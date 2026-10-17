# Development checkpoint

Update this file at the end of every working session (spec section 12, "Session exit checklist").

## Current state

- **Branch:** `feat/shrinker`
- **Phase:** Exploration phase, PR 4 of 4: shrinker (gate 3)
- **Last green command:** CI run 39005252543 on PR #12: 138 tests; shrink: SV-002 3→1 interventions, SV-003 3→1 (place, cancel), all 10/10
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
| 3 | Explorer: seeded generation of valid schedules (gate 2) | Done | PR #11 merged (squash) |
| 4 | Shrinker: dependency-aware reduction confirmed by repeated trials (gate 3) | Done (awaiting merge) | PR #12: gate 3 met; `docs/results/shrinking.md` |

## Active blocker

None.

## Next action

Merge PR #12. Exploration phase complete (gates 2 and 3 met). Next candidates: refund rule (cancelled + captured must be refunded), mutation corpus + JUnit baseline (gate 4), external target application.

## Open questions (spec appendix B)

- [x] Dedicated databases or schemas for the spike? **Decision:** one PostgreSQL server, a separate
  database and user per service (`orders`, `payments`), so each service genuinely owns its data.
- [x] Kafka topology for controlled dispatch in v1: **Decision (ADR-002):** one partition per topic,
  one consumer per group; duplicates are delivered sequentially by the dispatcher.
- [x] Source of committed payment captures: **Decision:** payment-service `GET /captures?orderId=` backed by the `payment_captures` table. Reset boundary still open.
- [ ] Clock model for bounded temporal assertions.
- [x] Definition of a valid schedule: **Decision:** `Schedule.problems()` (one order, arms before it, waits only for armed holds, every hold released). The explorer generates within it; the shrinker must preserve it.
- [ ] Which open-source target, if any, to use for external validation.
