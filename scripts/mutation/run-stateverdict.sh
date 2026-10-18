#!/usr/bin/env bash
# Run StateVerdict against PIT's exported mutants: one shard of them, each deployed for real.
#
#   scripts/mutation/run-stateverdict.sh SHARD SHARDS MUTANTS_JSON JARS_DIR
#
# For every mutant with index % SHARDS == SHARD: build the service's jar with the mutated class,
# restart both services (one mutated, one original), and run StateVerdict's standard suite, the same
# one the planted-bug head-to-head uses: the hand-written schedules in scenarios/, then 60 generated
# schedules (seed 42). It stops at the first FAIL, as PIT stops at the first failing test.
#
# Detected = at least one FAIL verdict (a business rule violated in committed state). A mutant whose
# service does not start, and INCONCLUSIVE or ERROR verdicts, are recorded separately and are not
# counted as detected.
#
# Before any mutant, the same suite runs against the original jars and must PASS everywhere;
# otherwise the shard's results are not trustworthy and the script exits 1.
set -uo pipefail
SHARD=$1 SHARDS=$2 MUTANTS=$3 JARS=$4
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="${OUT_DIR:-${ROOT}/artifacts}/mutation/shard-${SHARD}"
mkdir -p "${OUT}"
CLI="${CLI:-$(ls "${ROOT}"/apps/stateverdict-core/target/stateverdict-core-*.jar | grep -v original | head -1)}"
COMPOSE=(docker compose -f "${ROOT}/infrastructure/compose/docker-compose.yml" -f "${ROOT}/infrastructure/compose/mutant.override.yml")
ORIGINAL_ORDER="${JARS}/order-service.jar" ORIGINAL_PAYMENT="${JARS}/payment-service.jar"
WORK="$(mktemp -d)"

deploy() { # ORDER_JAR PAYMENT_JAR; returns 1 if a service does not become healthy
  ORDER_JAR=$1 PAYMENT_JAR=$2 "${COMPOSE[@]}" up -d --force-recreate --no-deps order-service payment-service > /dev/null 2>&1 \
    && timeout 150 "${ROOT}/scripts/wait-healthy.sh" 8081 8082 > /dev/null 2>&1
}

