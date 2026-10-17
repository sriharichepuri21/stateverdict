# Exploration results (gate 2)

- **Date:** 2026-10-16
- **CI run:** [38997158713](https://github.com/sriharichepuri21/stateverdict/actions/runs/38997158713)
- **Command:** `stateverdict explore --iterations 60 --seed 42` (`scripts/explore.sh`)
- **Rules:** SV-002 (at most one capture per order), SV-003 (a cancelled order never ends CONFIRMED)

## Question

Can StateVerdict find rule violations in schedules that nobody wrote, without being told which
faults or which customer action lead to them?

## Setup

The generator draws each schedule from a fixed, documented space (see `ScheduleGenerator`): for each
of the two events, no fault, `DUPLICATE` (2–3 copies), `DELAY` (100–1500 ms) or `HOLD`; the customer
does not cancel, cancels right after placing the order, or cancels while a held message is parked;
the amount is usually approved, sometimes declined. Every schedule is valid by construction and runs
once. The same seed generates the same 60 schedules against both configurations.

## Result

| Handlers | Schedules | PASS | FAIL | INCONCLUSIVE | ERROR | Rules violated |
|---|---|---|---|---|---|---|
| naive | 60 | 36 | **24** | 0 | 0 | **SV-002, SV-003** |
| fixed (idempotent payment, guarded order) | 60 | **60** | 0 | 0 | 0 | none |

**Gate 2 is met:** generated schedules found violations of both rules, and the fixed handlers passed
every one of the same schedules.

## What the 24 findings were

| Count | Rules | Faults | Customer |
|---|---|---|---|
| 4 | SV-003 | **none** | cancel right after order |
| 3 | SV-003 | `payment.completed` HOLD | cancel while `payment.completed` held (**the hand-written race**) |
| 3 | SV-003 | `payment.requested` HOLD (+ other) | cancel while `payment.requested` held |
| 7 | SV-003 | DELAY / HOLD / DUPLICATE combinations | cancel right after order |
| 5 | SV-002 + SV-003 | `payment.requested` DUPLICATE (+ other) | cancel right after order |
| 2 | SV-002 | `payment.requested` DUPLICATE (+ DELAY) | no cancel |

- Only **3 of 24** findings are the schedule written by hand in `scenarios/`. The others reach the
  same defects by different routes.
- **4 findings needed no fault at all.** Placing an order and cancelling straight away let the cancel
  win against the normal payment flow, and the naive handler then confirmed the cancelled order. The
  bug is reachable through ordinary timing, not only under injected faults.
- **Cancelling while the payment *request* is held** is a second, independent route to SV-003.

## What this does and does not show

- **A finding is a lead, not a verified reproduction.** Each schedule ran once. I expected findings
  that depend on real timing (for example "no faults, cancel right after order") not to fail every
  time. **Measured later, that was wrong:** it failed 10/10, and shrinking showed the faults were never
  needed. See [shrinking.md](shrinking.md).
- **The search space is small and chosen by hand:** two events, four fault kinds, three customer
  behaviours. Random generation found these defects because they are common in that space (24 of
  60). Rarer defects would need more iterations or smarter generation.
- **Same author:** the generator, the rules and the planted defects were written by one person. The
  routes the explorer found that were not planted in a scenario (no-fault cancel, cancel while the
  request is held) reduce, but do not remove, that concern.

## Found while building it

The first CI run reported **6 INCONCLUSIVE** results in both configurations. The cause was a bug in
the generator, not in the services: some schedules released a held message *before it had
arrived*, so the release did nothing and the message stayed held. StateVerdict reported those runs as
INCONCLUSIVE rather than PASS. Fixed in two places: `Schedule.problems()` now rejects a
`RELEASE_ALL` that is not preceded by an `AWAIT_HELD` for each hold on that service, and the
generator always waits before releasing.
