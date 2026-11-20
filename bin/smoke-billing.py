#!/usr/bin/env python3
"""ADR-0007 billing smoke: seeded price -> recharge -> settled call -> 402 on
insufficient funds -> missing-price rejection -> frozen price on in-flight calls.
Runs against both real data planes with a request-counting local mock.

JAR selection: LLMGW_TEST_JAR pins the jar under test so profile/AOT rebuilds
cannot silently replace it (see AGENTS.md 验证).
"""
import argparse
import http.cookiejar
import http.server
import json
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
IN_PRICE, OUT_PRICE = 1_000_000, 2_000_000  # nano-CNY per million tokens
RECHARGE = 10_000_000
TENANT = 'static-smoke'  # Stage-1 scope mapping: blank tenant falls back to keyId
PROJECT = 'static-smoke'  # 与静态 key 的归属元数据一致（Chapter-9 §9.5.2）
MODEL = "qwen-plus"

checks = []


def check(name, ok, detail=""):
    checks.append((name, ok, detail))
    print(("PASS " if ok else "FAIL ") + name + (f" :: {detail}" if detail else ""))


def port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


class CountingUpstream(http.server.BaseHTTPRequestHandler):
    """Counts upstream hits so rejections can be proven to never reach it."""
    hits = 0

    def do_POST(self):
        CountingUpstream.hits += 1
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))))
        if body.get("stream"):
            frames = [
                {"choices": [{"index": 0, "delta": {"content": "ok"}, "finish_reason": None}]},
                {"choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}],
                 "usage": {"prompt_tokens": 1, "completion_tokens": 1}},
            ]
            payload = ("".join("data: " + json.dumps(f) + "\n\n" for f in frames)
                       + "data: [DONE]\n\n").encode()
            ctype = "text/event-stream"
        else:
            payload = json.dumps({"choices": [{"message": {"role": "assistant", "content": "ok"},
                                               "finish_reason": "stop"}],
                                  "usage": {"prompt_tokens": 1, "completion_tokens": 1}}).encode()
            ctype = "application/json"
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *args):
        pass


def db_state(db_path, sql, args=()):
    conn = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True, timeout=5)
    try:
        return conn.execute(sql, args).fetchall()
    finally:
        conn.close()


def seed_and_recharge(db_path):
    conn = sqlite3.connect(db_path, timeout=10)
    try:
        now = int(time.time() * 1000)
        conn.execute(
            "INSERT OR IGNORE INTO price_version (model, version, input_nano_per_million,"
            " output_nano_per_million, nano_per_image, nano_per_task, nano_per_second, frozen_at)"
            " VALUES (?,?,?,?,?,?,?,?)", (MODEL, 1, IN_PRICE, OUT_PRICE, 0, 0, 0, now))
        conn.execute(
            "INSERT OR IGNORE INTO billing_account (tenant_id, currency, balance_nano,"
            " overdraft_nano, carry_nano, updated_at) VALUES (?,'CNY',0,0,0,?)", (TENANT, now))
        # Chapter-9 §9.5.2（ADR-0007 v0.5 计费合同）：billing.enabled 下静态 key
        # 必须携带 tenant/project 归属元数据；带项目 scope 的 key 需要显式项目
        # 额度授权（SqliteUsageLedger：加 key 不能凭空铸额度）。
        conn.execute(
            "INSERT OR IGNORE INTO token_quota (scope, quota) VALUES (?, ?)",
            ("project:" + str(len(TENANT)) + ":" + TENANT + PROJECT, 1_000_000))

        conn.execute(
            "INSERT OR IGNORE INTO billing_recharge (id, tenant_id, amount_nano, voucher_no,"
            " operator_id, kind, created_at) VALUES ('rcg-smoke-1',?,?,?,'ops','recharge',?)",
            (TENANT, RECHARGE, "V-SMOKE", now))
        conn.execute("UPDATE billing_account SET balance_nano = ? WHERE tenant_id=?",
                     (RECHARGE, TENANT))
        conn.commit()
    finally:
        conn.close()


def balance(db_path):
    rows = db_state(db_path, "SELECT balance_nano FROM billing_account WHERE tenant_id=?", (TENANT,))
    return rows[0][0] if rows else None


