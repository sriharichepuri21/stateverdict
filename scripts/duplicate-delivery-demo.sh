#!/usr/bin/env bash
# Demonstrates controlled duplicate delivery against the running Compose stack:
#   1. arm a DUPLICATE intervention for a new run
#   2. place one order tagged with that run id
#   3. show the dispatcher journal (the same event delivered twice) and the committed captures
#
# With today's naive payment handler this produces TWO captures for ONE order: the
# double-charge defect that rule SV-002 will report as FAIL.
set -euo pipefail

ORDER_URL="${ORDER_URL:-http://localhost:8081}"
PAYMENT_URL="${PAYMENT_URL:-http://localhost:8082}"
RUN_ID="${RUN_ID:-demo-$(date +%s)}"
CONTROL="${PAYMENT_URL}/_stateverdict/dispatch/runs/${RUN_ID}"

echo "run ${RUN_ID}: arming DUPLICATE x2 on payment.requested"
curl -fsS -X POST "${CONTROL}/interventions" \
  -H 'Content-Type: application/json' -d '{"type": "DUPLICATE", "copies": 2}'

order_id=$(curl -fsS -X POST "${ORDER_URL}/orders" \
  -H 'Content-Type: application/json' -H "X-Correlation-Id: ${RUN_ID}" \
  -d '{"amountCents": 129900}' | jq -r '.id')
echo "placed order ${order_id}"

for _ in $(seq 1 30); do
  deliveries=$(curl -fsS "${CONTROL}/journal" | jq '[.[] | select(.action == "DELIVERED")] | length')
  if [ "${deliveries}" -ge 2 ]; then break; fi
  sleep 1
done

echo
echo "dispatcher journal:"
curl -fsS "${CONTROL}/journal" | jq -r '.[] | "  \(.action) eventKey=\(.eventKey) attempt=\(.attempt) outcome=\(.outcome)"'

captures=$(curl -fsS "${PAYMENT_URL}/captures?orderId=${order_id}" | jq '.count')
echo
echo "committed captures for order ${order_id}: ${captures}"

curl -fsS -X DELETE "${CONTROL}" > /dev/null

if [ "${deliveries}" -ne 2 ]; then
  echo "UNEXPECTED: journal shows ${deliveries} deliveries, expected 2"; exit 1
fi
if [ "${captures}" -eq 2 ]; then
  echo "result: same event delivered twice -> 2 captures (the naive handler double-charges)"
elif [ "${captures}" -eq 1 ]; then
  echo "result: same event delivered twice -> 1 capture (the handler is idempotent)"
else
  echo "UNEXPECTED: ${captures} captures"; exit 1
fi
