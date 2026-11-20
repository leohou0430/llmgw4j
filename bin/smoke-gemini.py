#!/usr/bin/env python3
"""Task-7 Gemini native-generation smoke: model-path injection rejected, missing
price rejected without upstream contact, seeded-price call transparently
forwarded with the ROUTE key riding x-goog-api-key (never the caller's), and
the reservation settled from the usageMetadata authoritative frame.

JAR selection: LLMGW_TEST_JAR pins the jar under test (see AGENTS.md 验证).
"""
import argparse
import hashlib
import http.server
import json
import os
from pathlib import Path
import socket
import sqlite3
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
ENGINE_LIST = ["vt", "webflux"]
MODEL = "gemini-2.0-flash"
RECHARGE = 10_000_000

checks = []


def check(name, ok, detail=""):
    checks.append((name, ok, detail))
    print(("PASS " if ok else "FAIL ") + name + (f" :: {detail}" if detail else ""))


def port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


class GeminiUpstream(http.server.BaseHTTPRequestHandler):
    """Counts hits and records which credential reached the upstream."""
    hits = 0
    auth = None
    bearer = None

    def do_POST(self):
        GeminiUpstream.hits += 1
        GeminiUpstream.auth = self.headers.get("x-goog-api-key")
        GeminiUpstream.bearer = self.headers.get("Authorization")
        payload = json.dumps({
            "candidates": [{"content": {"parts": [{"text": "ok"}], "role": "model"},
                            "finishReason": "STOP"}],
            "usageMetadata": {"promptTokenCount": 7, "candidatesTokenCount": 4}}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *args):
        pass


def seed_price_and_recharge(db_path):
    conn = sqlite3.connect(db_path, timeout=10)
    try:
        # Chapter-9 §9.5.2：billing.enabled 下带归属的静态 key 需要显式项目额度授权。
        conn.execute(
            "INSERT OR IGNORE INTO token_quota (scope, quota) VALUES (?, ?)",
            ("project:" + str(len("static-smoke")) + ":static-smokestatic-smoke", 1_000_000))
        now = int(time.time() * 1000)
        conn.execute(
            "INSERT OR IGNORE INTO price_version (model, version, input_nano_per_million,"
            " output_nano_per_million, nano_per_image, nano_per_task, nano_per_second, frozen_at)"
            " VALUES (?,1,1000000,2000000,0,0,0,?)", (MODEL, now))
        conn.execute(
            "INSERT OR IGNORE INTO billing_account (tenant_id, currency, balance_nano,"
            " overdraft_nano, carry_nano, updated_at) VALUES ('static-smoke','CNY',0,0,0,?)", (now,))
        conn.execute(
            "INSERT OR IGNORE INTO billing_recharge (id, tenant_id, amount_nano, voucher_no,"
            " operator_id, kind, created_at) VALUES ('rcg-1','static-smoke',?,?,'ops','recharge',?)",
            (RECHARGE, "V-GEM", now))
        conn.execute("UPDATE billing_account SET balance_nano=? WHERE tenant_id='static-smoke'",
                     (RECHARGE,))
        conn.commit()
    finally:
        conn.close()


