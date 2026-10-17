# Schedules

A schedule is an ordered list of steps that `stateverdict run-schedule` runs against the order and
payment services in one run. After the steps, StateVerdict waits until the workflow is complete,
collects committed state, and evaluates SV-001 (a CONFIRMED order has a capture), SV-002 (at most
one capture per order), SV-003 (a cancelled order never ends CONFIRMED) and SV-004 (a captured payment
for a cancelled order is refunded; an "eventually" rule, re-observed for up to 5 s).

| File | What it does | Naive handlers | Fixed handlers |
|---|---|---|---|
| `checkout-baseline.json` | One normal order, no faults (negative control) | PASS | PASS |
| `duplicate-payment-request.json` | `payment.requested` delivered twice | FAIL (SV-002) | PASS |
| `cancel-while-payment-completion-held.json` | Hold `payment.completed`, cancel while it is held, then release it | FAIL (SV-003) | PASS |

## Steps

| `type` | Fields | Meaning |
|---|---|---|
| `ARM` | `service` (`ORDER`/`PAYMENT`), `topic`, `fault` (`HOLD`, `DUPLICATE` + `copies`, `DELAY` + `delayMs`) | Fault for the next matching message the service consumes |
| `PLACE_ORDER` | `amountCents` (above 500000 is declined) | Place the order, tagged with the run id |
| `AWAIT_HELD` | `service`, `topic`, `count` | Wait until that many messages are held, so the next step happens *while* they are held |
| `CANCEL` | | The customer cancels the order (409 if it already moved on is a normal outcome) |
| `AWAIT_ORDER_STATUS` | `status` (`PENDING`/`CONFIRMED`/`CANCELLED`) | Wait until the order reaches that status, e.g. to cancel *after* confirmation |
| `RELEASE_ALL` | `service` | Deliver everything the service holds for this run |

Rules that make a schedule valid (checked before it runs): exactly one `PLACE_ORDER`; every `ARM`
before it; at most one `CANCEL`, after it; `AWAIT_HELD` only for an armed `HOLD`; every `HOLD`
followed by a `RELEASE_ALL` for its service, with an `AWAIT_HELD` before that release; every `HOLD`
released before an `AWAIT_ORDER_STATUS`.

```bash
SV="docker compose -f infrastructure/compose/docker-compose.yml --profile cli run --rm stateverdict"
$SV run-schedule scenarios/cancel-while-payment-completion-held.json
$SV run-schedule scenarios/cancel-while-payment-completion-held.json --trials 10
```
