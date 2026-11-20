#!/usr/bin/env python3
"""Task-9 operator entry: take a consistent online backup of the control DB
(VACUUM INTO) and write a SHA-256 manifest next to it. The TARGET must be an
operator-configured path on a DIFFERENT storage domain -- a same-disk copy is
never an independent failure domain (ADR-0007 task 9).

Usage: backup-control.py --db <control.db> --target <dir-on-other-disk>
Exit codes: 0 ok, 2 usage, 3 backup failed.
"""
import argparse
import hashlib
import json
from pathlib import Path
import sqlite3
import sys
import time


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--db", required=True, help="live control database path")
    parser.add_argument("--target", required=True,
                        help="backup directory (MUST be a different storage domain)")
    args = parser.parse_args()

    source = Path(args.db)
    if not source.exists():
        print(f"control db not found: {source}", file=sys.stderr)
        return 2
    target_dir = Path(args.target)
    target_dir.mkdir(parents=True, exist_ok=True)
    if target_dir.resolve().parent == source.resolve().parent:
        print("REFUSED: target directory shares a parent with the source database;"
              " a same-disk copy is not an independent failure domain", file=sys.stderr)
        return 3

    stamp = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime())
    backup = target_dir / f"control-{stamp}.db"
    # VACUUM INTO yields a transactionally consistent snapshot of the live DB.
    conn = sqlite3.connect(f"file:{source}?mode=ro", uri=True, timeout=30)
    try:
        conn.execute("VACUUM INTO ?", (str(backup),))
    except sqlite3.Error as failure:
        print(f"backup failed: {failure}", file=sys.stderr)
        return 3
    finally:
        conn.close()

    manifest_path = backup.with_suffix(backup.suffix + ".manifest.json")
    manifest = {
        "file": backup.name,
        "sha256": sha256(backup),
        "createdAtEpochMs": int(time.time() * 1000),
    }
    manifest_path.write_text(json.dumps(manifest, indent=2))
    print(json.dumps({"backup": str(backup), "manifest": str(manifest_path),
                      "sha256": manifest["sha256"], "bytes": backup.stat().st_size}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
