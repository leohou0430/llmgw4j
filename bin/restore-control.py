#!/usr/bin/env python3
"""Task-9 operator entry: verify a backup manifest and place a verified copy at
the recovery target. The gateway must be started in maintenance after restore;
it OPENS only after the operator records reconciliation evidence (archive
watermark digest + gap check) -- see docs/control-plane-operations.md.

Usage: restore-control.py --backup <control-*.db> --target <recovery control.db>
Exit codes: 0 ok, 2 usage, 3 verification failed (gate stays closed).
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import sqlite3
import sys


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--backup", required=True, help="backup .db produced by backup-control")
    parser.add_argument("--target", required=True, help="recovery control.db path")
    args = parser.parse_args()

    backup = Path(args.backup)
    manifest_path = backup.with_suffix(backup.suffix + ".manifest.json")
    if not backup.exists() or not manifest_path.exists():
        print(f"backup or manifest missing: {backup} / {manifest_path}", file=sys.stderr)
        return 3
    try:
        manifest = json.loads(manifest_path.read_text())
    except (ValueError, OSError) as failure:
        print(f"manifest unreadable (gate stays closed): {failure}", file=sys.stderr)
        return 3

    actual = sha256(backup)
    if manifest.get("sha256") != actual:
        print(f"REFUSED: digest mismatch manifest={manifest.get('sha256')} actual={actual};"
              " restore gate stays closed", file=sys.stderr)
        return 3

    # Sanity: the backup must open as a database with the expected ledger schema.
    try:
        conn = sqlite3.connect(f"file:{backup}?mode=ro", uri=True, timeout=10)
        tables = {row[0] for row in conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table'").fetchall()}
        conn.close()
    except sqlite3.Error as failure:
        print(f"REFUSED: backup does not open as sqlite ({failure}); gate stays closed",
              file=sys.stderr)
        return 3
    required = {"billing_account", "billing_recharge", "billing_reservation",
                "billing_reservation_archive", "billing_archive_watermark"}
    missing = required - tables
    if missing:
        print(f"REFUSED: backup lacks billing tables {sorted(missing)}; gate stays closed",
              file=sys.stderr)
        return 3

    target = Path(args.target)
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(backup, target)
    shutil.copy2(manifest_path, target.with_suffix(target.suffix + ".manifest.json"))
    conn = sqlite3.connect(target, timeout=10)
    watermark = conn.execute(
        "SELECT high_water_mark, digest, archived_count FROM billing_archive_watermark"
        " WHERE id=1").fetchone()
    conn.close()
    print(json.dumps({"restored": str(target), "sha256": actual,
                      "archiveWatermark": {"highWaterMark": watermark[0],
                                           "digest": watermark[1],
                                           "archivedCount": watermark[2]} if watermark else None,
                      "nextStep": "start gateway in maintenance; record reconciliation"
                                  " evidence; open the recovery gate"}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
