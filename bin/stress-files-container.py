#!/usr/bin/env python3
"""T2.10 2C2G container file stress.

Deploys the CURRENT app jar into the repo runtime image under a hard
2 CPU / 2 GiB (no swap) cgroup, then measures the bounded Files upload path:
4 parallel 64 MiB uploads + concurrent admin traffic, RSS/CPU sampling, OOM
absence, saturation 503, cancel -> permit-zero -> recovery, and temp spool
cleanup. Real measurements only; nothing here reuses stage-1 resource results.

Usage: python3 bin/stress-files-container.py [--engine webflux|vt] [--keep]
Evidence JSON: docs/verification/<date>/stage2-files/t2.10-container-stress.json
"""
import argparse
import datetime
import json
import os
import re
import socket
import struct
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
IMAGE = "llmgw4j:v13-test"
NAME = "llmgw-stress-t210"
HOST_PORT = 18080
FILE_BYTES = 64 * 1024 * 1024
BOUNDARY = "----llmgwstressT210"
TOKEN = "sk-demo"


def run(args):
    """Run a command as an argument list (no shell parsing)."""
    return subprocess.run(args, capture_output=True, text=True)


def multipart_parts(payload_len):
    head = (
        f"--{BOUNDARY}\r\n"
        f'Content-Disposition: form-data; name="purpose"\r\n\r\n'
        f"fine-tune\r\n"
        f"--{BOUNDARY}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="stress.bin"\r\n'
        f"Content-Type: application/octet-stream\r\n\r\n"
    ).encode()
    tail = f"\r\n--{BOUNDARY}--\r\n".encode()
    return head, tail


def build_body(payload):
    head, tail = multipart_parts(len(payload))
    return head + payload + tail


def http_upload(body, chunk_delay=0.0, abort_after=None, timeout=120):
    """Send a multipart upload over a raw socket -> (status, resp_bytes, aborted).

    chunk_delay>0 throttles the client (slow-write path). abort_after=N sends N
    body bytes then abruptly closes the socket (client cancel mid-upload).
    """
    headers = (
        f"POST /v1/files HTTP/1.1\r\n"
        f"Host: 127.0.0.1:{HOST_PORT}\r\n"
        f"Authorization: Bearer {TOKEN}\r\n"
        f"Content-Type: multipart/form-data; boundary={BOUNDARY}\r\n"
        f"Content-Length: {len(body)}\r\n"
        f"Connection: close\r\n\r\n"
    ).encode()
    s = socket.create_connection(("127.0.0.1", HOST_PORT), timeout=timeout)
    try:
        s.sendall(headers)
        sent = 0
        step = 256 * 1024
        while sent < len(body):
            if abort_after is not None and sent >= abort_after:
                s.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0))
                s.close()
                return None, b"", True
            end = min(sent + step, len(body))
            if abort_after is not None:
                end = min(end, abort_after)
            s.sendall(body[sent:end])
            sent = end
            if chunk_delay:
                time.sleep(chunk_delay)
        s.settimeout(timeout)
        data = b""
        while True:
            try:
                part = s.recv(65536)
            except socket.timeout:
                break
            if not part:
                break
            data += part
        m = re.match(rb"HTTP/1\.[01] (\d{3})", data)
        return (int(m.group(1)) if m else None), data, False
    finally:
        try:
            s.close()
        except OSError:
            pass


def admin_get(path):
    req = urllib.request.Request(f"http://127.0.0.1:{HOST_PORT}{path}")
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status
    except urllib.error.HTTPError as e:
        return e.code
    except Exception:
        return None


def stats_sample():
    r = run(["docker", "stats", "--no-stream", "--format",
             "{{.MemUsage}}|{{.CPUPerc}}", NAME])
    line = r.stdout.strip()
    if "|" not in line:
        return None
    mem, cpu = line.split("|", 1)
    return {"mem_used": mem.split("/")[0].strip(), "cpu": cpu.strip()}


def parse_mem(s):
    m = re.match(r"([\d.]+)\s*([KMGT]?i?B)", s)
    if not m:
        return 0.0
    mult = {"B": 1, "KiB": 1024, "MiB": 1024**2, "GiB": 1024**3, "TiB": 1024**4,
            "KB": 1000, "MB": 1000**2, "GB": 1000**3}
    return float(m.group(1)) * mult.get(m.group(2), 1)


def spool_file_count(engine):
    cmd = f"find /tmp/llmgw-spool/8080/{engine} -type f ! -name .owner.lock 2>/dev/null | wc -l"
    r = run(["docker", "exec", NAME, "sh", "-c", cmd])
    try:
        return int(r.stdout.strip())
    except ValueError:
        return None


