# Spike result: reproduction of the duplicate-capture defect

- **Date:** 2026-10-16
- **Commits measured:** branch `feat/repetitions` (PR #5, naive handler only) and
  `feat/idempotent-handler` (PR #6, both handlers)
- **Where:** GitHub Actions, `ubuntu-24.04` runner, full Compose stack (PostgreSQL 17, Kafka 4.1 KRaft, both services)
- **CI runs:** [38944476200](https://github.com/sriharichepuri21/stateverdict/actions/runs/38944476200) (naive),
  [38946710200](https://github.com/sriharichepuri21/stateverdict/actions/runs/38946710200) (naive and idempotent)
- **Commands:** `scripts/measure-reproduction.sh 10 naive`, then `scripts/measure-reproduction.sh 10 idempotent`

## Setup

Scenario `checkout-duplicate-payment-request` v1, rule SV-002 v1 ("at most one committed payment
capture per order"), against the **deliberately naive** payment handler.

Every trial starts from clean state: a new run id, a new order, and the run's dispatcher state reset
before and after. Trials share the database, but SV-002 reads captures for that trial's order only,
so trials cannot see each other's captures.

| Configuration | Fault | Role |
|---|---|---|
| baseline | none (`--copies 1`) | negative control: the tool must not report a violation that is not there |
| duplicate | `payment.requested` delivered twice (`--copies 2`) | the defect under test |

## Measured results

| Configuration | Trials | PASS | FAIL | INCONCLUSIVE | ERROR | Median time per trial |
|---|---|---|---|---|---|---|
| baseline | 10 | **10** | 0 | 0 | 0 | 2040 ms |
| duplicate | 10 | 0 | **10** | 0 | 0 | 2051 ms |

Every duplicate trial observed exactly two committed captures for one order. Every baseline trial
observed exactly one. No trial timed out, lost evidence or hit an infrastructure error.

## Before and after the fix (CI run 38946710200)

Same scenario, same rule, same 10 + 10 clean-state trials. Only the payment handler changes: the
stack is started with the naive handler, then the payment service is recreated with the
idempotent one.

| Handler | Configuration | Trials | PASS | FAIL | INCONCLUSIVE | ERROR | Median per trial |
|---|---|---|---|---|---|---|---|
| naive | baseline | 10 | **10** | 0 | 0 | 0 | 2053 ms |
| naive | duplicate | 10 | 0 | **10** | 0 | 0 | 2044 ms |
| idempotent | baseline | 10 | **10** | 0 | 0 | 0 | 2042 ms |
| idempotent | duplicate | 10 | **10** | 0 | 0 | 0 | 2287 ms |

With the idempotent handler, the dispatcher journal still shows the event delivered twice, but only
one capture is committed. StateVerdict tells "duplicate delivery" (expected, harmless) apart from
"duplicate side effect" (the defect) because SV-002 reads committed captures, not deliveries.

The higher median for idempotent/duplicate (about 240 ms) was not investigated. The idempotent
trials run right after the payment service restarts, so JVM warm-up is one plausible cause, not a
measured one.

### A failure worth recording

The first CI run of the idempotent trials ([38946344615](https://github.com/sriharichepuri21/stateverdict/actions/runs/38946344615))
reported **ERROR 10/10**, each in about 6 ms. CI had started the trials as soon as the recreated
container was running, before Spring had finished starting. StateVerdict could not reach the
control API, classified that as `INFRA_ERROR`, and reported ERROR, not PASS. The CI ordering was
fixed (`scripts/wait-healthy.sh`); the tool's behaviour was what ADR-004 requires.

## What this does and does not show

- **Shows:** with the controlled dispatcher, the duplicate-capture defect reproduces on every trial,
  and the same scenario without the fault passes on every trial. So the FAIL comes from the fault,
  not from the tool or the environment.
- **Expected, not surprising:** 10/10 is the result ADR-002 predicts. The dispatcher delivers the two
  copies one after another, so this defect has no timing dependence. Faults that depend on real
  concurrency (two consumers racing) are not covered and would need their own measurement.
- **Sample size:** 10 trials per configuration, on one runner type. Enough to detect a flaky setup;
  not a statistical claim about rare failures.
- **Timing:** about 2 s of each trial is the configured settle window (`--settle-ms 2000`), which
  waits for late side effects after the expected deliveries arrive. Trial time is dominated by that
  wait, not by the services.
- **The fix is judged on behaviour, not code:** nothing in StateVerdict knows which handler is
  running. The verdict flipped because the committed state changed.
