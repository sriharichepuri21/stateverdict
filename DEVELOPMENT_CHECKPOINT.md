# Development checkpoint

Update this file at the end of every working session (spec section 12, "Session exit checklist").

## Current state

- **Branch:** `feat/repetitions`
- **Phase:** Technical spike, step 5 of 7: measured reproduction
- **Last green command:** CI run 38944476200 on PR #5: 77 tests, 0 failures; `scripts/measure-reproduction.sh 10`: baseline PASS 10/10, duplicate FAIL 10/10
- **Last failing command:** none yet

## Spike progress

| # | Step | Status | Evidence |
|---|------|--------|----------|
| 1 | Repo, Compose, two services, Postgres, Kafka, health checks | Done | PR #1 merged; CI green |
| 2 | Normal checkout flow with committed capture | Done | PR #2 merged |
| 3 | Dispatch scheduler: hold, release, duplicate | Done | PR #3 merged |
| 4 | Vulnerable handler + capture-count rule → FAIL | Done | PR #4 merged |
| 5 | Ten clean-state repetitions, measured reproduction | Done (awaiting merge) | PR #5: baseline PASS 10/10, duplicate FAIL 10/10; `docs/results/spike-reproduction.md` |
| 6 | Corrected idempotent handler → PASS | Not started | |
| 7 | CLI, report, docs, one-command reproduction | Not started | |

## Active blocker

None.

## Next action

Merge PR #5, then step 6: add the idempotent payment handler and re-measure; duplicate should flip to PASS 10/10 with the baseline unchanged.

## Open questions (spec appendix B)

- [x] Dedicated databases or schemas for the spike? **Decision:** one PostgreSQL server, a separate
  database and user per service (`orders`, `payments`), so each service genuinely owns its data.
- [x] Kafka topology for controlled dispatch in v1: **Decision (ADR-002):** one partition per topic,
  one consumer per group; duplicates are delivered sequentially by the dispatcher.
- [x] Source of committed payment captures: **Decision:** payment-service `GET /captures?orderId=` backed by the `payment_captures` table. Reset boundary still open.
- [ ] Clock model for bounded temporal assertions.
- [ ] Definition of a valid, shrinkable schedule dependency graph.
- [ ] Which open-source target, if any, to use for external validation.
