#!/usr/bin/env bash
# What the StateVerdict adapter costs the external target when no fault is injected.
#
#   targets/piomin-saga/overhead.sh [ROUNDS] [ORDERS]     (defaults: 3 rounds, 300 orders per run)
#
# Three configurations of the same application, each started fresh:
#   upstream    the unpatched application at the pinned commit (prepare.sh --upstream)
#   direct      with the adapter, in its production mode: one delivery per message
#   controlled  with the adapter, in test mode, no fault armed: every message goes through the
#               controlled dispatcher, which looks up rules and journals the delivery
#
# Each round runs all three, in a rotated order, so drift on the machine (warm caches, a noisy
# neighbour) does not always land on the same configuration. In each run, warm-up orders come first,
# then ORDERS orders at a steady 10 per second (a light load, so this measures latency, not
# throughput).
#
# What is measured: for every order, the time from the order event (as published by the order service)
# to the later of the two reservation replies (payment and stock). That is the path the adapter sits
# on: consume, dispatch, reserve, reply. The times are the Kafka record timestamps, which the producers
# set when they send; all three services share one clock. The order service's join afterwards is
# the same code in every configuration and is not part of the measurement.
#
# The script fails only if the measurement is not trustworthy: an order without both replies or
# without a decision, or a build that is not what its configuration claims. It never fails on the
# size of the overhead; analyze-overhead.py reports whatever it is.
set -euo pipefail
ROUNDS=${1:-3}
ORDERS=${2:-300}
WARMUP=${WARMUP:-50}
INTERVAL=${INTERVAL:-0.1}
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "${HERE}/../.." && pwd)"
OUT="${OUT_DIR:-${ROOT}/artifacts}/piomin-saga-overhead"
mkdir -p "${OUT}"
ORDER=http://localhost:8091 PAYMENT=http://localhost:8092
COMPOSE=(docker compose -p stateverdict-overhead -f "${HERE}/docker-compose.yml")

declare -A BUILD=(
  [upstream]="${ROOT}/build/targets/piomin-saga-upstream"
  [direct]="${ROOT}/build/targets/piomin-saga"
  [controlled]="${ROOT}/build/targets/piomin-saga"
)
declare -A MODE=([upstream]=direct [direct]=direct [controlled]=controlled)

# The builds are what they claim: the adapter's classes are in the patched jars and not in upstream.
for service in payment stock; do
  jar="${service}-service/target/${service}-service-1.0-SNAPSHOT.jar"
  # Listed into a variable first: with pipefail, "unzip | grep -q" can fail on SIGPIPE.
  upstream_files=$(unzip -Z1 "${BUILD[upstream]}/${jar}")
  patched_files=$(unzip -Z1 "${BUILD[direct]}/${jar}")
  if grep -q 'stateverdict-dispatch' <<< "${upstream_files}"; then
    echo "::error::upstream ${service}-service contains the adapter"; exit 1
  fi
  grep -q 'stateverdict-dispatch' <<< "${patched_files}" \
    || { echo "::error::patched ${service}-service does not contain the adapter"; exit 1; }
done

order_json() { # deterministic, spread over the target's 100 customers and 1000 products
  local i=$1
  printf '{"customerId":%d,"productId":%d,"productCount":1,"price":%d,"status":"NEW"}' \
    $((1 + (i * 37) % 100)) $((1 + (i * 101) % 1000)) $((1 + i % 50))
}

place() { curl -fsS -o /dev/null -X POST "${ORDER}/orders" -H 'Content-Type: application/json' -d "$(order_json "$1")"; }

wait_ready() {
  for _ in $(seq 1 90); do
    curl -fsS "${ORDER}/orders" > /dev/null 2>&1 && break
    sleep 2
  done
  for attempt in $(seq 1 30); do
    place "${attempt}" || true
    sleep 1
    if curl -fsS "${ORDER}/orders" 2>/dev/null | jq -e 'any(.[]; .status != "NEW")' > /dev/null; then
      echo "ready after ${attempt} warm-up order(s)"; return 0
    fi
  done
  echo "::error::the target never decided an order"; return 1
}

dump() { # every record of a topic as: timestamp <TAB> key <TAB> value
  "${COMPOSE[@]}" exec -T broker /opt/kafka/bin/kafka-console-consumer.sh \
    --bootstrap-server broker:9092 --topic "$1" --from-beginning --timeout-ms 15000 \
    --property print.timestamp=true --property print.key=true \
    --property key.deserializer=org.apache.kafka.common.serialization.LongDeserializer 2>/dev/null \
    | sed -E 's/^CreateTime:([0-9]+)\t/\1\t/' || true   # a missing record is caught by the analysis
}

configs=(upstream direct controlled)
for round in $(seq 1 "${ROUNDS}"); do
  for k in 0 1 2; do
    config=${configs[$(( (k + round - 1) % 3 ))]}
    run="${OUT}/r${round}-${config}"
    mkdir -p "${run}"
    echo "=== round ${round}: ${config}"
    TARGET_BUILD="${BUILD[${config}]}" STATEVERDICT_DISPATCH_MODE="${MODE[${config}]}" \
      "${COMPOSE[@]}" up -d --wait broker
    TARGET_BUILD="${BUILD[${config}]}" STATEVERDICT_DISPATCH_MODE="${MODE[${config}]}" \
      "${COMPOSE[@]}" up -d
    wait_ready
    for i in $(seq 1 "${WARMUP}"); do place $((1000 + i)); sleep "${INTERVAL}"; done
    sleep 3
    start=$(date +%s%3N)
    for i in $(seq 1 "${ORDERS}"); do place $((2000 + i)); sleep "${INTERVAL}"; done
    sleep 12   # beyond the join window, so every decision is out
    for topic in orders payment-orders stock-orders; do dump "${topic}" > "${run}/${topic}.tsv"; done
    if [ "${config}" = controlled ]; then
      # Evidence that the measured deliveries really went through the controlled dispatcher.
      id=$(awk -F'\t' -v s="${start}" '$1 >= s && $3 ~ /"status":"NEW"/ {print $2; exit}' "${run}/orders.tsv")
      curl -fsS "${PAYMENT}/_stateverdict/dispatch/runs/order-${id}/journal" > "${run}/journal-sample.json"
    fi
    jq -n --arg config "${config}" --argjson round "${round}" --argjson start "${start}" \
      --argjson orders "${ORDERS}" --arg interval "${INTERVAL}" \
      '{config: $config, round: $round, measuredFromMs: $start, orders: $orders, intervalSeconds: $interval}' \
      > "${run}/run.json"
    "${COMPOSE[@]}" logs --no-color > "${run}/services.log" 2>&1 || true
    "${COMPOSE[@]}" down -v > /dev/null 2>&1
  done
done

python3 "${ROOT}/scripts/analyze-overhead.py" "${OUT}" "${OUT}/overhead.json"
