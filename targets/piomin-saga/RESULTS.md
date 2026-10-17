# Results: StateVerdict against piomin/sample-spring-kafka-microservices

- **Target:** [piomin/sample-spring-kafka-microservices](https://github.com/piomin/sample-spring-kafka-microservices)
  at commit `a76daeb` (September 2026), Spring Boot 4.1.1, Kafka Streams.
- **Run:** CI job "External target", run [39062748329](https://github.com/sriharichepuri21/stateverdict/actions/runs/39062748329).
  10 clean-state trials per schedule; each trial uses a new order, a fresh customer and a fresh product.
- **Predictions:** [PREDICTIONS.md](PREDICTIONS.md), committed before this run.

## Outcome

| Schedule | Predicted | Measured | Rule and what was observed |
|---|---|---|---|
| `baseline` | PASS | **PASS 10/10** | |
| `both-reject` | PASS | **PASS 10/10** | |
| `delay-payment-within-window` (2 s) | PASS | **PASS 10/10** | |
| `payment-rejects` | FAIL SAGA-2 | **FAIL 10/10** | ROLLBACK; the customer ends with 6000 available and -5000 reserved after starting with 1000: *5000 more money than it started with* |
| `stock-rejects` | FAIL SAGA-3 | **FAIL 10/10** | ROLLBACK; the product ends with 30 available and -20 reserved after starting with 10 |
| `duplicate-payment-reserve` | FAIL SAGA-2, SAGA-3 | **FAIL 10/10** (both rules, every trial) | CONFIRMED; the customer is charged 200 for a 100 order |
| `duplicate-payment-settle` | FAIL SAGA-2 | **FAIL 10/10** | CONFIRMED; the reservation goes to -100 |
| `delay-payment-past-window` (12 s) | FAIL SAGA-1 | **FAIL 10/10** | The order is still NEW after 30 s, and its reservations are never released |

**8 of 8 predictions held. Every schedule gave the same verdict on all 10 trials, with no ERROR, no
INCONCLUSIVE and no false alarm.** Medians: about 1.3 s for a passing run, about 6.4 s for a
settlement failure (the 5 s eventual window runs out), about 31 s for the undecided order (the 25 s
decision deadline plus the window).

## What these mean, in order of importance

1. **A rejected order corrupts the balance of the service that rejected it.** No fault injection is
   needed: it takes one order the customer cannot afford, or one with more items than are in stock.
   The order service sets the rollback's `source` to `"PAYMENT"` or `"STOCK"`. The payment and stock
   services compare it with `"payment"` and `"stock"`. Because the comparison never matches, both
   services "return" the reservation, including the one that never reserved anything. Money and items
   are created out of nothing, and the reservation goes negative. This is a plain bug, not a
   simplification. The target's own `ANALYSIS_AND_RECOMMENDATIONS.md` notes that many generated orders
   are rejected, so it should be frequent in the demo.
2. **Reservation and settlement are not idempotent.** If Kafka redelivers a message (at-least-once
   delivery, for example after a consumer restart before the offset commit), the customer is charged
   twice, or a reservation goes negative. A demo may leave this out on purpose. It is reported as an
   observation, not as a bug.
3. **A reply that is more than 10 s late leaves the order undecided forever.** The Kafka Streams
   join only matches replies within its window, and nothing times out or compensates afterwards. This
   is a design property of the windowed join, and it is reported as such.

## How much this owed to StateVerdict, honestly

- Finding 1 was **predicted from reading the code**, and confirmed by running. StateVerdict's
  contribution is the evidence: a reproducible scenario, the exact balances before and after, 10/10,
  and the same verdict on replay.
- Findings 2 and 3 need fault injection to show. Without a tool that can redeliver or delay one
  specific message on demand, they are hard to demonstrate.
- These schedules were written by hand. The section below repeats the exercise with generated
  schedules.

## Cost of integration

The adapter is **143 added lines and 3 changed lines** across 7 files of the target. No business logic
was changed: there are dispatcher hooks in two listeners, test-only state endpoints, and the order
service accepts a client-chosen id. Writing the StateVerdict side took about 600 lines (runner, three
rules, CLI command), plus tests.

## Limits

- One commit of one demo application, with ten trials per schedule on one CI runner type.
- Faults are applied at the payment and stock consumers. The order service's Kafka Streams topology is
  not intercepted.
- The rules restate the behaviour described in the target's README. They are not the author's own
  invariants.

## Generated exploration and shrinking (PR #18)

The same generic explorer and shrinker that run against the commerce example ran here, with a schedule
space for this target (`SagaSpace`). For each of the four messages a fault can target, the generator
chooses nothing, a duplicate, a concurrent duplicate, a short delay (≤ 2 s) or a long one (11 to 13 s).
For the order, it chooses affordable or not, and in stock or not. Nothing in it points at the bugs.

CI run [39072498345](https://github.com/sriharichepuri21/stateverdict/actions/runs/39072498345), seed 42:

- **60 generated schedules: 45 FAIL, 15 PASS, no ERROR or INCONCLUSIVE.** All three rules were
  violated. (The previous run on the same seed gave 46/14, so one schedule's outcome depends on timing.)
- The explorer found the rollback bug **without any fault**: an unaffordable order alone violated SAGA-2.

Each kind of finding was then shrunk. A candidate was kept only if it failed 3/3, and the original and
the result were each confirmed over 10 runs:

| Finding shrunk | Original | Locally minimal | Confirmed | What the shrink shows |
|---|---|---|---|---|
| Order never decided (SAGA-1) | 5 interventions | **payment reserve delayed 12 s** | 10/10 | Halving the delay to 6 s passed, so it was kept at 12 s: the reply must miss the 10 s join window. The order details and the other faults played no part |
| Money wrong after a rejection (SAGA-2) | 4 | **an unaffordable order, no fault** | 10/10 | The rollback case-mismatch bug, reduced to its trigger |
| Stock wrong after a rejection (SAGA-3) | 3 | **an order short of stock, no fault** | 10/10 | The same bug on the stock side |
| Money wrong, order affordable and in stock (SAGA-2) | 4 | **stock reserve delivered twice** | 10/10 | **Not predicted.** A duplicate in the *stock* service makes the *customer* pay twice: two stock ACCEPTs, joined with one payment ACCEPT, produce two CONFIRMED decisions, and payment settles both |

The fourth row is the most interesting result of this target. The hand-written schedules duplicated
the payment service's messages and predicted damage to money. The generator found that a duplicate in
the *other* service damages money too, through the order service's join. The shrinker reduced it to that
one cause from a schedule with four faults.

Cost: the external-target CI job takes about 52 minutes, most of it spent shrinking the never-decided
order (17 minutes), because every run of it waits out the 25 s decision deadline.
