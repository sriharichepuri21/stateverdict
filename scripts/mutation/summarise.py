#!/usr/bin/env python3
"""Combine the JUnit (PIT) and StateVerdict outcomes for the same mutants.

    scripts/mutation/summarise.py MUTANTS.json SHARDS_DIR OUT.json

The measurement is complete only if every mutant has exactly one StateVerdict result and every shard
first passed its baseline (the original services PASS the whole suite). Exits 1 otherwise.
"""
import json
import os
import sys
from collections import Counter, defaultdict
from pathlib import Path


def pct(n, d):
    return round(100 * n / d, 1) if d else None


def main():
    mutants = json.loads(Path(sys.argv[1]).read_text())
    shards = [json.loads(p.read_text()) for p in sorted(Path(sys.argv[2]).glob("**/results.json"))]
    out = Path(sys.argv[3])
    problems = []
    sv = {}
    expected_shards = shards[0]["shards"] if shards else 0
    if len(shards) != expected_shards or expected_shards == 0:
        problems.append(f"{len(shards)} shard results, expected {expected_shards or 'at least one'}")
    for shard in shards:
        if sorted(shard["baseline"]["verdicts"]) != ["PASS"]:
            problems.append(f"shard {shard['shard']}: baseline not all PASS {shard['baseline']['verdicts']}")
        for r in shard["mutants"]:
            if r["id"] in sv:
                problems.append(f"{r['id']} measured twice")
            sv[r["id"]] = r
    missing = [m["id"] for m in mutants if m["id"] not in sv]
    if missing:
        problems.append(f"{len(missing)} mutants without a StateVerdict result, e.g. {missing[:3]}")

    rows, per_class = [], defaultdict(Counter)
    for m in mutants:
        r = sv.get(m["id"])
        if r is None:
            continue
        j, s = m["junit"]["detected"], r["detected"]
        # Secondary measure (added after the first run, see docs/evaluation/mutation.md): any outcome
        # other than PASS on every schedule. The original services PASS every schedule in every shard,
        # so a non-PASS outcome is a difference the mutant caused, but not necessarily a rule violation.
        noticed = s or r["startup"] == "failed" or any(v for k, v in r["verdicts"].items() if k != "PASS")
        per_class[m["service"] + ":" + m["class"].rsplit(".", 1)[-1]]["noticed"] += noticed
        cell = "both" if j and s else "junitOnly" if j else "stateverdictOnly" if s else "neither"
        rows.append({**m, "stateverdict": {**r, "noticed": noticed}, "outcome": cell})
        c = per_class[m["service"] + ":" + m["class"].rsplit(".", 1)[-1]]
        c["mutants"] += 1
        c["junit"] += j
        c["stateverdict"] += s
        c[cell] += 1

    n = len(rows)
    count = Counter(r["outcome"] for r in rows)
    junit = count["both"] + count["junitOnly"]
    stateverdict = count["both"] + count["stateverdictOnly"]
    either = n - count["neither"]
    pit_status = Counter(r["junit"]["status"] for r in rows)
    noticed = sum(1 for r in rows if r["stateverdict"]["noticed"])
    noticed_overlap = Counter(
        "both" if r["junit"]["detected"] and r["stateverdict"]["noticed"] else
        "junitOnly" if r["junit"]["detected"] else
        "stateverdictOnly" if r["stateverdict"]["noticed"] else "neither" for r in rows)
    sv_extra = {
        "startupFailed": sum(1 for r in rows if r["stateverdict"]["startup"] == "failed"),
        "withInconclusive": sum(1 for r in rows if r["stateverdict"]["verdicts"].get("INCONCLUSIVE")),
        "withError": sum(1 for r in rows if r["stateverdict"]["verdicts"].get("ERROR")),
    }
    result = {
        "complete": not problems, "problems": problems, "mutants": n,
        "junit": {"detected": junit, "percent": pct(junit, n), "pitStatuses": dict(pit_status)},
        "stateverdict": {"detected": stateverdict, "percent": pct(stateverdict, n), **sv_extra,
                         "noticed": noticed, "noticedPercent": pct(noticed, n),
                         "noticedOverlap": dict(noticed_overlap)},
        "overlap": {"both": count["both"], "junitOnly": count["junitOnly"],
                    "stateverdictOnly": count["stateverdictOnly"], "neither": count["neither"],
                    "either": either, "eitherPercent": pct(either, n)},
        "perClass": {k: dict(v) for k, v in sorted(per_class.items())},
        "stateverdictOnly": [{k: r[k] for k in ("id", "description")} | {"detectedBy": r["stateverdict"]["detectedBy"]}
                             for r in rows if r["outcome"] == "stateverdictOnly"],
        "junitOnly": [{k: r[k] for k in ("id", "description")} | {"killingTest": r["junit"]["killingTest"]}
                      for r in rows if r["outcome"] == "junitOnly"],
        "baselineSuites": [s["baseline"] for s in shards],
    }
    out.write_text(json.dumps(result, indent=2) + "\n")
    Path(out.with_name("mutation-detail.json")).write_text(json.dumps(rows, indent=1) + "\n")

    head = (f"{n} mutants. JUnit suite (PIT) detected {junit} ({pct(junit, n)}%); StateVerdict {stateverdict} "
            f"({pct(stateverdict, n)}%). Both {count['both']}, JUnit only {count['junitOnly']}, StateVerdict only "
            f"{count['stateverdictOnly']}, neither {count['neither']}. Together {either} ({pct(either, n)}%).")
    print(head)
    print(f"::notice title=Mutation head-to-head::{head}")
    print(f"::notice title=Mutation details::PIT statuses {dict(pit_status)}; StateVerdict {sv_extra}; "
          f"StateVerdict noticed (any non-PASS) {noticed} ({pct(noticed, n)}%), overlap {dict(noticed_overlap)}")
    lines = [f"{r['id']} | PIT {r['junit']['status']} | SV {r['stateverdict']['startup']} "
             f"{json.dumps(r['stateverdict']['verdicts'], separators=(',', ':'))}"
             f"{' by ' + r['stateverdict']['detectedBy'] if r['stateverdict']['detectedBy'] else ''}" for r in rows]
    for start in range(0, len(lines), 20):
        print(f"::notice title=Per mutant {start + 1}-{min(start + 20, len(lines))}::" + "%0A".join(lines[start:start + 20]))
    for kind in ("stateverdictOnly", "junitOnly"):
        listing = "%0A".join(f"{x['id']}: {x['description']}" for x in result[kind][:40])
        if listing:
            print(f"::notice title=Detected by {kind} ({len(result[kind])})::{listing}")
    for p in problems:
        print(f"::error title=Mutation measurement incomplete::{p}")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a") as f:
            f.write("## Mutation testing: JUnit suite vs StateVerdict on the same mutants\n\n" + head + "\n\n")
            f.write("| Class | Mutants | JUnit | StateVerdict | Both | JUnit only | StateVerdict only | Neither |\n")
            f.write("|---|---|---|---|---|---|---|---|\n")
            for k, v in result["perClass"].items():
                f.write(f"| {k} | {v['mutants']} | {v['junit']} | {v['stateverdict']} | {v['both']} | "
                        f"{v['junitOnly']} | {v['stateverdictOnly']} | {v['neither']} |\n")
            for kind in ("stateverdictOnly", "junitOnly"):
                f.write(f"\n### Detected by {kind} only\n\n")
                for x in result[kind]:
                    f.write(f"- `{x['id']}`: {x['description']}\n")
    return 0 if not problems else 1


if __name__ == "__main__":
    sys.exit(main())
