#!/usr/bin/env bash
# Evaluate one corpus configuration with both approaches and write artifacts/eval/<CONFIG>.json.
#
#   scripts/evaluate-config.sh CONFIG
#     CONFIG  baseline | M01..M06 | C01..C03   (see docs/evaluation/corpus.md)
#
# 1. Conventional suite: the order- and payment-service JUnit/Testcontainers tests, run with the
#    configuration applied as system properties. "Detected" = at least one test fails.
# 2. StateVerdict: the Compose stack with the configuration applied, then `explore` (seed 42, 60
#    generated schedules) and each example schedule once. "Detected" = at least one FAIL.
# For controls, "detected" means a false alarm.
set -uo pipefail

CONFIG=$1
OUT=artifacts/eval
mkdir -p "${OUT}"

payment_handler=idempotent; order_handler=guarded; mutation=""
case "${CONFIG}" in
  baseline) ;;
  M01) payment_handler=naive ;;
  M02) order_handler=naive ;;
  M0[3-6]|C0[1-3]) mutation="${CONFIG}" ;;
  *) echo "unknown configuration ${CONFIG}"; exit 2 ;;
esac
kind=$([[ "${CONFIG}" == C* ]] && echo control || { [[ "${CONFIG}" == baseline ]] && echo baseline || echo defect; })

# ---- 1. Conventional suite -----------------------------------------------------------------
mvn -B -q install -DskipTests > /dev/null
find examples -path '*target/surefire-reports*' -delete 2>/dev/null
# -fae: run every module's tests even if an earlier module fails, so counts are comparable.
mvn -B -fae test -pl examples/commerce/order-service,examples/commerce/payment-service \
  -Dpayment.capture.handler="${payment_handler}" -Dorder.completion.handler="${order_handler}" \
  -Devaluation.mutation="${mutation}" > "${OUT}/${CONFIG}-junit.log" 2>&1
junit_exit=$?
read -r tests_run failing < <(python3 - <<'PY'
import glob, json, xml.etree.ElementTree as ET
run, failing = 0, []
for path in glob.glob("examples/commerce/*/target/surefire-reports/TEST-*.xml"):
    suite = ET.parse(path).getroot()
    run += int(suite.get("tests", 0))
    for case in suite.iter("testcase"):
        if case.find("failure") is not None or case.find("error") is not None:
            failing.append(case.get("classname").rsplit(".", 1)[-1] + "." + case.get("name"))
print(run, json.dumps(sorted(set(failing)), separators=(",", ":")))
PY
)

