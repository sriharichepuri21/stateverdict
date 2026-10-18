#!/usr/bin/env bash
# Valid-scenario control campaigns against the patched target: generated schedules the target is
# designed to handle (short delays; orders accepted, or rejected by both services). Every run should
# PASS. A FAIL is a false alarm, or a defect outside the three already known, and is investigated.
#   targets/piomin-saga/controls.sh ITERATIONS SEED...
set -uo pipefail
ITERATIONS=$1; shift
CLI="$(ls apps/stateverdict-core/target/stateverdict-core-*.jar | grep -v original | head -1)"
OUT="${OUT_DIR:-artifacts}/piomin-saga"
mkdir -p "${OUT}"
status=0
for seed in "$@"; do
  java -jar "${CLI}" explore-saga --controls true --iterations "${ITERATIONS}" --seed "${seed}" \
    --out "${OUT}/controls-found-s${seed}" --report "${OUT}/controls-s${seed}.json" > "${OUT}/controls-s${seed}.txt" 2>&1
  r="${OUT}/controls-s${seed}.json"
  if [ ! -s "${r}" ]; then echo "::error title=Controls (piomin-saga seed ${seed})::no report"; tail -30 "${OUT}/controls-s${seed}.txt"; status=1; continue; fi
  line=$(jq -r '[.verdictCounts | to_entries[] | "\(.key) \(.value)"] | join(" ")' "${r}")
  echo "::notice title=Controls (piomin-saga seed ${seed})::${ITERATIONS} valid schedules: ${line}"
  jq -e --argjson n "${ITERATIONS}" '.verdictCounts.PASS == $n' "${r}" > /dev/null \
    || { echo "::error title=Controls (piomin-saga seed ${seed})::a valid scenario did not PASS: a false alarm or an unknown defect, to investigate"; status=1; }
done
exit "${status}"
