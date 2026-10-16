# Development checkpoint

Update this file at the end of every working session (spec section 12, "Session exit checklist").

## Current state

- **Branch:** `feat/checkout-flow`
- **Phase:** Technical spike, step 2 of 7: normal checkout flow
- **Last green command:** CI on PR #2: `mvn -B verify` (10 tests, 0 failures), Compose smoke test and `scripts/checkout-smoke.sh` (one order → one capture), all green
- **Last failing command:** none yet

## Spike progress

| # | Step | Status | Evidence |
|---|------|--------|----------|
| 1 | Repo, Compose, two services, Postgres, Kafka, health checks | Done | PR #1 merged; CI green |
| 2 | Normal checkout flow with committed capture | Done (awaiting merge) | PR #2: 10 tests + end-to-end one-order/one-capture check |
| 3 | Dispatch scheduler: hold, release, duplicate | Not started | |
| 4 | Vulnerable handler + capture-count rule → FAIL | Not started | |
| 5 | Ten clean-state repetitions, measured reproduction | Not started | |
| 6 | Corrected idempotent handler → PASS | Not started | |
| 7 | CLI, report, docs, one-command reproduction | Not started | |

## Active blocker

None.

## Next action

Merge PR #2, then start step 3 (test-mode dispatch scheduler: hold, release, duplicate) on `feat/dispatch-scheduler`.

## Open questions (spec appendix B)

- [x] Dedicated databases or schemas for the spike? **Decision:** one PostgreSQL server, a separate
  database and user per service (`orders`, `payments`), so each service genuinely owns its data.
- [ ] Kafka partition/consumer topology for strict controlled dispatch in v1
  (current assumption: one partition per topic, one consumer per group).
- [x] Source of committed payment captures: **Decision:** payment-service `GET /captures?orderId=` backed by the `payment_captures` table. Reset boundary still open.
- [ ] Clock model for bounded temporal assertions.
- [ ] Definition of a valid, shrinkable schedule dependency graph.
- [ ] Which open-source target, if any, to use for external validation.