# ---- 2. StateVerdict -----------------------------------------------------------------------
CLI="$(ls apps/stateverdict-core/target/stateverdict-core-*.jar | grep -v original | head -1)"
export PAYMENT_CAPTURE_HANDLER="${payment_handler}" ORDER_COMPLETION_HANDLER="${order_handler}" EVALUATION_MUTATION="${mutation}"
docker compose -f infrastructure/compose/docker-compose.yml up --build -d --wait > "${OUT}/${CONFIG}-compose.log" 2>&1
scripts/wait-healthy.sh 8081 8082
# Guard: measure nothing unless the configuration really is active inside the containers.
for check in order-service:EVALUATION_MUTATION order-service:ORDER_COMPLETION_HANDLER \
             payment-service:EVALUATION_MUTATION payment-service:PAYMENT_CAPTURE_HANDLER; do
  svc=${check%%:*} var=${check##*:}
  expected="${!var}"
  actual=$(docker compose -f infrastructure/compose/docker-compose.yml exec -T "${svc}" printenv "${var}" 2>/dev/null || true)
  if [ "${actual}" != "${expected}" ]; then
    echo "::error::${svc} has ${var}='${actual}', expected '${expected}': configuration not applied"; exit 1
  fi
done
if [ -n "${mutation}" ]; then
  # Read the logs into a variable first: piping straight into `grep -q` lets grep exit early,
  # which kills `docker compose logs` with SIGPIPE and fails the pipeline under pipefail.
  service_logs=$(docker compose -f infrastructure/compose/docker-compose.yml logs --no-color order-service payment-service)
  if ! grep -q "EVALUATION MUTATIONS ACTIVE: \\[${mutation}\\]" <<< "${service_logs}"; then
    echo "::error::no service logged that mutation ${mutation} is active"; exit 1
  fi
fi
java -jar "${CLI}" explore --iterations 60 --seed 42 --out "${OUT}/${CONFIG}-found" \
  --report "${OUT}/${CONFIG}-explore.json" > "${OUT}/${CONFIG}-explore.txt" 2>&1
schedules='[]'
for schedule in scenarios/*.json; do
  name=$(basename "${schedule}" .json)
  java -jar "${CLI}" run-schedule "${schedule}" > "${OUT}/${CONFIG}-${name}.txt" 2>&1
  code=$?
  verdict=$(case $code in 0) echo PASS;; 1) echo FAIL;; 2) echo INCONCLUSIVE;; *) echo ERROR;; esac)
  rules=$(grep -B4 'Result:   VIOLATED' "${OUT}/${CONFIG}-${name}.txt" | grep -o '^Rule SV-[0-9]*' | cut -d' ' -f2 | jq -R . | jq -s .)
  schedules=$(jq -c --arg n "${name}" --arg v "${verdict}" --argjson r "${rules}" '. + [{name: $n, verdict: $v, violated: $r}]' <<< "${schedules}")
done
if [ "${CONFIG}" = M03 ]; then
  # M03 is the concurrency defect: measure how reliably the race reproduces, and shrink a generated
  # finding to see whether the shrinker keeps the concurrency (it should: copies delivered one after
  # another are skipped correctly). Measured only; the head-to-head result above is what is judged.
  OUT_DIR="${OUT}" scripts/measure-schedule.sh scenarios/concurrent-duplicate-payment-request.json 10 FAIL m03-concurrent || true
  OUT_DIR="${OUT}" scripts/measure-schedule.sh scenarios/duplicate-payment-request.json 10 PASS m03-sequential || true
  finding=$(jq -r '[.findings[] | select(.violatedRules | index("SV-002"))] | first | .savedAs // empty' "${OUT}/${CONFIG}-explore.json")
  if [ -n "${finding}" ]; then
    java -jar "${CLI}" shrink "${finding}" --rules SV-002 --trials 3 --confirm 10 \
      --out "${OUT}/M03-shrunk.min.json" --report "${OUT}/M03-shrink.json" > "${OUT}/M03-shrink.txt" 2>&1
    shrunk=$(jq -r '"\(.status), fails \(.minimizedFailures)/\(.confirmTrials); minimal: \([.minimized.steps[] | if .type == "ARM" then "ARM \(.fault.type) x\(.fault.copies // "") \(.topic)" else .type end] | join(" > "))"' "${OUT}/M03-shrink.json")
    echo "::notice title=Shrink (M03 finding)::${shrunk}"
  fi
fi
docker compose -f infrastructure/compose/docker-compose.yml down -v > /dev/null 2>&1

jq -n --arg config "${CONFIG}" --arg kind "${kind}" --arg mutation "${mutation}" \
  --argjson junitExit "${junit_exit}" --argjson testsRun "${tests_run:-0}" --argjson failing "${failing:-[]}" \
  --argjson schedules "${schedules}" --slurpfile explore "${OUT}/${CONFIG}-explore.json" '
  ($explore[0]) as $e
  | {
      config: $config, kind: $kind,
      junit: {testsRun: $testsRun, failed: ($failing | length), exitCode: $junitExit, failingTests: $failing,
              detected: ($junitExit != 0)},
      stateverdict: {
        explore: {verdicts: $e.verdictCounts, findings: ($e.findings | length),
                  rulesViolated: ([$e.findings[].violatedRules[]] | unique)},
        schedules: $schedules,
        inconclusive: (($e.verdictCounts.INCONCLUSIVE // 0) + ([$schedules[] | select(.verdict == "INCONCLUSIVE")] | length)),
        rulesViolated: ([$e.findings[].violatedRules[]] + [$schedules[].violated[]] | unique),
        detected: ((($e.findings | length) > 0) or ([$schedules[] | select(.verdict == "FAIL")] | length > 0))
      }
    }' > "${OUT}/${CONFIG}.json"
cat "${OUT}/${CONFIG}.json"
