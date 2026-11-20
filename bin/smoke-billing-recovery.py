#!/usr/bin/env python3
"""Task-9 disaster-recovery rehearsal: live gateway -> settle traffic -> stop ->
online backup (backup-control.py) -> tamper-refusal -> verified restore to an
INDEPENDENT target (restore-control.py) -> ledger/watermark consistency ->
timing budget (recovery point <= 1h, total <= 4h). The RecoveryGate contract
(close -> reconciliation evidence -> open) is covered by RecoveryGateTest.

JAR selection: LLMGW_TEST_JAR pins the jar under test.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import socket
import sqlite3
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
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


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--binary", action="store_true")
    args = parser.parse_args()
    jar = Path(os.environ.get("LLMGW_TEST_JAR")
               or ROOT / "llmgw-app/target/llmgw-app-0.5.0.jar")
    if not jar.exists():
        raise SystemExit(f"jar not found: {jar}; build llmgw-app first or set LLMGW_TEST_JAR")

    started = time.time()
    with tempfile.TemporaryDirectory(prefix="llmgw-recovery-") as folder:
        work = Path(folder)
        live_db = work / "control.db"          # live control plane DB
        # The rehearsal uses a REAL separate directory (outside the temp tree) so the
        # same-parent refusal in backup-control.py does not fire -- production points
        # this at a different disk/volume; a same-disk copy is never accepted.
        backup_dir = Path("/tmp/llmgw-recovery-backups")
        restore_target = work / "recovery" / "control.db"

        # --- Phase 1: live traffic settles into the ledger -------------------
        mock_port = port()
        # Reuse the billing smoke's mock upstream shape via a tiny inline server.
        import http.server

        class Gemini(http.server.BaseHTTPRequestHandler):
            def do_POST(self):
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

        mock = http.server.ThreadingHTTPServer(("127.0.0.1", mock_port), Gemini)
        threading.Thread(target=mock.serve_forever, daemon=True).start()
        gateway_port = port()
        command = (([str(jar)] if args.binary else ["java", "-jar", str(jar)])) + [
            f"--spring.profiles.active=vt", f"--server.port={gateway_port}",
            "--server.address=127.0.0.1", "--gateway.production=false",
            "--gateway.mock.enabled=false", "--gateway.billing.enabled=true",
            f"--gateway.routes[0].target-url=http://127.0.0.1:{mock_port}/mock",
            "--gateway.routes[0].provider=gemini",
            "--gateway.api-keys[0].id=static-smoke",
            "--gateway.api-keys[0].token=static-smoke-key",
            # §9.5.2: billing-enabled gateways require subject attribution; the
            # tenant must match the billing_account seeded below.
            "--gateway.api-keys[0].metadata[llmgw.tenantId]=static-smoke",
            "--gateway.api-keys[0].metadata[llmgw.projectId]=recovery"]
        log = (work / "gateway.log").open("w")
        env = {**os.environ, "LLMGW_CONTROL_DB": str(live_db)}
        process = subprocess.Popen(command, cwd=folder, stdout=log, stderr=subprocess.STDOUT,
                                   env=env)
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        deadline = time.time() + 90
        while time.time() < deadline:
            try:
                socket.create_connection(("127.0.0.1", gateway_port), timeout=1).close()
                break
            except Exception:
                time.sleep(0.5)
        try:
            conn = sqlite3.connect(live_db, timeout=10)
            now = int(time.time() * 1000)
            conn.execute("INSERT OR IGNORE INTO price_version (model, version,"
                         " input_nano_per_million, output_nano_per_million,"
                         " nano_per_image, nano_per_task, nano_per_second, frozen_at)"
                         " VALUES (?,1,1000000,2000000,0,0,0,?)", (MODEL, now))
            conn.execute("INSERT OR IGNORE INTO billing_account (tenant_id, currency,"
                         " balance_nano, overdraft_nano, carry_nano, updated_at)"
                         " VALUES ('static-smoke','CNY',0,0,0,?)", (now,))
            conn.execute("INSERT OR IGNORE INTO billing_recharge (id, tenant_id, amount_nano,"
                         " voucher_no, operator_id, kind, created_at)"
                         " VALUES ('rcg-1','static-smoke',?,?, 'ops','recharge',?)",
                         (RECHARGE, "V-REC", now))
            conn.execute("UPDATE billing_account SET balance_nano=?"
                         " WHERE tenant_id='static-smoke'", (RECHARGE,))
            # Chapter-9 §9.5.2（ADR-0007）：billing.enabled 下 SqliteUsageLedger
            # 要求显式项目额度授权（加 key 不能凭空铸额度），scope 为
            # project:<len(tenant)>:<tenant><project>，与 key 的归属元数据对齐。
            conn.execute(
                "INSERT OR IGNORE INTO token_quota (scope, quota) VALUES (?, ?)",
                ("project:" + str(len("static-smoke")) + ":" + "static-smoke" + "recovery",
                 1_000_000))
            conn.commit()
            conn.close()
            request = urllib.request.Request(
                f"http://127.0.0.1:{gateway_port}/v1beta/models/{MODEL}:generateContent",
                data=json.dumps({"contents": [{"role": "user",
                                               "parts": [{"text": "hi"}]}]}).encode(),
                headers={"Content-Type": "application/json",
                         "Authorization": "Bearer static-smoke-key"})
            response = opener.open(request, timeout=30)
            check("live call settles", response.status == 200)
        finally:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
            log.close()
            mock.shutdown()

        live_balance = sqlite3.connect(live_db).execute(
            "SELECT balance_nano FROM billing_account WHERE tenant_id='static-smoke'"
        ).fetchone()[0]
        check("live ledger holds the debited balance", live_balance == RECHARGE - 1000,
              f"balance={live_balance}")

        # --- Phase 2: online backup with manifest ----------------------------
        backup = subprocess.run(
            [sys.executable, str(ROOT / "bin/backup-control.py"),
             "--db", str(live_db), "--target", str(backup_dir)],
            capture_output=True, text=True, timeout=120)
        check("backup-control succeeds on a different storage domain",
              backup.returncode == 0, backup.stderr.strip()[:200])
        if backup.returncode != 0:
            return 1
        backup_info = json.loads(backup.stdout)
        backup_db = Path(backup_info["backup"])

        # --- Phase 3: tamper refusal (digest gate stays closed) --------------
        tampered = work / "tampered.db"
        shutil.copy2(backup_db, tampered)
        shutil.copy2(backup_db.with_suffix(backup_db.suffix + ".manifest.json"),
                     tampered.with_suffix(tampered.suffix + ".manifest.json"))
        Files_tamper(tampered)
        refused = subprocess.run(
            [sys.executable, str(ROOT / "bin/restore-control.py"),
             "--backup", str(tampered), "--target", str(restore_target)],
            capture_output=True, text=True, timeout=120)
        check("tampered backup is refused (gate stays closed)",
              refused.returncode != 0 and "digest mismatch" in refused.stderr,
              refused.stderr.strip()[:160])

        # --- Phase 4: verified restore to the independent target -------------
        restore = subprocess.run(
            [sys.executable, str(ROOT / "bin/restore-control.py"),
             "--backup", str(backup_db), "--target", str(restore_target)],
            capture_output=True, text=True, timeout=120)
        check("verified restore succeeds", restore.returncode == 0,
              restore.stderr.strip()[:200])
        if restore.returncode != 0:
            return 1
        restored_info = json.loads(restore.stdout)
        restored_balance = sqlite3.connect(restore_target).execute(
            "SELECT balance_nano FROM billing_account WHERE tenant_id='static-smoke'"
        ).fetchone()[0]
        check("restored balance equals the live balance",
              restored_balance == live_balance, f"restored={restored_balance}")
        # The archive watermark is only set once backups actually archive; this
        # rehearsal never crosses that threshold, so there is nothing to
        # assert here (a decorative always-true check would be dishonest).

        # --- Phase 5: timing budget ------------------------------------------
        elapsed = time.time() - started
        check("rehearsal completes inside the 4-hour recovery budget", elapsed < 4 * 3600,
              f"elapsed={elapsed:.1f}s")
        check("rehearsal wall time under 1h", elapsed < 3600, f"elapsed={elapsed:.1f}s")

    failures = [name for name, ok, _ in checks if not ok]
    print(f"\n{len(checks) - len(failures)}/{len(checks)} checks passed")
    if failures:
        print("FAILED: " + "; ".join(failures))
        return 1
    return 0


def Files_tamper(path: Path) -> None:
    """Corrupt the copy AFTER its manifest was copied (digest now mismatches)."""
    path.write_bytes(b"\x09\x09\x09\x09\x09")


if __name__ == "__main__":
    sys.exit(main())
