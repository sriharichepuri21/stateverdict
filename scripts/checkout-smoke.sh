#!/usr/bin/env bash
# End-to-end check against the running Compose stack:
# place one order and verify that exactly one payment capture is committed for it.
set -euo pipefail

ORDER_URL="${ORDER_URL:-http://localhost:8081}"
PAYMENT_URL="${PAYMENT_URL:-http://localhost:8082}"

order_id=$(curl -fsS -X POST "${ORDER_URL}/orders" \
  -H 'Content-Type: application/json' -d '{"amountCents": 129900}' | jq -r '.id')
echo "placed order ${order_id}"

for _ in $(seq 1 30); do
  count=$(curl -fsS "${PAYMENT_URL}/captures?orderId=${order_id}" | jq '.count')
  if [ "${count}" -ge 1 ]; then break; fi
  sleep 1
done

# Wait a little longer so a late duplicate capture would also show up.
sleep 3
count=$(curl -fsS "${PAYMENT_URL}/captures?orderId=${order_id}" | jq '.count')
echo "committed captures for ${order_id}: ${count}"

if [ "${count}" -ne 1 ]; then
  echo "FAIL: expected exactly 1 capture"; exit 1
fi
echo "OK: exactly one capture"