# suite NAME STOP_ON_FIRST -> writes ${OUT}/NAME/suite.json: {verdicts, detected, detectedBy, durationMs}
suite() {
  local name=$1 stop=$2 dir="${OUT}/$1" start verdicts='{}' detected_by="" code verdict
  mkdir -p "${dir}"
  start=$(date +%s%3N)
  for schedule in "${ROOT}"/scenarios/*.json; do
    timeout 120 java -jar "${CLI}" run-schedule "${schedule}" > "${dir}/$(basename "${schedule}" .json).txt" 2>&1
    code=$?
    verdict=$(case $code in 0) echo PASS;; 1) echo FAIL;; 2) echo INCONCLUSIVE;; *) echo ERROR;; esac)
    verdicts=$(jq -c --arg v "${verdict}" '.[$v] = ((.[$v] // 0) + 1)' <<< "${verdicts}")
    if [ "${verdict}" = FAIL ] && [ -z "${detected_by}" ]; then
      detected_by="schedule $(basename "${schedule}" .json)"
      [ "${stop}" = true ] && break
    fi
  done
  if [ -z "${detected_by}" ] || [ "${stop}" != true ]; then
    timeout 1200 java -jar "${CLI}" explore --iterations 60 --seed 42 --stop-on-first "${stop}" \
      --out "${dir}/found" --report "${dir}/explore.json" > "${dir}/explore.txt" 2>&1
    if [ -s "${dir}/explore.json" ]; then
      verdicts=$(jq -c --slurpfile e "${dir}/explore.json" \
        'reduce ($e[0].verdictCounts | to_entries[]) as $x (.; .[$x.key] = ((.[$x.key] // 0) + $x.value))' <<< "${verdicts}")
      if [ -z "${detected_by}" ] && [ "$(jq '.verdictCounts.FAIL // 0' "${dir}/explore.json")" -gt 0 ]; then
        detected_by="generated schedule $(jq -r '[.findings[].name] | first // "?"' "${dir}/explore.json")"
      fi
    else
      verdicts=$(jq -c '.ERROR = ((.ERROR // 0) + 1)' <<< "${verdicts}")
    fi
  fi
  jq -n --argjson v "${verdicts}" --arg by "${detected_by}" --argjson ms $(( $(date +%s%3N) - start )) \
    '{verdicts: $v, detected: ($by != ""), detectedBy: (if $by == "" then null else $by end), durationMs: $ms}' > "${dir}/suite.json"
}

ORDER_JAR="${ORIGINAL_ORDER}" PAYMENT_JAR="${ORIGINAL_PAYMENT}" "${COMPOSE[@]}" up -d --wait postgres kafka > /dev/null 2>&1 \
  || { echo "::error::infrastructure did not start"; exit 1; }

# 1. The original services must PASS the whole suite, or nothing below can be trusted.
deploy "${ORIGINAL_ORDER}" "${ORIGINAL_PAYMENT}" || { echo "::error::the original services did not start"; exit 1; }
suite baseline false
if ! jq -e '(.verdicts | keys) == ["PASS"]' "${OUT}/baseline/suite.json" > /dev/null; then
  echo "::error title=Mutation shard ${SHARD}::the original services did not PASS every schedule: $(jq -c .verdicts "${OUT}/baseline/suite.json")"
  "${COMPOSE[@]}" logs --no-color > "${OUT}/baseline/services.log" 2>&1
  exit 1
fi
echo "shard ${SHARD}: original services PASS $(jq -c .verdicts "${OUT}/baseline/suite.json")"

# 2. The mutants of this shard.
results='[]'
count=$(jq length "${MUTANTS}")
for (( i = SHARD; i < count; i += SHARDS )); do
  m=$(jq -c ".[${i}]" "${MUTANTS}")
  id=$(jq -r .id <<< "${m}") service=$(jq -r .service <<< "${m}")
  dir_name="m${i}"
  start=$(date +%s%3N)
  order="${ORIGINAL_ORDER}" payment="${ORIGINAL_PAYMENT}"
  mutated="${WORK}/${service}-mutant.jar"
  python3 "${ROOT}/scripts/mutation/patch-jar.py" "${JARS}/${service}-service.jar" "$(jq -r .class <<< "${m}")" \
    "${JARS}/$(jq -r .classFile <<< "${m}")" "${mutated}" \
    || { echo "::error::could not build the jar for ${id}"; exit 1; }
  if [ "${service}" = order ]; then order="${mutated}"; else payment="${mutated}"; fi
  if deploy "${order}" "${payment}"; then
    suite "${dir_name}" true
    entry=$(jq -c --arg id "${id}" --argjson i "${i}" '{index: $i, id: $id, startup: "ok"} + .' "${OUT}/${dir_name}/suite.json")
  else
    mkdir -p "${OUT}/${dir_name}"
    "${COMPOSE[@]}" logs --no-color --tail 200 "${service}-service" > "${OUT}/${dir_name}/startup.log" 2>&1
    entry=$(jq -nc --arg id "${id}" --argjson i "${i}" --argjson ms $(( $(date +%s%3N) - start )) \
      '{index: $i, id: $id, startup: "failed", verdicts: {}, detected: false, detectedBy: null, durationMs: $ms}')
  fi
  echo "${id}: $(jq -r '"\(.startup), \(if .detected then "DETECTED by \(.detectedBy)" else "not detected" end) \(.verdicts | tostring) \(.durationMs / 1000 | floor)s"' <<< "${entry}")"
  results=$(jq -c --argjson e "${entry}" '. + [$e]' <<< "${results}")
  rm -f "${mutated}"
done

jq -n --argjson shard "${SHARD}" --argjson shards "${SHARDS}" --argjson results "${results}" \
  --slurpfile baseline "${OUT}/baseline/suite.json" \
  '{shard: $shard, shards: $shards, baseline: $baseline[0], mutants: $results}' > "${OUT}/results.json"
ORDER_JAR="${ORIGINAL_ORDER}" PAYMENT_JAR="${ORIGINAL_PAYMENT}" "${COMPOSE[@]}" down -v > /dev/null 2>&1
echo "shard ${SHARD}: $(jq length <<< "${results}") mutants, $(jq '[.[] | select(.detected)] | length' <<< "${results}") detected"