def wait_health(timeout=90):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if admin_get("/actuator/health") == 200:
            return True
        time.sleep(1)
    return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--engine", default="webflux", choices=["webflux", "vt"])
    ap.add_argument("--keep", action="store_true")
    ap.add_argument("--jar", default=os.environ.get("LLMGW_TEST_JAR", os.path.join(ROOT,"llmgw-app","target","llmgw-app-0.5.0.jar")))
    ap.add_argument("--out")
    args = ap.parse_args()
    global NAME, HOST_PORT
    NAME = "llmgw-remediation-" + uuid.uuid4().hex[:12]
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0)); HOST_PORT = listener.getsockname()[1]
    engine = args.engine

    jar = os.path.abspath(args.jar)
    if not os.path.isfile(jar):
        print("FATAL: app jar missing; build first", file=sys.stderr)
        return 2
    jar_sha = run(["shasum", "-a", "256", jar]).stdout.split()[0]
    image_id = run(["docker", "image", "inspect", IMAGE, "--format", "{{.Id}}"]).stdout.strip()

    spring_json = json.dumps({"gateway": {
        "max-request-bytes": 80 * 1024 * 1024,
        "max-concurrent-requests": 4,
        "governance-enabled": True,
        "admin-token-mode": "migration",
        "governance": {"default-quota-tokens": 2000000000,
                       "rate-limit-tokens-per-second": 2000000000,
                       "rate-limit-burst": 2000000000},
        "api-keys": [{"id": "demo", "token": TOKEN, "name": "demo",
                      "quota-tokens": 2000000000, "allowed-models": []}],
        "mock": {"enabled": True, "port": 9090, "delay-ms": 0},
    }})

    r = run([
        "docker", "run", "-d", "--name", NAME,
        "--cpus=2", "--memory=2g", "--memory-swap=2g",
        "-p", f"127.0.0.1:{HOST_PORT}:8080",
        "-v", f"{jar}:/app/llmgw.jar:ro",
        "-e", f"SPRING_PROFILES_ACTIVE={engine}",
        "-e", "JAVA_OPTS=-Xmx1g -XX:MaxGCPauseMillis=10",
        "-e", f"SPRING_APPLICATION_JSON={spring_json}",
        IMAGE,
    ])
    if r.returncode != 0:
        print("FATAL docker run:", r.stderr, file=sys.stderr)
        return 2

    evidence = {
        "date": datetime.date.today().isoformat(),
        "engine": engine, "image": IMAGE, "image_id": image_id,
        "jar_sha256": jar_sha, "container_limits": {"cpus": 2, "memory": "2g", "swap": "2g"},
        "file_bytes": FILE_BYTES, "parallel_uploads": 4,
        "phases": {}, "assertions": [],
    }

    def check(name, ok, detail=""):
        evidence["assertions"].append({"name": name, "pass": bool(ok), "detail": detail})
        print(f"[{'PASS' if ok else 'FAIL'}] {name} {detail}")

    stop = threading.Event()
    samples = []
    t0 = time.time()

    def sampler():
        while not stop.is_set():
            s = stats_sample()
            if s:
                s["t"] = round(time.time() - t0, 2)
                samples.append(s)
            time.sleep(0.4)

    try:
        if not wait_health():
            logs = run(["docker", "logs", NAME]).stdout[-4000:]
            print("FATAL: health never UP\n", logs, file=sys.stderr)
            evidence["fatal"] = "health timeout"
            return 1
        check("health_up", True)

        warm = build_body(os.urandom(1024 * 1024))
        ws, wb, _ = http_upload(warm)
        check("warmup_upload_200", ws == 200, f"status={ws} body={wb[:200]!r}")
        if ws != 200:
            print("FATAL warmup failed; aborting stress", file=sys.stderr)
            return 1

        body = build_body(os.urandom(FILE_BYTES))
        sampler_thread = threading.Thread(target=sampler, daemon=True)
        sampler_thread.start()

        # Phase A: 4 slow parallel 64 MiB uploads + concurrent admin + 5th saturation.
        results = {}

        def uploader(i):
            st, _, _ = http_upload(body, chunk_delay=0.01)
            results[i] = st

        threads = [threading.Thread(target=uploader, args=(i,)) for i in range(4)]
        admin_hits = []
        admin_stop = threading.Event()

        def admin_loop():
            paths = ["/admin/snapshot", "/admin/metrics", "/admin/routes"]
            k = 0
            while not admin_stop.is_set():
                admin_hits.append(admin_get(paths[k % len(paths)]))
                k += 1
                time.sleep(0.05)

        admin_thread = threading.Thread(target=admin_loop, daemon=True)
        for th in threads:
            th.start()
        admin_thread.start()
        time.sleep(1.0)
        fifth_status, _, _ = http_upload(build_body(os.urandom(64 * 1024)))
        during_spool = spool_file_count(engine)
        for th in threads:
            th.join(timeout=120)
        admin_stop.set()
        admin_thread.join(timeout=5)
        after_spool = spool_file_count(engine)

        check("four_64MiB_uploads_200", all(results.get(i) == 200 for i in range(4)),
              f"statuses={results}")
        check("fifth_saturated_503", fifth_status == 503, f"status={fifth_status}")
        admin_ok = sum(1 for x in admin_hits if x == 200)
        check("admin_concurrent_responsive", admin_ok >= 5, f"admin_200={admin_ok}/{len(admin_hits)}")
        check("spool_present_during_stress", (during_spool or 0) >= 1, f"files_during={during_spool}")
        check("spool_cleaned_after_uploads", after_spool == 0, f"files_after={after_spool}")
        evidence["phases"]["A"] = {
            "upload_statuses": results, "fifth_status": fifth_status,
            "admin_200": admin_ok, "admin_total": len(admin_hits),
            "spool_during": during_spool, "spool_after": after_spool,
            "duration_s": round(time.time() - t0, 2),
        }

        # Phase B: cancel mid-upload -> permit returns -> fresh upload 200.
        cs, _, aborted = http_upload(body, abort_after=8 * 1024 * 1024)
        time.sleep(1.5)
        cancel_spool = spool_file_count(engine)
        rs, _, _ = http_upload(build_body(os.urandom(2 * 1024 * 1024)))
        check("cancel_aborted", aborted, f"status={cs}")
        check("cancel_spool_cleaned", cancel_spool == 0, f"files={cancel_spool}")
        check("permit_zero_after_cancel_recovery_200", rs == 200, f"status={rs}")
        evidence["phases"]["B"] = {"cancel_status": cs, "spool_after_cancel": cancel_spool,
                                   "recovery_status": rs}

        # Phase C: steady-state recovery — fresh 64 MiB upload + admin healthy.
        cs2, _, _ = http_upload(body, chunk_delay=0.005)
        admin_c = admin_get("/admin/snapshot")
        final_spool = spool_file_count(engine)
        check("recovery_64MiB_200", cs2 == 200, f"status={cs2}")
        check("recovery_admin_200", admin_c == 200, f"status={admin_c}")
        check("final_spool_empty", final_spool == 0, f"files={final_spool}")
        evidence["phases"]["C"] = {"upload_status": cs2, "admin_status": admin_c,
                                   "spool_final": final_spool}

        stop.set()
        sampler_thread.join(timeout=3)
        peak_mem = max((parse_mem(s["mem_used"]) for s in samples), default=0)
        peak_cpu = 0.0
        for s in samples:
            m = re.match(r"([\d.]+)%", s["cpu"])
            if m:
                peak_cpu = max(peak_cpu, float(m.group(1)))
        evidence["resources"] = {
            "samples": len(samples),
            "peak_rss_bytes": int(peak_mem),
            "peak_rss_mib": round(peak_mem / 1024**2, 1),
            "peak_cpu_percent": round(peak_cpu, 1),
            "memory_limit_bytes": 2 * 1024**3,
            "rss_under_limit": peak_mem < 2 * 1024**3,
        }
        check("rss_under_2GiB", peak_mem < 2 * 1024**3,
              f"peak={round(peak_mem/1024**2,1)}MiB samples={len(samples)}")

        insp = run(["docker", "inspect", NAME, "--format",
                    "{{.State.OOMKilled}}|{{.State.Status}}|{{.State.ExitCode}}"]).stdout.strip()
        oom, status, exitcode = (insp.split("|") + ["", "", ""])[:3]
        evidence["container_state"] = {"oom_killed": oom, "status": status, "exit_code": exitcode}
        check("no_oom_kill", oom == "false", f"OOMKilled={oom} status={status}")
        check("container_running", status == "running", f"status={status}")

    finally:
        if not args.keep:
            run(["docker", "rm", "-f", NAME])

    outdir = os.path.join(ROOT, "docs", "verification", evidence["date"], "stage2-files")
    os.makedirs(outdir, exist_ok=True)
    outpath = args.out or os.path.join(outdir, f"t2.10-container-stress-{engine}.json")
    with open(outpath, "w") as f:
        json.dump(evidence, f, indent=2)
    failed = [a for a in evidence["assertions"] if not a["pass"]]
    print(f"\nEvidence: {outpath}")
    print(f"Assertions: {len(evidence['assertions'])} total, {len(failed)} failed")
    if "resources" in evidence:
        print(f"Peak RSS: {evidence['resources']['peak_rss_mib']} MiB / 2048 MiB; "
              f"peak CPU: {evidence['resources']['peak_cpu_percent']}%")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())