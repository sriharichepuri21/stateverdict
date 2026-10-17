#!/usr/bin/env bash
# Run every schedule in scenarios/ N times against the patched target and report what happened.
#   targets/piomin-saga/measure.sh [TRIALS]   (default 10)
# Findings are reported, not treated as build failures. The job fails only if StateVerdict itself is
# not trustworthy here: the baseline does not PASS every time, or any run ends in ERROR.
set -uo pipefail
TRIALS=${1:-10}
HERE="$(cd "$(dirname "$0")" && pwd)"
CLI="$(ls apps/stateverdict-core/target/stateverdict-core-*.jar | grep -v original | head -1)"
OUT="${OUT_DIR:-artifacts}/piomin-saga"
mkdir -p "${OUT}"
status=0
for schedule in "${HERE}"/scenarios/*.json; do
  name=$(basename "${schedule}" .json)
  java -jar "${CLI}" run-saga "${schedule}" --trials "${TRIALS}" --report "${OUT}/${name}.json" > "${OUT}/${name}.txt" 2>&1
  line=$(jq -r --arg t "${TRIALS}" '
    ([.verdictCounts | to_entries[] | "\(.key) \(.value)/\($t)"] | join(", ")) as $counts
    | ([.reports[].ruleResults[] | select(.status == "VIOLATED") | .ruleId] | group_by(.) | map("\(.[0]) x\(length)") | join(", ")) as $rules
    | ([.reports[].ruleResults[] | select(.status == "VIOLATED") | "\(.ruleId): \(.observed). \(.rationale)"] | first // "") as $example
    | "\($counts); median \(.medianDurationMs) ms\(if $rules != "" then "; violated: \($rules)%0Aexample: \($example)" else "" end)"' \
    "${OUT}/${name}.json" 2>/dev/null || echo "no report (see ${name}.txt)")
  echo "${name}: ${line}"
  echo "::notice title=Target piomin-saga (${name})::${line}"
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    { echo "### ${name}: ${line%%%0A*}"; echo '```'; sed -n '/repeated trials/,$p' "${OUT}/${name}.txt"; echo '```'; } >> "${GITHUB_STEP_SUMMARY}"
  fi
  if jq -e '(.verdictCounts.ERROR // 0) > 0' "${OUT}/${name}.json" > /dev/null 2>&1 || [ ! -s "${OUT}/${name}.json" ]; then
    echo "::error title=Target piomin-saga (${name})::runs ended in ERROR or no report: the harness is not trustworthy here"
    sed -n '1,60p' "${OUT}/${name}.txt"
    status=1
  fi
done
jq -e --arg t "${TRIALS}" '.verdictCounts.PASS == ($t | tonumber)' "${OUT}/baseline.json" > /dev/null 2>&1 \
  || { echo "::error title=Target piomin-saga (baseline)::the baseline must PASS on every trial"; status=1; }
exit "${status}"
