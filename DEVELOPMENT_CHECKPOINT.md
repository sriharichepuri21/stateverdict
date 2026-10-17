# Development checkpoint

Update this file at the end of every working session (spec section 12, "Session exit checklist").

## Current state

- **Branch:** `feat/second-event-flow`
- **Phase:** Exploration phase, PR 1 of 4: second event flow (payment.completed, cancellation)
- **Last green command:** CI run 38989299928 on PR #9: 90 tests, 0 failures; demo OK; reproduction unchanged (naive duplicate FAIL 10/10, all others PASS 10/10)
- **Last failing command:** CI run 38946344615: idempotent trials ERROR 10/10 because trials started before the restarted service was ready; fixed with `scripts/wait-healthy.sh`

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
| 1 | Second event flow: `payment.completed`, customer cancellation, naive/guarded completion handlers | Done (awaiting merge) | PR #9: 90 tests; naive handler confirms a cancelled order under the race (pinned by test) |
| 2 | Schedules: multi-step runs across both services, rule SV-003 | Not started | |
| 3 | Explorer: seeded generation of valid schedules (gate 2) | Not started | |
| 4 | Shrinker: dependency-aware reduction confirmed by repeated trials (gate 3) | Not started | |

## Active blocker

None.

## Next action

Merge PR #9, then PR 2: schedule runner that drives both services' dispatchers, resets both per run, and rule SV-003 (a customer-cancelled order never ends CONFIRMED).

## Open questions (spec appendix B)

- [x] Dedicated databases or schemas for the spike? **Decision:** one PostgreSQL server, a separate
  database and user per service (`orders`, `payments`), so each service genuinely owns its data.
- [x] Kafka topology for controlled dispatch in v1: **Decision (ADR-002):** one partition per topic,
  one consumer per group; duplicates are delivered sequentially by the dispatcher.
- [x] Source of committed payment captures: **Decision:** payment-service `GET /captures?orderId=` backed by the `payment_captures` table. Reset boundary still open.
- [ ] Clock model for bounded temporal assertions.
- [ ] Definition of a valid, shrinkable schedule dependency graph.
- [ ] Which open-source target, if any, to use for external validation.
