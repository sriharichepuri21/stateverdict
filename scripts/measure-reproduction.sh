#!/usr/bin/env bash
# Spike step 5: measure, rather than assume, how reliably each outcome reproduces.
#
# Runs the duplicate-delivery scenario N times with the fault and N times without it
# (baseline), each trial from clean state, and records the verdict distribution.
#
#   scripts/measure-reproduction.sh [TRIALS] [HANDLER]
#     TRIALS   trials per configuration (default 10)
#     HANDLER  which payment handler the running stack uses: naive (default) or idempotent
#
# Expected:
#   baseline  -> PASS on every trial, for both handlers (negative control: no false alarms)
#   duplicate -> FAIL on every trial with the naive handler (the double capture reproduces)
#             -> PASS on every trial with the idempotent handler (the fix is accepted)
# The script exits non-zero if any expectation is not met on every trial.
set -euo pipefail

TRIALS="${1:-10}"
HANDLER="${2:-naive}"
case "${HANDLER}" in
  naive) EXPECT_DUPLICATE=FAIL ;;
  idempotent) EXPECT_DUPLICATE=PASS ;;
  *) echo "HANDLER must be naive or idempotent, got '${HANDLER}'"; exit 2 ;;
esac
CLI="$(ls apps/stateverdict-core/target/stateverdict-core-*.jar | grep -v original | head -1)"
OUT="${OUT_DIR:-artifacts}"
mkdir -p "${OUT}"

measure() {
  local name=$1 copies=$2 expected=$3
  echo "=== ${name}: ${TRIALS} trials, copies=${copies}, expected ${expected} ==="
  set +e
  java -jar "${CLI}" run checkout-duplicate-payment-request \
    --copies "${copies}" --trials "${TRIALS}" --report "${OUT}/${name}-trials.json" | tee "${OUT}/${name}-trials.txt"
  local code=${PIPESTATUS[0]}
  set -e
  local counts
  counts=$(jq -r --arg t "${TRIALS}" '[.verdictCounts | to_entries[] | "\(.key) \(.value)/\($t)"] | join(", ")' \
    "${OUT}/${name}-trials.json")
  local median
  median=$(jq -r '.medianDurationMs' "${OUT}/${name}-trials.json")
  echo "::notice title=Reproduction (${name})::${counts}; median ${median} ms per trial"
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    {
      echo "### ${name}: ${counts} (median ${median} ms per trial)"
      echo '```'
      sed -n '/repeated trials/,$p' "${OUT}/${name}-trials.txt"
      echo '```'
    } >> "${GITHUB_STEP_SUMMARY}"
  fi
  local expected_code
  case "${expected}" in PASS) expected_code=0 ;; FAIL) expected_code=1 ;; *) expected_code=-1 ;; esac
  if [ "${code}" -ne "${expected_code}" ]; then
    echo "::error title=Reproduction (${name})::expected ${expected} on all ${TRIALS} trials; got ${counts} (exit ${code})"
    return 1
  fi
}

status=0
measure "baseline-${HANDLER}" 1 PASS || status=1
measure "duplicate-${HANDLER}" 2 "${EXPECT_DUPLICATE}" || status=1
exit "${status}"