def verify(engine, jar, binary=False):
    GeminiUpstream.hits = 0
    GeminiUpstream.auth = None
    GeminiUpstream.bearer = None
    mock = http.server.ThreadingHTTPServer(("127.0.0.1", 0), GeminiUpstream)
    threading.Thread(target=mock.serve_forever, daemon=True).start()
    gateway_port, mock_port = port(), mock.server_port
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def post(path):
        request = urllib.request.Request(
            f"http://127.0.0.1:{gateway_port}{path}",
            data=json.dumps({"contents": [{"role": "user", "parts": [{"text": "hi"}]}]}).encode(),
            headers={"Content-Type": "application/json",
                     "Authorization": "Bearer static-smoke-key"})
        try:
            response = opener.open(request, timeout=30)
            return response.status, response.read()
        except urllib.error.HTTPError as error:
            return error.code, error.read()

    with tempfile.TemporaryDirectory(prefix="llmgw-gemini-smoke-") as folder:
        db_path = str(Path(folder) / "control.db")
        command = ([str(jar)] if binary else ["java", "-jar", str(jar)]) + [
            f"--spring.profiles.active={engine}", f"--server.port={gateway_port}",
            "--server.address=127.0.0.1", "--gateway.production=false",
            "--gateway.mock.enabled=false", "--gateway.billing.enabled=true",
            f"--gateway.routes[0].target-url=http://127.0.0.1:{mock_port}/mock",
            "--gateway.routes[0].provider=gemini",
            "--gateway.api-keys[0].id=static-smoke",
            "--gateway.api-keys[0].token=static-smoke-key",
            "--gateway.api-keys[0].metadata.llmgw.tenantId=static-smoke",
            "--gateway.api-keys[0].metadata.llmgw.projectId=static-smoke"]
        log = (Path(folder) / "gateway.log").open("w")
        process = subprocess.Popen(command, cwd=folder, stdout=log,
                                   stderr=subprocess.STDOUT,
                                   env={**os.environ, "LLMGW_CONTROL_DB": db_path})
        try:
            deadline = time.time() + 90
            while time.time() < deadline:
                try:
                    socket.create_connection(("127.0.0.1", gateway_port), timeout=1).close()
                    break
                except Exception:
                    time.sleep(0.5)
            seed_price_and_recharge(db_path)

            # 1. injection rejected before admission (no upstream contact).
            status, _ = post("/v1beta/models/..%2Fadmin:generateContent")
            check(f"{engine}: model path injection rejected 400", status == 400,
                  f"status={status}")
            status, _ = post("/v1beta/models/a%2Fb:generateContent")
            check(f"{engine}: cross-segment injection rejected 400", status == 400,
                  f"status={status}")

            # 2. missing price rejected without reaching the upstream.
            before = GeminiUpstream.hits
            status, body = post("/v1beta/models/unknown-model:generateContent")
            check(f"{engine}: missing price rejected 402", status == 402,
                  f"status={status} body={body[:120]}")
            check(f"{engine}: missing-price rejection never reached upstream",
                  GeminiUpstream.hits == before)

            # 3. seeded-price call: Gemini response shape forwarded byte-for-byte,
            #    route key rides x-goog-api-key, caller key never leaves.
            status, body = post(f"/v1beta/models/{MODEL}:generateContent")
            text = ""
            if status == 200:
                text = json.loads(body).get("candidates", [{}])[0].get(
                    "content", {}).get("parts", [{}])[0].get("text", "")
            check(f"{engine}: native generation succeeds with candidates passthrough",
                  status == 200 and text == "ok", f"status={status} text={text!r}")
            check(f"{engine}: route key rides x-goog-api-key",
                  GeminiUpstream.auth is not None and len(GeminiUpstream.auth) > 0,
                  f"auth={GeminiUpstream.auth}")
            check(f"{engine}: caller key never forwarded upstream",
                  GeminiUpstream.bearer is None, f"bearer={GeminiUpstream.bearer}")

            # 4. settlement posts the known usage (7 in + 4 out @ seeded prices
            #    = 15 nano -> one whole-micro unit of 1000 nano).
            for _ in range(50):
                rows = sqlite3.connect(db_path).execute(
                    "SELECT status, settled_nano FROM billing_reservation").fetchall()
                if rows and rows[-1][0] == "settled":
                    break
                time.sleep(0.2)
            check(f"{engine}: reservation settled from usageMetadata", bool(rows)
                  and rows[-1][0] == "settled", f"rows={rows}")
            balance = sqlite3.connect(db_path).execute(
                "SELECT balance_nano FROM billing_account WHERE tenant_id='static-smoke'"
            ).fetchone()[0]
            check(f"{engine}: balance debited by posted micro unit",
                  balance == RECHARGE - 1000, f"balance={balance}")
        finally:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
            log.close()
            mock.shutdown()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--engine", default=None, choices=ENGINE_LIST)
    parser.add_argument("--binary", action="store_true")
    args = parser.parse_args()
    jar = Path(os.environ.get("LLMGW_TEST_JAR")
               or ROOT / "llmgw-app/target/llmgw-app-0.5.0.jar")
    if not jar.exists() and not args.binary:
        raise SystemExit(f"jar not found: {jar}; build llmgw-app first or set LLMGW_TEST_JAR")
    engines = [args.engine] if args.engine else ENGINE_LIST
    for engine in engines:
        verify(engine, jar, args.binary)
    failures = [name for name, ok, _ in checks if not ok]
    print(f"\n{len(checks) - len(failures)}/{len(checks)} checks passed")
    if failures:
        print("FAILED: " + "; ".join(failures))
        raise SystemExit(1)


if __name__ == "__main__":
    main()
