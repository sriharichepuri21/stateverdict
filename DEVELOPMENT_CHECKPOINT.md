# Development checkpoint

Update this file at the end of every working session (spec section 12, "Session exit checklist").

## Current state

- **Branch:** `feat/explorer`
- **Phase:** Exploration phase, PR 3 of 4: explorer (gate 2)
- **Last green command:** CI run 38997158713 on PR #11: 126 tests; explore seed 42 × 60: naive FAIL 24 (SV-002, SV-003), fixed PASS 60
- **Last failing command:** CI run 38992685990: 6 INCONCLUSIVE per campaign because generated schedules released holds before the message arrived; fixed in validation and generator

## Spike progress

| # | Step | Status | Evidence |
|---|------|--------|----------|
| 1 | Repo, Compose, two services, Postgres, Kafka, health checks | Done | PR #1 merged; CI green |
| 2 | Normal checkout flow with committed capture | Done | PR #2 merged |
| 3 | Dispatch scheduler: hold, release, duplicate | Done | PR #3 merged |
| 4 | Vulnerable handler + capture-count rule → FAIL | Done | PR #4 merged |
| 5 | Ten clean-state repetitions, measured reproduction | Done | PR #5 merged |
| 6 | Corrected idempotent handler → PASS | Done | PR #6 + #7 merged (landed on main via #7) |
| 7 | CLI, report, docs, one-command reproduction | Done | PR #8 merged |

## Exploration phase (gates 2 and 3)

| # | PR | Status | Evidence |
|---|----|--------|----------|
| 1 | Second event flow: `payment.completed`, customer cancellation, naive/guarded completion handlers | Done | PR #9 merged |
| 2 | Schedules: multi-step runs across both services, rule SV-003 | Done | PR #10 merged |
| 3 | Explorer: seeded generation of valid schedules (gate 2) | Done (awaiting merge) | PR #11: gate 2 met; `docs/results/exploration.md` |
| 4 | Shrinker: dependency-aware reduction confirmed by repeated trials (gate 3) | Not started | |

## Active blocker

None.

## Next action

Merge PR #11, then PR 4: shrinker. Reduce each finding to a locally minimal schedule and measure reproduction with repeated trials; expect timing-dependent findings (no-fault cancel) to reproduce less than 10/10.

## Open questions (spec appendix B)

- [x] Dedicated databases or schemas for the spike? **Decision:** one PostgreSQL server, a separate
  database and user per service (`orders`, `payments`), so each service genuinely owns its data.
- [x] Kafka topology for controlled dispatch in v1: **Decision (ADR-002):** one partition per topic,
  one consumer per group; duplicates are delivered sequentially by the dispatcher.
- [x] Source of committed payment captures: **Decision:** payment-service `GET /captures?orderId=` backed by the `payment_captures` table. Reset boundary still open.
- [ ] Clock model for bounded temporal assertions.
- [x] Definition of a valid schedule: **Decision:** `Schedule.problems()` (one order, arms before it, waits only for armed holds, every hold released). The explorer generates within it; the shrinker must preserve it.
- [ ] Which open-source target, if any, to use for external validation.
