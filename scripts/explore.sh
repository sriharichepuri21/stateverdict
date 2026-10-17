#!/usr/bin/env bash
# Run an exploration campaign and check its outcome.
#
#   scripts/explore.sh LABEL ITERATIONS SEED EXPECTED [RULE...]
#     EXPECTED  FAIL: the campaign must find violations of every RULE listed
#               PASS: the campaign must find no violation (and no inconclusive or error run)
#
# Needs the Compose stack running and the CLI jar built.
set -euo pipefail

LABEL=$1 ITERATIONS=$2 SEED=$3 EXPECTED=$4
shift 4
REQUIRED_RULES=("$@")
CLI="$(ls apps/stateverdict-core/target/stateverdict-core-*.jar | grep -v original | head -1)"
OUT="${OUT_DIR:-artifacts}"
mkdir -p "${OUT}"

set +e
java -jar "${CLI}" explore --iterations "${ITERATIONS}" --seed "${SEED}" \
  --out "${OUT}/found-${LABEL}" --report "${OUT}/explore-${LABEL}.json" | tee "${OUT}/explore-${LABEL}.txt"
code=${PIPESTATUS[0]}
set -e

report="${OUT}/explore-${LABEL}.json"
verdicts=$(jq -r '[.verdictCounts | to_entries[] | "\(.key) \(.value)"] | join(", ")' "${report}")
found=$(jq -r '[.findings[].violatedRules[]] | unique | join(",")' "${report}")
findings=$(jq -r '.findings | length' "${report}")
echo "::notice title=Exploration (${LABEL})::seed ${SEED}, ${ITERATIONS} schedules: ${verdicts}; ${findings} findings; rules violated: ${found:-none}"
# Group findings by the rules they violated and the kind of schedule, so the reader can see
# whether the explorer only rediscovered the hand-written race or found other routes too.
kinds=$(jq -r '
  def cancel_mode: (.schedule.steps | to_entries | map(select(.value.type == "CANCEL")) | first) as $c
    | if $c == null then "no cancel"
      elif ($c.key > 0 and .schedule.steps[$c.key - 1].type == "AWAIT_HELD") then "cancel while \(.schedule.steps[$c.key - 1].topic) held"
      else "cancel right after order" end;
  def faults: [.schedule.steps[] | select(.type == "ARM") | "\(.topic):\(.fault.type)"] | if length == 0 then "no faults" else join("+") end;
  [.findings[] | "\(.violatedRules | join(","))  \(faults)  \(cancel_mode)"]
  | group_by(.) | map("\(length) x \(.[0])") | join("%0A")' "${report}")
if [ -n "${kinds}" ]; then
  echo "::notice title=Exploration findings by kind (${LABEL})::${kinds}"
fi
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  {
    echo "### Exploration ${LABEL}: seed ${SEED}, ${ITERATIONS} schedules, rules violated: ${found:-none}"
    echo '```'
    sed -n '/^Exploration:/,$p' "${OUT}/explore-${LABEL}.txt"
    echo '```'
  } >> "${GITHUB_STEP_SUMMARY}"
fi

case "${EXPECTED}" in
  FAIL)
    [ "${code}" -eq 1 ] || { echo "::error::expected findings (exit 1), got exit ${code}"; exit 1; }
    for rule in "${REQUIRED_RULES[@]}"; do
      echo ",${found}," | grep -q ",${rule}," || { echo "::error::exploration did not find a violation of ${rule}"; exit 1; }
    done ;;
  PASS)
    [ "${code}" -eq 0 ] || { echo "::error::expected no findings and no inconclusive runs (exit 0), got exit ${code}"; exit 1; } ;;
  *) echo "EXPECTED must be FAIL or PASS"; exit 2 ;;
esac
