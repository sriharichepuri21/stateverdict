#!/usr/bin/env python3
"""Builds a compact, durable evidence record for one measurement run.

  scripts/record-evidence.py exploration|evaluation|benchmark|mutation STATUS INPUT_DIR OUTPUT.json [REASON]

STATUS is "complete" (every report was uploaded and downloaded) or "incomplete". An incomplete
record says which evidence is missing; its figures must not be published or cited. The record keeps
only the figures the README and results documents cite, plus the run's identity, so it stays small
enough to commit to the `evidence` branch, where it outlives GitHub's 90-day artifact retention.
"""
import json
import os
import sys
from pathlib import Path


def load(path):
    try:
        return json.loads(Path(path).read_text())
    except (OSError, ValueError):
        return None


def exploration_summary(report):
    if not report:
        return None
    rules = sorted({rule for f in report.get("findings", []) for rule in f.get("violatedRules", [])})
    return {"seed": report.get("seed"), "schedules": report.get("iterations"),
            "verdicts": report.get("verdictCounts", {}), "findings": len(report.get("findings", [])),
            "rulesViolated": rules}


def interventions(schedule):
    """One per fault and per customer cancel (commerce), or per fault and per rejecting side (saga)."""
    if not isinstance(schedule, dict):
        return None
    if "steps" in schedule:
        return sum(1 for step in schedule["steps"] if step.get("type") in ("ARM", "CANCEL"))
    order = schedule.get("order") or {}
    short = int(order.get("price", 0) >= order.get("funds", 1)) + int(order.get("count", 0) >= order.get("stock", 1))
    return len(schedule.get("arms") or []) + short


def shrink_summary(report):
    before, after = interventions(report.get("original")), interventions(report.get("minimized"))
    return {"status": report.get("status"), "targetRules": report.get("targetRules"),
            "originalInterventions": before, "minimizedInterventions": after,
            "reductionPercent": round(100 * (before - after) / before) if before and after is not None else None,
            "candidatesTried": report.get("candidatesTried"), "runs": report.get("runs"),
            "durationMs": report.get("durationMs"),
            "originalFailures": report.get("originalFailures"),
            "minimizedFailures": report.get("minimizedFailures"),
            "confirmTrials": report.get("confirmTrials"), "minimized": report.get("minimized")}


def controls(directory, pattern):
    """Valid-scenario control campaigns: every run should PASS; anything else is reported separately."""
    out = {}
    for path in sorted(Path(directory).glob(pattern)):
        report = load(path)
        if report and "verdictCounts" in report:
            counts = report["verdictCounts"]
            out[path.stem] = {"seed": report.get("seed"), "schedules": report.get("iterations"),
                              "pass": counts.get("PASS", 0), "falseAlarmsOrFindings": counts.get("FAIL", 0),
                              "inconclusive": counts.get("INCONCLUSIVE", 0), "error": counts.get("ERROR", 0)}
    total = {k: sum(c[k] for c in out.values()) for k in ("schedules", "pass", "falseAlarmsOrFindings", "inconclusive", "error")}
    return {"campaigns": out, "total": total}


def shrinks(directory):
    out = {}
    for path in sorted(Path(directory).glob("*.json")):
        report = load(path)
        if report and "status" in report and not path.name.endswith(".min.json"):
            out[path.stem] = shrink_summary(report)
    return out


def main():
    kind, status, root, output = sys.argv[1:5]
    reason = sys.argv[5] if len(sys.argv) > 5 else ""
    root = Path(root)
    record = {
        "workflow": kind,
        "status": status,
        "citable": status == "complete",
        "reason": reason,
        "run": {
            "id": os.environ.get("GITHUB_RUN_ID"),
            "url": f"{os.environ.get('GITHUB_SERVER_URL', 'https://github.com')}/"
                   f"{os.environ.get('GITHUB_REPOSITORY', '')}/actions/runs/{os.environ.get('GITHUB_RUN_ID', '')}",
            "commit": os.environ.get("GITHUB_SHA"),
            "event": os.environ.get("GITHUB_EVENT_NAME"),
        },
    }
    if status == "complete":
        if kind == "exploration":
            record["commerce"] = {
                "naive": exploration_summary(load(root / "commerce" / "explore-naive.json")),
                "fixed": exploration_summary(load(root / "commerce" / "explore-fixed.json")),
                "shrinks": shrinks(root / "commerce" / "shrunk"),
                "controls": controls(root / "commerce", "explore-fixed-s*.json"),
            }
            record["external"] = {
                "explore": exploration_summary(load(root / "external" / "piomin-saga" / "explore.json")),
                "shrinks": shrinks(root / "external" / "piomin-saga" / "shrunk"),
                "controls": controls(root / "external" / "piomin-saga", "controls-s*.json"),
            }
            record["jobResults"] = {"commerce": os.environ.get("COMMERCE_RESULT"),
                                    "external": os.environ.get("EXTERNAL_RESULT")}
            missing = [name for name, value in [("commerce naive exploration", record["commerce"]["naive"]),
                                                ("commerce fixed exploration", record["commerce"]["fixed"]),
                                                ("external exploration", record["external"]["explore"])] if value is None]
        elif kind == "mutation":
            summary = load(root / "mutation.json")
            if summary is not None:
                record["mutation"] = {k: summary.get(k) for k in
                                      ("complete", "problems", "mutants", "junit", "stateverdict", "overlap",
                                       "perClass", "stateverdictOnly", "junitOnly")}
            missing = [] if summary is not None else ["the mutation summary"]
            if summary is not None and not summary.get("complete"):
                missing.append("a complete measurement (" + "; ".join(summary.get("problems", [])) + ")")
        elif kind == "benchmark":
            jmh = load(root / "jmh" / "jmh.json")
            overhead = load(root / "overhead" / "overhead.json")
            record["dispatcher"] = None if jmh is None else [{
                "benchmark": b["benchmark"].rsplit(".", 1)[-1],
                "score": round(b["primaryMetric"]["score"], 2),
                "error99_9": round(b["primaryMetric"]["scoreError"], 2),
                "unit": b["primaryMetric"]["scoreUnit"],
                "forks": b.get("forks"), "measurementIterations": b.get("measurementIterations"),
            } for b in jmh]
            record["externalApp"] = overhead
            missing = [name for name, value in [("dispatcher microbenchmark", jmh),
                                                ("external app overhead", overhead)] if value is None]
            if overhead is not None and not overhead.get("complete"):
                missing.append("a complete external app measurement (" + "; ".join(overhead.get("problems", [])) + ")")
        else:
            results = load(root / "results.json") or []
            record["configurations"] = [{
                "config": r.get("config"), "kind": r.get("kind"),
                "conventional": {"detected": r["junit"].get("detected"), "failed": r["junit"].get("failed"),
                                 "testsRun": r["junit"].get("testsRun")},
                "stateverdict": {"detected": r["stateverdict"].get("detected"),
                                 "rulesViolated": r["stateverdict"].get("rulesViolated"),
                                 "inconclusive": r["stateverdict"].get("inconclusive")},
            } for r in results]
            missing = [] if len(results) == 10 else [f"{10 - len(results)} of 10 configuration results"]
        if missing:
            record.update(status="incomplete", citable=False, reason="missing: " + ", ".join(missing))
    Path(output).write_text(json.dumps(record, indent=2) + "\n")
    print(f"{kind} run {record['run']['id']}: {record['status']}" + (f" ({record['reason']})" if record["reason"] else ""))
    return 0 if record["citable"] else 1


if __name__ == "__main__":
    sys.exit(main())
