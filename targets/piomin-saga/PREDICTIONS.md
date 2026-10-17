# Predictions (written before the first run)

Committed before StateVerdict was ever run against this target, so the results can be checked
against them. They come from **reading the target's code** (commit `a76daeb`), so they are not blind
discoveries. The point of running is to find out which ones are real, and how reliably.

## Rules

Each run uses a fresh customer and a fresh product, so their starting balances are known and no other
order touches them.

| Rule | Business statement |
|---|---|
| SAGA-1 | Every order is decided: CONFIRMED, REJECTED or ROLLBACK |
| SAGA-2 | Once decided, the customer's money is exactly what the decision says: the price taken once if CONFIRMED, nothing taken otherwise, nothing left reserved |
| SAGA-3 | The same for the product's stock |

These restate what the target's README describes (reserve, then confirm or roll back). They are not
taken from its tests.

## Predicted outcomes

| Schedule | What it does | Prediction | Why (from the code) |
|---|---|---|---|
| `baseline` | Affordable order, enough stock | **PASS** | |
| `both-reject` | Too expensive and too many items | **PASS** | Both reject, the order is REJECTED, nothing was reserved |
| `payment-rejects` | Too expensive, stock is fine | **FAIL SAGA-2** | The rollback's `source` is set to `"PAYMENT"`, but the payment service compares it with `"payment"`. The comparison never matches, so the payment service "returns" money it never reserved: the customer ends with 5000 more, and reserved = -5000 |
| `stock-rejects` | Too many items, money is fine | **FAIL SAGA-3** | Same mismatch in the stock service (`"STOCK"` vs `"stock"`): it returns 20 items it never reserved |
| `duplicate-payment-reserve` | The payment service receives the new order twice | **FAIL SAGA-2 and SAGA-3** | Reservation is not idempotent: 200 reserved for a 100 order, two ACCEPT replies, and the stream join emits two CONFIRMED decisions. Then the customer pays 200, and the stock reservation goes to -1 |
| `duplicate-payment-settle` | The payment service receives the decision twice | **FAIL SAGA-2** | Settlement is not idempotent: the reservation is released twice and goes to -100 |
| `delay-payment-within-window` | The payment reply is 2 s late | **PASS** | Within the order service's 10 s join window |
| `delay-payment-past-window` | The payment reply is 12 s late | **FAIL SAGA-1** | Outside the 10 s join window, the two replies never meet. The order stays NEW forever, with money and stock reserved |

## What would count against StateVerdict

- `baseline`, `both-reject` or `delay-payment-within-window` failing: a false alarm (or a harness bug).
- Any ERROR or INCONCLUSIVE run: the harness is not trustworthy on this target.
- A predicted FAIL that passes: the prediction was wrong, and is reported as wrong.
