# Spike result: reproduction of the duplicate-capture defect

- **Date:** 2026-10-16
- **Commit measured:** branch `feat/repetitions` (PR #5)
- **Where:** GitHub Actions, `ubuntu-24.04` runner, full Compose stack (PostgreSQL 17, Kafka 4.1 KRaft, both services)
- **CI run:** [38944476200](https://github.com/sriharichepuri21/stateverdict/actions/runs/38944476200)
- **Command:** `scripts/measure-reproduction.sh 10`

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
- **Still to measure:** the same 10 + 10 trials after the idempotent fix (spike step 6). The
  duplicate configuration is expected to flip to PASS 10/10 with the baseline unchanged.
