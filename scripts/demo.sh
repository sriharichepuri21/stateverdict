#!/usr/bin/env bash
# One-command reproduction of the StateVerdict spike. Needs only Docker (with Compose) and curl.
#
#   scripts/demo.sh            run the whole demo, then stop the stack
#   KEEP=1 scripts/demo.sh     leave the stack running afterwards
#
# What it proves, in order:
#   1. baseline, naive handler         -> PASS   (no fault, no false alarm)
#   2. duplicate, naive handler        -> FAIL   (one order, two committed captures: SV-002)
#   3. replay of step 2's evidence     -> FAIL   (same verdict, no service contacted)
#   4. duplicate, idempotent handler   -> PASS   (same fault, fixed code, one capture)
#
# Exits 0 only if every step produced its expected verdict.
set -euo pipefail

cd "$(dirname "$0")/.."
COMPOSE=(docker compose -f infrastructure/compose/docker-compose.yml)
export HOST_UID="$(id -u)" HOST_GID="$(id -g)"
mkdir -p artifacts

cleanup() {
  if [ "${KEEP:-0}" != "1" ]; then
    echo; echo "Stopping the stack (set KEEP=1 to leave it running)..."
    "${COMPOSE[@]}" --profile cli down -v --remove-orphans > /dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

step() { echo; echo "================================================================"; echo "$*"; echo "================================================================"; }

results=()
expect() {  # expect NAME EXPECTED_VERDICT -- command...
  local name=$1 expected=$2; shift 3
  local code verdict
  set +e; "$@"; code=$?; set -e
  case "${code}" in 0) verdict=PASS ;; 1) verdict=FAIL ;; 2) verdict=INCONCLUSIVE ;; *) verdict=ERROR ;; esac
  local mark="ok"; [ "${verdict}" = "${expected}" ] || mark="UNEXPECTED"
  results+=("$(printf '%-38s expected %-5s got %-12s %s' "${name}" "${expected}" "${verdict}" "${mark}")")
}

cli() { "${COMPOSE[@]}" --profile cli run --rm -T stateverdict "$@"; }

step "Building images and starting PostgreSQL, Kafka, order-service, payment-service (naive handler)"
PAYMENT_CAPTURE_HANDLER=naive "${COMPOSE[@]}" up --build -d --wait
"${COMPOSE[@]}" --profile cli build stateverdict > /dev/null
scripts/wait-healthy.sh 8081 8082

step "1/4  Baseline: no fault, naive handler"
expect "1. baseline (naive handler)" PASS -- cli run checkout-duplicate-payment-request --copies 1

step "2/4  Deliver payment.requested twice, naive handler"
expect "2. duplicate (naive handler)" FAIL -- \
  cli run checkout-duplicate-payment-request --copies 2 --report artifacts/demo-duplicate-naive.json

step "3/4  Replay the saved evidence from step 2 (no service is contacted)"
expect "3. replay of step 2's evidence" FAIL -- cli replay-evidence artifacts/demo-duplicate-naive.json

step "Switching payment-service to the idempotent handler"
PAYMENT_CAPTURE_HANDLER=idempotent "${COMPOSE[@]}" up -d --wait payment-service
scripts/wait-healthy.sh 8082

step "4/4  Deliver payment.requested twice, idempotent handler"
expect "4. duplicate (idempotent handler)" PASS -- \
  cli run checkout-duplicate-payment-request --copies 2 --report artifacts/demo-duplicate-idempotent.json

step "Summary"
printf '%s\n' "${results[@]}"
echo
echo "Reports: artifacts/demo-duplicate-naive.json, artifacts/demo-duplicate-idempotent.json"
if printf '%s\n' "${results[@]}" | grep -q UNEXPECTED; then
  echo "DEMO FAILED: at least one step did not produce its expected verdict."
  exit 1
fi
echo "DEMO OK: the fault is detected against the naive handler and accepted after the fix."
