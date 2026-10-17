# Development checkpoint

Update this file at the end of every working session (spec section 12, "Session exit checklist").

## Current state

- **Branch:** `feat/explore-external-target`
- **Phase:** External validation: piomin/sample-spring-kafka-microservices
- **Last green command:** CI 39072498345 on PR #18: 184 tests; explore-saga seed 42 FAIL 45/60; four shrinks to one cause each, 10/10
- **Last failing command:** none on PR #16

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
| 3 | Refund flow, SV-004, cancel-after-confirmed (closes M04) | Done | PR #15 merged |
| 4 | Concurrent delivery (closes M03) | Done | PR #16 merged |

## External validation

| # | PR | Status | Evidence |
|---|----|--------|----------|
| 1 | piomin saga: adapter, saga runner, SAGA-1..3, hand-written schedules | Done | PR #17 merged |
| 2 | Generic explorer and shrinker (ScheduleSpace), explored and shrunk against the target | Done (awaiting merge) | PR #18: 45/60 FAIL; 4 minimal causes, 10/10; one unpredicted |

## Active blocker

None.

## Next action

Merge PR #18. Owner to review and post targets/piomin-saga/ISSUE_DRAFT.md when ready. Candidates next: a recruiter-facing README and demo pass; a second external target (Eventuate Tram).

## Open questions (spec appendix B)

- [x] Dedicated databases or schemas for the spike? **Decision:** one PostgreSQL server, a separate
  database and user per service (`orders`, `payments`), so each service genuinely owns its data.
- [x] Kafka topology for controlled dispatch in v1: **Decision (ADR-002):** one partition per topic,
  one consumer per group; duplicates are delivered sequentially by the dispatcher.
- [x] Source of committed payment captures: **Decision:** payment-service `GET /captures?orderId=` backed by the `payment_captures` table. Reset boundary still open.
- [ ] Clock model for bounded temporal assertions.
- [x] Definition of a valid schedule: **Decision:** `Schedule.problems()` (one order, arms before it, waits only for armed holds, every hold released). The explorer generates within it; the shrinker must preserve it.
- [x] Which open-source target to use for external validation: **piomin/sample-spring-kafka-microservices** (PR #17).
