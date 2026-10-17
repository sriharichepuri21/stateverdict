#!/usr/bin/env bash
# Wait until the target can process an order end to end. A started service is not enough: the
# payment and stock consumers must have joined their groups, and the order service's Kafka Streams
# join must be running. So place warm-up orders (outside any StateVerdict run) until one is decided.
set -euo pipefail
ORDER=http://localhost:8091 PAYMENT=http://localhost:8092 STOCK=http://localhost:8093

for _ in $(seq 1 90); do
  if curl -fsS "${ORDER}/orders" > /dev/null 2>&1 \
     && curl -fsS "${PAYMENT}/_stateverdict/dispatch/runs/ready/journal" > /dev/null 2>&1 \
     && curl -fsS "${STOCK}/_stateverdict/dispatch/runs/ready/journal" > /dev/null 2>&1; then
    break
  fi
  sleep 2
done

for attempt in $(seq 1 20); do
  id=$((900000000 + attempt))
  curl -fsS -X POST "${ORDER}/orders" -H 'Content-Type: application/json' \
    -d "{\"id\":${id},\"customerId\":1,\"productId\":1,\"productCount\":1,\"price\":1,\"status\":\"NEW\"}" > /dev/null
  for _ in $(seq 1 10); do
    status=$(curl -fsS "${ORDER}/orders" | jq -r --argjson id "${id}" '.[] | select(.id == $id) | .status')
    if [ -n "${status}" ] && [ "${status}" != "NEW" ]; then
      echo "target ready: warm-up order ${id} decided ${status} (attempt ${attempt})"; exit 0
    fi
    sleep 1
  done
done
echo "target never decided a warm-up order"; exit 1
