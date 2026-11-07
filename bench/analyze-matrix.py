#!/usr/bin/env python3
"""Reproducible readout for a bench-matrix summary CSV.

Usage:
    python3 bench/analyze-matrix.py bench/results/summary-<runid>.csv
    python3 bench/analyze-matrix.py --compare old.csv new.csv

Prints the full table, dispersion (coefficient of variation), cold-start
summary, paired on/off deltas and validity checks. It never "fixes" data:
rows with error_rate > 1% are flagged INVALID and excluded from conclusions.
"""
import argparse
import csv
import statistics as stats
import sys
from pathlib import Path

COLS = ("p50_ms", "p99_ms", "rps", "error_rate", "rss_kb", "startup_s")


def num(value):
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def load(path):
    with open(path, newline="") as handle:
        rows = list(csv.DictReader(handle))
    return [r for r in rows if r.get("mode") in ("json", "sse")]


def cv(values):
    values = [v for v in values if v is not None]
    if len(values) < 2:
        return float("nan")
    mean = stats.mean(values)
    return stats.pstdev(values) / mean * 100 if mean else float("nan")


def table(rows, label):
    print(f"\n=== {label} ===")
    head = f"{'engine':8} {'gov':4} {'rt':7} {'mode':5} {'p50':>8} {'p99':>9} {'rps':>8} {'err':>6} {'rss_kb':>8} {'start_s':>8} {'rss_src':>10}"
    print(head)
    print("-" * len(head))
    for r in rows:
        def f(col, fmt=".2f"):
            v = num(r.get(col))
            return format(v, fmt) if v is not None else str(r.get(col, "NA"))
        print(f"{r['engine']:8} {r['governance']:4} {r['runtime']:7} {r['mode']:5} "
              f"{f('p50_ms'):>8} {f('p99_ms'):>9} {f('rps', '.0f'):>8} "
              f"{f('error_rate', '.3f'):>6} {f('rss_kb', '.0f'):>8} "
              f"{f('startup_s', '.3f'):>8} {r.get('rss_source', 'NA'):>10}")


def dispersion(rows, label):
    print(f"\n=== dispersion / {label} ===")
    for mode in ("json", "sse"):
        sub = [r for r in rows if r["mode"] == mode]
        for col in ("p50_ms", "p99_ms", "rps"):
            xs = [num(r[col]) for r in sub]
            xs = [x for x in xs if x is not None]
            if not xs:
                continue
            print(f"{mode:4} {col:8} min={min(xs):9.2f} max={max(xs):9.2f} "
                  f"mean={stats.mean(xs):9.2f} CV={cv(xs):5.1f}%")
    for rt in ("jvm", "native"):
        xs = {num(r["startup_s"]) for r in rows if r["runtime"] == rt}
        xs = {x for x in xs if x is not None}
        if xs:
            print(f"startup {rt:7} mean={stats.mean(xs):.3f}s range={min(xs):.3f}-{max(xs):.3f}")
    jvm = [num(r["startup_s"]) for r in rows if r["runtime"] == "jvm"]
    native = [num(r["startup_s"]) for r in rows if r["runtime"] == "native"]
    jvm = [x for x in jvm if x is not None]
    native = [x for x in native if x is not None]
    if jvm and native:
        print(f"startup ratio jvm/native = {stats.mean(jvm) / stats.mean(native):.1f}x")


def paired(rows, label):
    print(f"\n=== paired governance on/off / {label} ===")
    lookup = {(r["engine"], r["governance"], r["runtime"], r["mode"]): r for r in rows}
    for engine in ("webflux", "vt"):
        for rt in ("jvm", "native"):
            for mode in ("json", "sse"):
                on = lookup.get((engine, "on", rt, mode))
                off = lookup.get((engine, "off", rt, mode))
                if not on or not off:
                    continue
                p50 = (num(on["p50_ms"]) / num(off["p50_ms"]) - 1) * 100
                rps = (num(on["rps"]) / num(off["rps"]) - 1) * 100
                print(f"{engine:8} {rt:7} {mode:5} p50 {p50:+7.1f}%  rps {rps:+7.1f}%")


def validity(rows, label):
    print(f"\n=== validity / {label} ===")
    bad = [r for r in rows if (num(r["error_rate"]) or 0) > 0.01]
    print(f"rows={len(rows)}  invalid(error_rate>1%)={len(bad)}")
    for r in bad:
        print(f"  INVALID {r['engine']}-{r['governance']}-{r['runtime']}-{r['mode']} "
              f"error_rate={r['error_rate']}")
    sources = {r.get("rss_source", "NA") for r in rows}
    ok = len(sources) == 1 and sources != {"NA"} and "NA" not in sources
    print(f"rss_source={sorted(sources)}  "
          + ("OK: single real source, comparable within this run" if ok
             else "WARNING: NA or mixed sources, do NOT compare memory"))
    missing = [r for r in rows if str(r.get("rss_kb", "NA")).upper() == "NA"]
    print(f"rss_kb=NA rows: {len(missing)}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("csv", nargs="+", help="one summary CSV, or two with --compare")
    ap.add_argument("--compare", action="store_true", help="print paired old->new deltas")
    args = ap.parse_args()

    datasets = [(Path(p).name, load(p)) for p in args.csv]
    for label, rows in datasets:
        if not rows:
            print(f"No usable rows in {label}", file=sys.stderr)
            return 1
        table(rows, label)
        dispersion(rows, label)
        paired(rows, label)
        validity(rows, label)

    if args.compare:
        if len(datasets) != 2:
            print("--compare needs exactly two CSVs", file=sys.stderr)
            return 1
        (old_label, old), (new_label, new) = datasets
        key = lambda r: (r["engine"], r["governance"], r["runtime"], r["mode"])
        a, b = {key(r): r for r in old}, {key(r): r for r in new}
        print(f"\n=== paired version delta: {old_label} -> {new_label} ===")
        for k in sorted(set(a) & set(b)):
            d = (num(b[k]["rps"]) / num(a[k]["rps"]) - 1) * 100
            print(f"{'/'.join(k):38} rps {num(a[k]['rps']):8.0f} -> {num(b[k]['rps']):8.0f} ({d:+6.1f}%)")
        print("NOTE: absolute deltas between separate runs are NOT causal evidence "
              "(different machine load / build artifacts).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
