#!/usr/bin/env bash
# Generated exploration against the patched target, then shrink one finding per kind.
#   targets/piomin-saga/explore-and-shrink.sh [ITERATIONS] [SEED]   (default 60 42)
# Findings are results, not build failures. The job fails only if StateVerdict was not trustworthy:
# a run ended in ERROR, or a required shrink found nothing to work on.
set -uo pipefail
ITERATIONS=${1:-60} SEED=${2:-42}
CLI="$(ls apps/stateverdict-core/target/stateverdict-core-*.jar | grep -v original | head -1)"
OUT="${OUT_DIR:-artifacts}/piomin-saga"
mkdir -p "${OUT}/shrunk"
status=0

java -jar "${CLI}" explore-saga --iterations "${ITERATIONS}" --seed "${SEED}" \
  --out "${OUT}/found" --report "${OUT}/explore.json" > "${OUT}/explore.txt" 2>&1
report="${OUT}/explore.json"
[ -s "${report}" ] || { echo "::error title=Explore (piomin-saga)::no report"; tail -50 "${OUT}/explore.txt"; exit 1; }

verdicts=$(jq -r '[.verdictCounts | to_entries[] | "\(.key) \(.value)"] | join(", ")' "${report}")
rules=$(jq -r '[.findings[].violatedRules[]] | unique | join(",")' "${report}")
echo "::notice title=Explore (piomin-saga)::seed ${SEED}, ${ITERATIONS} generated schedules: ${verdicts}; $(jq '.findings | length' "${report}") findings; rules violated: ${rules:-none}"
kinds=$(jq -r '
  def order: .schedule.order | if .price >= .funds and .count >= .stock then "unaffordable+short"
    elif .price >= .funds then "unaffordable" elif .count >= .stock then "short of stock" else "ok order" end;
  def faults: [.schedule.arms[] | "\(.service | ascii_downcase) \(.phase | sub("orders\\."; "")):\(.fault.type)\(if .fault.type == "DELAY" then (if .fault.delayMs > 10000 then ">10s" else "<=2s" end) else "" end)"]
    | if length == 0 then "no faults" else join("+") end;
  [.findings[] | "\(.violatedRules | join(","))  \(order)  \(faults)"] | group_by(.) | map("\(length) x \(.[0])") | join("%0A")' "${report}")
[ -n "${kinds}" ] && echo "::notice title=Explore findings by kind (piomin-saga)::${kinds}"
if jq -e '(.verdictCounts.ERROR // 0) > 0' "${report}" > /dev/null; then
  echo "::error title=Explore (piomin-saga)::some runs ended in ERROR: the harness is not trustworthy here"; status=1
fi

pick() {  # the finding matching the jq filter with the most interventions
  jq -r "[.findings[] | select($1)
          | {path: .savedAs, size: ((.schedule.arms | length) + (if .schedule.order.price >= .schedule.order.funds then 1 else 0 end)
                                    + (if .schedule.order.count >= .schedule.order.stock then 1 else 0 end))}]
         | sort_by(-.size) | first | .path // empty" "${report}"
}

shrink_one() {  # LABEL SCHEDULE RULE
  local label=$1 schedule=$2 rule=$3
  if [ -z "${schedule}" ]; then
    echo "::notice title=Shrink (piomin-saga ${label})::no such finding in this exploration"; return
  fi
  if grep -qxF "${schedule}" "${OUT}/shrunk/.done" 2>/dev/null; then
    echo "::notice title=Shrink (piomin-saga ${label})::same finding as an earlier shrink, skipped"; return
  fi
  echo "${schedule}" >> "${OUT}/shrunk/.done"
  java -jar "${CLI}" shrink-saga "${schedule}" --rules "${rule}" --trials 3 --confirm 10 \
    --out "${OUT}/shrunk/${label}.min.json" --report "${OUT}/shrunk/${label}.json" > "${OUT}/shrunk/${label}.txt" 2>&1
  local r="${OUT}/shrunk/${label}.json"
  [ -s "${r}" ] || { echo "::error title=Shrink (piomin-saga ${label})::no report"; tail -40 "${OUT}/shrunk/${label}.txt"; status=1; return; }
  local line
  line=$(jq -r '
    def size(s): (s.arms | length) + (if s.order.price >= s.order.funds then 1 else 0 end) + (if s.order.count >= s.order.stock then 1 else 0 end);
    def show(s): ([s.arms[] | "\(.service | ascii_downcase) \(.phase):\(.fault.type)\(if .fault.copies then " x\(.fault.copies)" else "" end)\(if .fault.delayMs then " \(.fault.delayMs) ms" else "" end)"]
      + [if s.order.price >= s.order.funds then "order unaffordable" else empty end]
      + [if s.order.count >= s.order.stock then "order short of stock" else empty end]) | if length == 0 then "plain order" else join(" + ") end;
    "\(.status): \(size(.original)) interventions, fails \(.originalFailures)/\(.confirmTrials)  ->  \(size(.minimized)) interventions, fails \(.minimizedFailures)/\(.confirmTrials); \(.candidatesTried) candidates, \(.runs) runs, \(.durationMs / 1000 | floor) s%0Aminimal: \(show(.minimized))%0Aremoved: \(.accepted | join("; "))"' "${r}")
  # (no commas in titles: GitHub reads a comma as the start of another annotation property)
  echo "::notice title=Shrink (piomin-saga ${label} for ${rule})::${line}"
}

# Four different kinds of finding, so each shrink answers a different question. A finding already
# shrunk is not shrunk again under another label.
UNAFFORDABLE='.schedule.order.price >= .schedule.order.funds'
SHORT='.schedule.order.count >= .schedule.order.stock'
shrink_one saga-1-undecided "$(pick '(.violatedRules | index("SAGA-1"))')" SAGA-1
shrink_one saga-2-rejected  "$(pick "(.violatedRules | index(\"SAGA-2\")) and (${UNAFFORDABLE}) and ((${SHORT}) | not)")" SAGA-2
shrink_one saga-3-rejected  "$(pick "(.violatedRules | index(\"SAGA-3\")) and (${SHORT}) and ((${UNAFFORDABLE}) | not)")" SAGA-3
# A money violation with an affordable, in-stock order: caused by delivery faults alone.
shrink_one saga-2-faults    "$(pick "(.violatedRules | index(\"SAGA-2\")) and ((${UNAFFORDABLE}) | not) and ((${SHORT}) | not)")" SAGA-2
exit "${status}"
