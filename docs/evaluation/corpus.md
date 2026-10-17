# Defect corpus

A fixed set of deliberate changes to the example services, used to compare StateVerdict with the
conventional test suite on the same defects (spec section 10, gate 4). Each one is switched on by
configuration (`evaluation.mutation=M03` or `EVALUATION_MUTATION=M03`) on top of the **fixed**
handlers. Nothing is active by default, and a service logs a warning when any is.

- **Defects (M):** a change that breaks a business rule a reviewer would care about. A good test
  approach should detect it.
- **Controls (C):** a change that is harmless to the business. A good test approach should *not*
  flag it. A control that fails is a false alarm.

## The corpus

| Id | Service | Change | Business impact |
|---|---|---|---|
| M01 | payment | Idempotency removed (the naive capture handler) | A redelivered request charges the customer twice |
| M02 | order | Status guard removed (the naive completion handler) | A late payment outcome confirms an order the customer cancelled |
| M03 | payment | Idempotency as "check, then insert" without a transaction | Two copies processed **at the same time** both charge; sequential redeliveries are still skipped |
| M04 | order | Cancel no longer checks the status | A CONFIRMED (already paid) order can be cancelled; nothing refunds it |
| M05 | order | A DECLINED payment confirms the order | The order ships without payment |
| M06 | payment | The capture write is lost but CAPTURED is still published | The order is confirmed, the payment service has no record of the money |
| C01 | order | Extra read of the order before the conditional update | None: a refactor |
| C02 | payment | Handler is 300 ms slower | None: slower, still correct |
| C03 | payment | `payment.completed` is published twice | None: consumers must tolerate duplicates, and the order handler does |

## Predictions, written before measuring

Recorded here so the measured results can be checked against them honestly. **Measured in
[results.md](results.md): all nine held.**

| Id | StateVerdict (explore seed 42 + example schedules) | Conventional suite (JUnit + Testcontainers) |
|---|---|---|
| M01 | **detect** (SV-002) | detect: idempotent-handler tests |
| M02 | **detect** (SV-003) | detect: guarded-handler tests |
| M03 | **miss**: the dispatcher delivers copies one after another, never concurrently (ADR-002). *Revised in PR #16, before measuring:* **detect** (SV-002) once generated schedules include `DUPLICATE_CONCURRENT` | **detect**: the 8-concurrent-copies test |
| M04 | **miss**: no rule says a CONFIRMED order cannot be cancelled or must be refunded | **detect**: "only a pending order can be cancelled" test |
| M05 | **detect** (SV-001), only if exploration generates a declined amount | detect: "declined payment cancels the order" test |
| M06 | **detect** (SV-001) | detect: publishing test asserts one capture |
| C01 | pass | pass |
| C02 | pass | pass |
| C03 | pass | **false alarm**: a publishing test asserts exactly one completion event |

## Known bias

The defects, the rules and the conventional tests were all written by the same person, who knew
about the defects while writing the tests. The conventional suite is therefore probably *better*
at catching these particular defects than a typical team's suite would be. The comparison is still
useful for its shape: where each approach is structurally blind (concurrency for StateVerdict's
sequential dispatcher, missing rules for StateVerdict, implementation-detail assertions for unit
tests), not for the absolute scores.
