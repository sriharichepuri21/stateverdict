#!/usr/bin/env python3
"""Join PIT's results with its exported mutants: one list of mutants, each with the JUnit outcome.

    scripts/mutation/list-mutants.py OUT.json SERVICE=REPORT_DIR [SERVICE=REPORT_DIR ...]

REPORT_DIR is a module's target/pit-reports (mutations.xml, and export/ from the +EXPORT feature).
Every mutant in mutations.xml must have exactly one exported class file and the other way round;
otherwise the two halves of the comparison would not be measuring the same mutants, so this exits 1.
"""
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

# PIT's MutationDetails.toString(), written to details.txt next to each exported mutant.
DETAILS = re.compile(
    r"clazz=(?P<cls>[^,\]]+), method=(?P<method>[^,\]]+), methodDesc=(?P<desc>[^\]]+)\], "
    r"indexes=\[(?P<indexes>[^\]]*)\], mutator=(?P<mutator>[^\]]+)\].*?lineNumber=(?P<line>\d+)", re.S)
# PIT's convention: these statuses mean the suite noticed the mutant.
DETECTED = {"KILLED", "TIMED_OUT", "MEMORY_ERROR"}


def key(cls, method, desc, indexes, mutator):
    return (cls, method, desc, tuple(int(i) for i in indexes), mutator)


def main():
    out, pairs = sys.argv[1], sys.argv[2:]
    mutants, problems = [], []
    for pair in pairs:
        service, report_dir = pair.split("=", 1)
        report_dir = Path(report_dir)
        exported = {}
        for details in sorted(report_dir.glob("export/**/details.txt")):
            m = DETAILS.search(details.read_text())
            classes = [p for p in details.parent.glob("*.class")]
            if not m or len(classes) != 1:
                problems.append(f"{service}: cannot read exported mutant {details.parent} "
                                f"({'no match' if not m else f'{len(classes)} class files'}): {details.read_text()[:300]!r}")
                continue
            indexes = [i.strip() for i in m["indexes"].split(",") if i.strip()]
            k = key(m["cls"].strip(), m["method"].strip(), m["desc"].strip(), indexes, m["mutator"].strip())
            if k in exported:
                problems.append(f"{service}: two exported mutants share {k}")
            exported[k] = classes[0]
        xml = report_dir / "mutations.xml"
        if not xml.exists():
            problems.append(f"{service}: {xml} is missing")
            continue
        seen = set()
        for mut in ET.parse(xml).getroot().iter("mutation"):
            cls, method = mut.findtext("mutatedClass"), mut.findtext("mutatedMethod")
            desc, mutator = mut.findtext("methodDescription"), mut.findtext("mutator")
            indexes = [i.text for i in mut.iter("index")]
            k = key(cls, method, desc, indexes, mutator)
            seen.add(k)
            status = mut.get("status")
            class_file = exported.get(k)
            if class_file is None:
                problems.append(f"{service}: no exported class for {k}")
            simple = cls.rsplit(".", 1)[-1]
            mutants.append({
                "id": f"{service}:{simple}.{method}:L{mut.findtext('lineNumber')}:{mutator.rsplit('.', 1)[-1]}:{'-'.join(indexes)}",
                "service": service,
                "class": cls,
                "method": method,
                "line": int(mut.findtext("lineNumber")),
                "mutator": mutator.rsplit(".", 1)[-1],
                "description": mut.findtext("description"),
                "classFile": str(class_file) if class_file else None,
                "junit": {"status": status, "detected": status in DETECTED,
                          "killingTest": mut.findtext("killingTest") or None},
            })
        for k in exported.keys() - seen:
            problems.append(f"{service}: exported mutant {k} has no result in mutations.xml")
    ids = [m["id"] for m in mutants]
    for dup in {i for i in ids if ids.count(i) > 1}:
        problems.append(f"duplicate mutant id {dup}")
    Path(out).write_text(json.dumps(mutants, indent=1) + "\n")
    by_status = {}
    for m in mutants:
        by_status[m["junit"]["status"]] = by_status.get(m["junit"]["status"], 0) + 1
    print(f"{len(mutants)} mutants; JUnit (PIT): {by_status}")
    for p in problems[:20]:
        print(f"::error title=Mutant list::{p}")
    return 1 if problems or not mutants else 0


if __name__ == "__main__":
    sys.exit(main())
