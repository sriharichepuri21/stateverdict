#!/usr/bin/env python3
"""Analyze targets/piomin-saga/overhead.sh runs: the adapter's latency cost with no fault injected.

    scripts/analyze-overhead.py <runs dir> <output json>

For every measured order: latency = (later of the payment and stock reservation replies) minus (the
order event), from the Kafka record timestamps. Reports median, 95th percentile and mean per
configuration, and the difference from the unpatched application with a 95% bootstrap confidence
interval. Exits 1 if the measurement is incomplete (an order without both replies or a decision, or
no proof that the controlled runs went through the controlled dispatcher), never because of the
size of the overhead.
"""
import json
import os
import random
import statistics
import sys
from pathlib import Path

CONFIGS = ["upstream", "direct", "controlled"]
BOOTSTRAP = 2000


def read_topic(path):
    """[(timestamp ms, key, value dict)] for one dumped topic."""
    records = []
    for line in Path(path).read_text().splitlines():
        parts = line.split("\t", 2)
        if len(parts) != 3 or not parts[0].isdigit():
            continue
        records.append((int(parts[0]), int(parts[1]), json.loads(parts[2])))
    return records


def first_by_key(records):
    out = {}
    for ts, key, value in records:
        out.setdefault(key, ts)
    return out


def percentile(values, p):
    ordered = sorted(values)
    k = (len(ordered) - 1) * p
    lo, hi = int(k), min(int(k) + 1, len(ordered) - 1)
    return ordered[lo] + (ordered[hi] - ordered[lo]) * (k - lo)


def bootstrap_ci(a, b, stat, rng):
    """95% interval of stat(a) - stat(b), resampling each group independently."""
    diffs = sorted(stat(rng.choices(a, k=len(a))) - stat(rng.choices(b, k=len(b))) for _ in range(BOOTSTRAP))
    return [round(diffs[int(0.025 * BOOTSTRAP)], 2), round(diffs[int(0.975 * BOOTSTRAP) - 1], 2)]


def analyze_run(run_dir, problems):
    meta = json.loads((run_dir / "run.json").read_text())
    orders = read_topic(run_dir / "orders.tsv")
    measured = {key: ts for ts, key, value in orders if value.get("status") == "NEW" and ts >= meta["measuredFromMs"]}
    decided = {key for ts, key, value in orders if value.get("status") != "NEW"}
    payment = first_by_key(read_topic(run_dir / "payment-orders.tsv"))
    stock = first_by_key(read_topic(run_dir / "stock-orders.tsv"))
    name = run_dir.name
    if len(measured) != meta["orders"]:
        problems.append(f"{name}: {len(measured)} measured order events, expected {meta['orders']}")
    both, pay, stk = [], [], []
    missing = 0
    for key, t0 in measured.items():
        if key not in payment or key not in stock or key not in decided:
            missing += 1
            continue
        pay.append(payment[key] - t0)
        stk.append(stock[key] - t0)
        both.append(max(payment[key], stock[key]) - t0)
    if missing:
        problems.append(f"{name}: {missing} order(s) without both replies or a decision")
    if meta["config"] == "controlled":
        journal_file = run_dir / "journal-sample.json"
        journal = json.loads(journal_file.read_text()) if journal_file.exists() else []
        if not any(entry.get("action") == "DELIVERED" for entry in journal):
            problems.append(f"{name}: no DELIVERED journal entry, so the controlled dispatcher was not proven in the path")
    return meta, both, pay, stk


def describe(values):
    return {
        "n": len(values),
        "medianMs": round(statistics.median(values), 2),
        "p95Ms": round(percentile(values, 0.95), 2),
        "meanMs": round(statistics.fmean(values), 2),
    }


