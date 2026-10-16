# Development checkpoint

Update this file at the end of every working session (spec section 12, "Session exit checklist").

## Current state

- **Branch:** `feat/bootstrap`
- **Phase:** Technical spike, step 1 of 7: bootstrap
- **Last green command:** pending first CI run
- **Last failing command:** none yet

## Spike progress

| # | Step | Status | Evidence |
|---|------|--------|----------|
| 1 | Repo, Compose, two services, Postgres, Kafka, health checks | In progress | CI run on PR `feat/bootstrap` |
| 2 | Normal checkout flow with committed capture | Not started | |
| 3 | Dispatch scheduler: hold, release, duplicate | Not started | |
| 4 | Vulnerable handler + capture-count rule → FAIL | Not started | |
| 5 | Ten clean-state repetitions, measured reproduction | Not started | |
| 6 | Corrected idempotent handler → PASS | Not started | |
| 7 | CLI, report, docs, one-command reproduction | Not started | |

## Active blocker

None.

## Next action

Get CI green on `feat/bootstrap`, then merge and start step 2 (normal checkout flow).

## Open questions (spec appendix B)

- [x] Dedicated databases or schemas for the spike? **Decision:** one PostgreSQL server, a separate
  database and user per service (`orders`, `payments`), so each service genuinely owns its data.
- [ ] Kafka partition/consumer topology for strict controlled dispatch in v1
  (current assumption: one partition per topic, one consumer per group).
- [ ] Exact source of committed payment captures and the reset transaction boundary.
- [ ] Clock model for bounded temporal assertions.
- [ ] Definition of a valid, shrinkable schedule dependency graph.
- [ ] Which open-source target, if any, to use for external validation.