def verify(engine, jar, binary=False):
    CountingUpstream.hits = 0
    mock = http.server.ThreadingHTTPServer(("127.0.0.1", 0), CountingUpstream)
    threading.Thread(target=mock.serve_forever, daemon=True).start()
    gateway_port, mock_port = port(), mock.server_port
    opener = urllib.request.build_opener(
        urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()),
        urllib.request.ProxyHandler({}))

    def call(model=MODEL, stream=False):
        data = json.dumps({"model": model, "stream": stream,
                           "messages": [{"role": "user", "content": "hi"}]}).encode()
        request = urllib.request.Request(
            f"http://127.0.0.1:{gateway_port}/v1/chat/completions", data=data,
            headers={"Content-Type": "application/json", "Authorization": "Bearer static-smoke-key"})
        return opener.open(request, timeout=30)

    with tempfile.TemporaryDirectory(prefix="llmgw-billing-smoke-") as folder:
        db_path = str(Path(folder) / "control.db")
        command = ([str(jar)] if binary else ["java", "-jar", str(jar)]) + [
            f"--spring.profiles.active={engine}", f"--server.port={gateway_port}",
            "--server.address=127.0.0.1", "--gateway.production=false",
            "--gateway.mock.enabled=false", "--gateway.billing.enabled=true",
            f"--gateway.routes[0].target-url=http://127.0.0.1:{mock_port}/mock/v1",
            "--gateway.api-keys[0].id=static-smoke",
            "--gateway.api-keys[0].token=static-smoke-key",
            "--gateway.api-keys[0].metadata.llmgw.tenantId=static-smoke",
            "--gateway.api-keys[0].metadata.llmgw.projectId=static-smoke"]
        admin_env = {"LLMGW_ADMIN_USERNAME": "platform",
                     "LLMGW_ADMIN_PASSWORD": "billing-admin-pass"}
        log = (Path(folder) / "gateway.log").open("w")
        process = subprocess.Popen(command, cwd=folder, stdout=log,
                                   stderr=subprocess.STDOUT,
                                   env={**dict(__import__("os").environ),
                                       "LLMGW_CONTROL_DB": db_path, **admin_env})
        try:
            deadline = time.time() + 90
            ready = False
            while time.time() < deadline and not ready:
                try:
                    opener.open(f"http://127.0.0.1:{gateway_port}/actuator/health", timeout=2)
                    ready = True
                except urllib.error.HTTPError:
                    ready = True  # endpoint exists, auth may reject: gateway is up
                except Exception:
                    time.sleep(0.5)
            if not ready:
                raise RuntimeError("gateway did not start; see log")

            seed_and_recharge(db_path)

            # 1. seeded-price chat call settles the reservation (1 in + 1 out token
            #    = 3 nano -> rounded up to one 1000-nano micro unit).
            with call() as response:
                check(f"{engine}: call succeeds with billing enabled",
                      response.status == 200)
            for _ in range(50):
                after = balance(db_path)
                if after is not None and after == RECHARGE - 1_000:
                    break
                time.sleep(0.2)
            check(f"{engine}: settlement posts one micro unit", after == RECHARGE - 1_000,
                  f"balance={after}")

            version = db_state(db_path, "SELECT price_version FROM billing_reservation"
                                        " WHERE tenant_id=? ORDER BY created_at DESC LIMIT 1",
                               (TENANT,))
            check(f"{engine}: reservation carries a frozen price version",
                  version and version[0][0] >= 1, f"version={version}")

            # 2. price update does NOT retarget new admissions away from v1 freeze:
            #    freeze() seeds the next version from the previous one, so v2 still
            #    charges the same unit prices (改价不追溯在途).
            conn = sqlite3.connect(db_path, timeout=10)
            conn.execute("INSERT OR IGNORE INTO price_version (model, version,"
                         " input_nano_per_million, output_nano_per_million,"
                         " nano_per_image, nano_per_task, nano_per_second, frozen_at)"
                         " VALUES (?,?,?,?,?,?,?,?)",
                         (MODEL, 2, 9_000_000, 9_000_000, 0, 0, 0, int(time.time() * 1000)))
            conn.commit()
            conn.close()
            with call() as response:
                check(f"{engine}: call after price update succeeds", response.status == 200)

            # 3. missing-price model is rejected WITHOUT touching the upstream.
            hits_before = CountingUpstream.hits
            try:
                call(model="no-price-model")
                check(f"{engine}: missing price rejected 402", False, "got 200")
            except urllib.error.HTTPError as error:
                check(f"{engine}: missing price rejected 402", error.code == 402,
                      f"status={error.code}")
            check(f"{engine}: missing-price rejection never reached upstream",
                  CountingUpstream.hits == hits_before,
                  f"hits={CountingUpstream.hits} before={hits_before}")

            # 3b. management plane over HTTP: platform admin recharges via
            #     /control/billing (voucher idempotency), anonymous writes rejected.
            login_req = urllib.request.Request(
                f"http://127.0.0.1:{gateway_port}/control/login",
                data=json.dumps({"username": "platform",
                                 "password": "billing-admin-pass"}).encode(),
                headers={"Content-Type": "application/json"})
            # The first administrator is seeded by an ApplicationRunner, which runs
            # AFTER the web server reports healthy; retry briefly on 401.
            deadline = time.time() + 10
            while True:
                try:
                    login = opener.open(login_req, timeout=15)
                    break
                except urllib.error.HTTPError as error:
                    if error.code != 401 or time.time() >= deadline:
                        raise
                    time.sleep(0.25)
            csrf = json.loads(login.read()).get("csrfToken", "")
            check(f"{engine}: control login for billing management",
                  login.status == 200 and bool(csrf), f"csrf={csrf[:8]}...")

            def control_post(path, body):
                req = urllib.request.Request(
                    f"http://127.0.0.1:{gateway_port}{path}",
                    data=json.dumps(body).encode(),
                    headers={"Content-Type": "application/json", "X-CSRF-Token": csrf})
                try:
                    response = opener.open(req, timeout=15)
                    return response.status, response.read()
                except urllib.error.HTTPError as error:
                    return error.code, error.read()

            status, body = control_post("/control/billing/recharge",
                                        {"tenantId": "static-smoke", "amountNano": 1,
                                         "voucherNo": "V-CP-HTTP"})
            check(f"{engine}: HTTP recharge by platform admin", status == 200,
                  f"status={status} body={body[:120]}")
            status, body = control_post("/control/billing/recharge",
                                        {"tenantId": "static-smoke", "amountNano": 1,
                                         "voucherNo": "V-CP-HTTP"})
            check(f"{engine}: HTTP voucher replay idempotent",
                  json.loads(body).get("alreadyRecorded") is True,
                  f"body={body[:120]}")

            anon = urllib.request.build_opener(urllib.request.ProxyHandler({}))
            anon_req = urllib.request.Request(
                f"http://127.0.0.1:{gateway_port}/control/billing/recharge",
                data=json.dumps({"tenantId": "static-smoke", "amountNano": 1,
                                 "voucherNo": "V-ANON"}).encode(),
                headers={"Content-Type": "application/json"})
            try:
                anon.open(anon_req, timeout=15)
                check(f"{engine}: anonymous billing write rejected", False, "got 200")
            except urllib.error.HTTPError as error:
                check(f"{engine}: anonymous billing write rejected",
                      error.code in (401, 403), f"status={error.code}")

            # 4. drained balance -> 402 spend rejection, upstream untouched.
            conn = sqlite3.connect(db_path, timeout=10)
            conn.execute("UPDATE billing_account SET balance_nano=0,"
                         " overdraft_nano=0 WHERE tenant_id=?", (TENANT,))
            conn.commit()
            conn.close()
            hits_before = CountingUpstream.hits
            try:
                call()
                check(f"{engine}: insufficient funds rejected 402", False, "got 200")
            except urllib.error.HTTPError as error:
                check(f"{engine}: insufficient funds rejected 402", error.code == 402,
                      f"status={error.code}")
            check(f"{engine}: insufficient funds never reached upstream",
                  CountingUpstream.hits == hits_before)
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
    jar = Path(__import__("os").environ.get("LLMGW_TEST_JAR")
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
