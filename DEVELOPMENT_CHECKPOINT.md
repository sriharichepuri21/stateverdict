# Development checkpoint

Update this file at the end of every working session (spec section 12, "Session exit checklist").

## Current state

- **Branch:** `feat/core-verdict`
- **Phase:** Technical spike, step 4 of 7: first verdict (core, SV-002, CLI)
- **Last green command:** CI on PR #3: `mvn -B verify` (42 tests, 0 failures), `scripts/checkout-smoke.sh` and `scripts/duplicate-delivery-demo.sh` against the Compose stack, all green
- **Last failing command:** none yet

## Spike progress

| # | Step | Status | Evidence |
|---|------|--------|----------|
| 1 | Repo, Compose, two services, Postgres, Kafka, health checks | Done | PR #1 merged; CI green |
| 2 | Normal checkout flow with committed capture | Done | PR #2 merged |
| 3 | Dispatch scheduler: hold, release, duplicate | Done | PR #3 merged |
| 4 | Vulnerable handler + capture-count rule → FAIL | In review | PR #4 (pending CI) |
| 5 | Ten clean-state repetitions, measured reproduction | Not started | |
| 6 | Corrected idempotent handler → PASS | Not started | |
| 7 | CLI, report, docs, one-command reproduction | Not started | |

## Active blocker

None.

## Next action

Get PR #4 green and merged, then step 5: run the duplicate scenario ten times from clean state and record the real reproduction frequency, UNKNOWN and ERROR counts.

## Open questions (spec appendix B)

- [x] Dedicated databases or schemas for the spike? **Decision:** one PostgreSQL server, a separate
  database and user per service (`orders`, `payments`), so each service genuinely owns its data.
- [x] Kafka topology for controlled dispatch in v1: **Decision (ADR-002):** one partition per topic,
  one consumer per group; duplicates are delivered sequentially by the dispatcher.
- [x] Source of committed payment captures: **Decision:** payment-service `GET /captures?orderId=` backed by the `payment_captures` table. Reset boundary still open.
- [ ] Clock model for bounded temporal assertions.
- [ ] Definition of a valid, shrinkable schedule dependency graph.
- [ ] Which open-source target, if any, to use for external validation.