def main():
    runs_dir, out_file = Path(sys.argv[1]), Path(sys.argv[2])
    rng = random.Random(42)
    problems = []
    samples = {c: {"both": [], "payment": [], "stock": []} for c in CONFIGS}
    rounds = {c: [] for c in CONFIGS}
    for run_dir in sorted(p for p in runs_dir.iterdir() if (p / "run.json").exists()):
        meta, both, pay, stk = analyze_run(run_dir, problems)
        c = meta["config"]
        samples[c]["both"] += both
        samples[c]["payment"] += pay
        samples[c]["stock"] += stk
        if both:
            rounds[c].append({"round": meta["round"], "n": len(both), "medianMs": round(statistics.median(both), 2)})
    for c in CONFIGS:
        if not samples[c]["both"]:
            problems.append(f"{c}: no measured orders")
    result = {"metric": "order event to the later of the payment and stock reservation replies (Kafka record timestamps)",
              "complete": not problems, "problems": problems, "configurations": {}}
    if all(samples[c]["both"] for c in CONFIGS):
        base = samples["upstream"]["both"]
        for c in CONFIGS:
            entry = {"bothReplies": describe(samples[c]["both"]),
                     "paymentReply": describe(samples[c]["payment"]),
                     "stockReply": describe(samples[c]["stock"]),
                     "rounds": sorted(rounds[c], key=lambda r: r["round"])}
            if c != "upstream":
                vals = samples[c]["both"]
                entry["vsUpstream"] = {
                    "medianDiffMs": round(statistics.median(vals) - statistics.median(base), 2),
                    "medianDiffCi95Ms": bootstrap_ci(vals, base, statistics.median, rng),
                    "meanDiffMs": round(statistics.fmean(vals) - statistics.fmean(base), 2),
                    "meanDiffCi95Ms": bootstrap_ci(vals, base, statistics.fmean, rng),
                }
            result["configurations"][c] = entry
    out_file.write_text(json.dumps(result, indent=2) + "\n")

    lines = ["| Configuration | Orders | Median | p95 | Mean | Median vs upstream (95% CI) | Mean vs upstream (95% CI) |",
             "|---|---|---|---|---|---|---|"]
    for c, entry in result["configurations"].items():
        d = entry["bothReplies"]
        vs = entry.get("vsUpstream")
        med = f"{vs['medianDiffMs']:+} ms [{vs['medianDiffCi95Ms'][0]:+}, {vs['medianDiffCi95Ms'][1]:+}]" if vs else "baseline"
        mean = f"{vs['meanDiffMs']:+} ms [{vs['meanDiffCi95Ms'][0]:+}, {vs['meanDiffCi95Ms'][1]:+}]" if vs else "baseline"
        lines.append(f"| {c} | {d['n']} | {d['medianMs']} ms | {d['p95Ms']} ms | {d['meanMs']} ms | {med} | {mean} |")
    table = "\n".join(lines)
    print(table)
    for c, entry in result["configurations"].items():
        d, vs = entry["bothReplies"], entry.get("vsUpstream")
        detail = (f"; median vs upstream {vs['medianDiffMs']:+} ms (95% CI {vs['medianDiffCi95Ms']}),"
                  f" mean vs upstream {vs['meanDiffMs']:+} ms (95% CI {vs['meanDiffCi95Ms']})") if vs else ""
        rounds_text = " ".join(str(r["medianMs"]) for r in entry["rounds"])
        print(f"::notice title=Overhead {c}::{d['n']} orders; median {d['medianMs']} ms, p95 {d['p95Ms']} ms,"
              f" mean {d['meanMs']} ms; round medians {rounds_text}{detail}")
    for p in problems:
        print(f"::error title=Overhead measurement incomplete::{p}")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a") as f:
            f.write("### Adapter overhead on the external app, no fault injected\n\n")
            f.write(f"Latency: {result['metric']}.\n\n{table}\n\n")
            if problems:
                f.write("**Incomplete:** " + "; ".join(problems) + "\n")
    return 0 if not problems else 1


if __name__ == "__main__":
    sys.exit(main())
