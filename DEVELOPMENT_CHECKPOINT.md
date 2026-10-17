# Development checkpoint

Update this file at the end of every working session (spec section 12, "Session exit checklist").

## Current state

- **Branch:** `feat/one-command-demo`
- **Phase:** Technical spike, spike complete (step 7 of 7: one-command reproduction, README, retrospective)
- **Last green command:** CI run 38946710200 on PR #6: 81 tests, 0 failures; naive: baseline PASS 10/10, duplicate FAIL 10/10; idempotent: baseline PASS 10/10, duplicate PASS 10/10
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
| 7 | CLI, report, docs, one-command reproduction | Done (awaiting merge) | PR #8: `scripts/demo.sh` passes all 4 steps in CI with Docker only; README; retrospective |

## Active blocker

None.

## Next action

Merge PR #8. Spike done. Next (see docs/results/spike-retrospective.md): mutation corpus + JUnit baseline (Gate 4), generated fault schedules (Gate 2), dependency-aware shrinking (Gate 3).

## Open questions (spec appendix B)

- [x] Dedicated databases or schemas for the spike? **Decision:** one PostgreSQL server, a separate
  database and user per service (`orders`, `payments`), so each service genuinely owns its data.
- [x] Kafka topology for controlled dispatch in v1: **Decision (ADR-002):** one partition per topic,
  one consumer per group; duplicates are delivered sequentially by the dispatcher.
- [x] Source of committed payment captures: **Decision:** payment-service `GET /captures?orderId=` backed by the `payment_captures` table. Reset boundary still open.
- [ ] Clock model for bounded temporal assertions.
- [ ] Definition of a valid, shrinkable schedule dependency graph.
- [ ] Which open-source target, if any, to use for external validation.
