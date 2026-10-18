#!/usr/bin/env python3
"""Builds a compact, durable evidence record for one measurement run.

  scripts/record-evidence.py exploration|evaluation STATUS INPUT_DIR OUTPUT.json [REASON]

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


def shrink_summary(report):
    return {"status": report.get("status"), "targetRules": report.get("targetRules"),
            "originalFailures": report.get("originalFailures"),
            "minimizedFailures": report.get("minimizedFailures"),
            "confirmTrials": report.get("confirmTrials"), "minimized": report.get("minimized")}


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
            }
            record["external"] = {
                "explore": exploration_summary(load(root / "external" / "piomin-saga" / "explore.json")),
                "shrinks": shrinks(root / "external" / "piomin-saga" / "shrunk"),
            }
            missing = [name for name, value in [("commerce naive exploration", record["commerce"]["naive"]),
                                                ("commerce fixed exploration", record["commerce"]["fixed"]),
                                                ("external exploration", record["external"]["explore"])] if value is None]
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
