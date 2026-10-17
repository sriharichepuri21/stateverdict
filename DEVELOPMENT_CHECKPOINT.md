# Development checkpoint

Update this file at the end of every working session (spec section 12, "Session exit checklist").

## Current state

- **Branch:** `feat/refunds`
- **Phase:** Closing evaluation gaps: refunds and SV-004 (M04)
- **Last green command:** CI 39048156582 + evaluation 39048156513 on PR #15: 159 tests; fixed explore PASS 60; StateVerdict 5/6 defects, 0/3 false alarms
- **Last failing command:** PR #15 first run: a test stub short-circuited its lookup counter, and the shrink selection silently matched nothing; both fixed

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
| 4 | Shrinker: dependency-aware reduction confirmed by repeated trials (gate 3) | Done | PR #12 merged |

## Gate 4: evaluation

| # | PR | Status | Evidence |
|---|----|--------|----------|
| 1 | Defect corpus (M01–M06, C01–C03) and SV-001 | Done | PR #13 merged |
| 2 | Head-to-head in CI | Done | PR #14 merged |
| 3 | Refund flow, SV-004, cancel-after-confirmed (closes M04) | Done (awaiting merge) | PR #15: 5/6 defects, 0/3 false alarms |
| 4 | Concurrent delivery (closes M03) | Not started | |

## Active blocker

None.

## Next action

Merge PR #15, then a DUPLICATE_CONCURRENT intervention in the dispatcher and generator; rerun the evaluation, expect M03 caught.

## Open questions (spec appendix B)

- [x] Dedicated databases or schemas for the spike? **Decision:** one PostgreSQL server, a separate
  database and user per service (`orders`, `payments`), so each service genuinely owns its data.
- [x] Kafka topology for controlled dispatch in v1: **Decision (ADR-002):** one partition per topic,
  one consumer per group; duplicates are delivered sequentially by the dispatcher.
- [x] Source of committed payment captures: **Decision:** payment-service `GET /captures?orderId=` backed by the `payment_captures` table. Reset boundary still open.
- [ ] Clock model for bounded temporal assertions.
- [x] Definition of a valid schedule: **Decision:** `Schedule.problems()` (one order, arms before it, waits only for armed holds, every hold released). The explorer generates within it; the shrinker must preserve it.
- [ ] Which open-source target, if any, to use for external validation.
