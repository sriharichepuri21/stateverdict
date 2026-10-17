#!/usr/bin/env bash
# Run one schedule N times from clean state and require the same expected verdict on every trial.
#
#   scripts/measure-schedule.sh SCHEDULE.json TRIALS EXPECTED LABEL
#     EXPECTED  PASS or FAIL
#     LABEL     name for the report files and CI annotations, e.g. cancel-race-naive
#
# Needs the Compose stack running and the CLI jar built (mvn -pl apps/stateverdict-core -am package).
set -euo pipefail

SCHEDULE=$1 TRIALS=$2 EXPECTED=$3 LABEL=$4
CLI="$(ls apps/stateverdict-core/target/stateverdict-core-*.jar | grep -v original | head -1)"
OUT="${OUT_DIR:-artifacts}"
mkdir -p "${OUT}"

set +e
java -jar "${CLI}" run-schedule "${SCHEDULE}" --trials "${TRIALS}" --report "${OUT}/${LABEL}-trials.json" \
  | tee "${OUT}/${LABEL}-trials.txt"
code=${PIPESTATUS[0]}
set -e

counts=$(jq -r --arg t "${TRIALS}" '[.verdictCounts | to_entries[] | "\(.key) \(.value)/\($t)"] | join(", ")' \
  "${OUT}/${LABEL}-trials.json")
median=$(jq -r '.medianDurationMs' "${OUT}/${LABEL}-trials.json")
echo "::notice title=Schedule (${LABEL})::${counts}; median ${median} ms per trial"
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  {
    echo "### ${LABEL}: ${counts} (median ${median} ms per trial)"
    echo '```'
    sed -n '/repeated trials/,$p' "${OUT}/${LABEL}-trials.txt"
    echo '```'
  } >> "${GITHUB_STEP_SUMMARY}"
fi

case "${EXPECTED}" in PASS) expected_code=0 ;; FAIL) expected_code=1 ;; *) echo "EXPECTED must be PASS or FAIL"; exit 2 ;; esac
if [ "${code}" -ne "${expected_code}" ]; then
  echo "::error title=Schedule (${LABEL})::expected ${EXPECTED} on all ${TRIALS} trials; got ${counts} (exit ${code})"
  exit 1
fi
