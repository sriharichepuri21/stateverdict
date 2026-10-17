#!/usr/bin/env bash
# Shrink a few of the explorer's findings and measure how reliably each reproduces (gate 3).
#
#   scripts/shrink-findings.sh EXPLORE_REPORT.json
#
# Picks, from an exploration report:
#   sv-002     the SV-002 finding with the most interventions          (must minimize and fail 10/10)
#   sv-003     the SV-003-only finding with the most interventions     (must minimize and fail 10/10)
#   no-fault   an SV-003 finding with no injected fault, if there is one (measured only: timing-dependent)
# Needs the Compose stack running with the naive handlers and the CLI jar built.
set -euo pipefail

REPORT=$1
CLI="$(ls apps/stateverdict-core/target/stateverdict-core-*.jar | grep -v original | head -1)"
OUT="${OUT_DIR:-artifacts}/shrunk"
mkdir -p "${OUT}"

pick() {  # pick JQ_FILTER -> saved schedule path of the finding with the most interventions
  jq -r "[.findings[] | select($1)
          | {path: .savedAs, size: ([.schedule.steps[] | select(.type == \"ARM\" or .type == \"CANCEL\")] | length)}]
         | sort_by(-.size) | first | .path // empty" "${REPORT}"
}

status=0
shrink_one() {  # shrink_one LABEL SCHEDULE RULE MUST_CONFIRM
  local label=$1 schedule=$2 rule=$3 must=$4
  if [ -z "${schedule}" ]; then
    echo "::notice title=Shrink (${label})::no such finding in this exploration"; return
  fi
  echo "=== ${label}: ${schedule} (target ${rule}) ==="
  set +e
  java -jar "${CLI}" shrink "${schedule}" --rules "${rule}" --trials 3 --confirm 10 \
    --out "${OUT}/${label}.min.json" --report "${OUT}/${label}.json" | tee "${OUT}/${label}.txt"
  set -e
  local r="${OUT}/${label}.json"
  local line
  line=$(jq -r '
    def size(s): [s.steps[] | select(.type == "ARM" or .type == "CANCEL")] | length;
    "\(.status): \(size(.original)) interventions, fails \(.originalFailures)/\(.confirmTrials)  ->  \(size(.minimized)) interventions, fails \(.minimizedFailures)/\(.confirmTrials); \(.candidatesTried) candidates, \(.runs) runs, \(.durationMs / 1000 | floor) s"' "${r}")
  local steps
  steps=$(jq -r '[.minimized.steps[] | if .type == "ARM" then "ARM \(.fault.type) \(.topic)"
                   elif .type == "AWAIT_HELD" then "AWAIT_HELD \(.topic)"
                   elif .type == "RELEASE_ALL" then "RELEASE_ALL \(.service)" else .type end] | join(" > ")' "${r}")
  echo "::notice title=Shrink (${label})::${line}%0Aminimal: ${steps}"
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    { echo "### Shrink ${label}: ${line}"; echo '```'; sed -n '/^Shrink result/,$p' "${OUT}/${label}.txt"; echo '```'; } >> "${GITHUB_STEP_SUMMARY}"
  fi
  if [ "${must}" = "yes" ]; then
    jq -e '.status == "MINIMIZED" and .minimizedFailures == .confirmTrials' "${r}" > /dev/null \
      || { echo "::error title=Shrink (${label})::expected a minimized schedule that fails every confirmation run"; status=1; }
  fi
}

shrink_one sv-002   "$(pick '(.violatedRules | index("SV-002"))')" SV-002 yes
shrink_one sv-003   "$(pick '(.violatedRules == ["SV-003"])')" SV-003 yes
shrink_one no-fault "$(pick '(.violatedRules == ["SV-003"]) and ([.schedule.steps[] | select(.type == "ARM")] | length == 0)')" SV-003 no
exit "${status}"
