# ADR-002: Fault injection happens in a dispatcher after Kafka consumption, not inside Kafka

- **Status:** Accepted
- **Date:** 2026-10-16

## Context

StateVerdict needs to make specific, repeatable delivery faults happen: deliver this event twice,
hold that one, release them in a different order. These faults must be exactly reproducible, or a
"minimal failing schedule" means nothing.

Options for where to inject them:

| Option | Can it duplicate / hold / reorder a *specific* event? | Reproducible? |
|---|---|---|
| Network proxy (Toxiproxy) between service and broker | No. It sees TCP bytes, not events; it can delay or cut a connection | Timing-dependent |
| Broker-level tricks (rewinding offsets, restarting consumers) | Partly; redelivers whole batches, not one chosen event | Depends on commit timing |
| **Dispatcher between the consumer and the business handler** | Yes, per event | Yes, for sequential deliveries |

## Decision

Every consumed record passes through a `Dispatcher` before the business handler runs
(`libs/stateverdict-dispatch`):

- **`DirectDispatcher`** (production, the default): calls the handler once. No behaviour change.
- **`ControlledDispatcher`** (test mode only, `stateverdict.dispatch.mode=controlled`): applies
  interventions armed for a run (`HOLD`, `DUPLICATE`, `DELAY`) and journals every delivery with a
  delivery id, attempt number and handler outcome.

Messages are matched to runs through a `correlation-id` Kafka header, copied from the
`X-Correlation-Id` HTTP header of the request that started the workflow. Messages without it are
never touched.

The library is plain Java with no framework dependency; each service wires it in a few lines.

## Consequences

- Duplicate copies are delivered **sequentially**, so "the same event, delivered twice" is exactly
  reproducible. This does **not** model two copies processed concurrently by competing consumers;
  that race needs a different mechanism and is out of scope for v1 (spec section 4).
- In controlled mode handler failures are recorded, not rethrown, so the broker never adds
  redeliveries StateVerdict did not ask for. Consequence: controlled mode is unsuitable for anything
  but test environments, which the startup warning and the default of `direct` enforce.
- Held messages live in memory and are lost if the service restarts. Acceptable for tests; a reset
  reports how many were discarded.
- The control API (`/_stateverdict/dispatch/...`) only exists in controlled mode; in direct mode
  those routes return 404, which an integration test verifies.
- Interventions apply at the consumer, so they say nothing about broker-side behaviour (partition
  rebalances, broker failover). That is a documented limit, not a hidden one.
