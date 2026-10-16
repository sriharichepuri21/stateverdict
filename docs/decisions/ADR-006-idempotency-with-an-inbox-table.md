# ADR-006: The payment service deduplicates with an inbox table, not a unique constraint on captures

- **Status:** Accepted
- **Date:** 2026-10-16

## Context

Kafka delivers at least once, so `payment.requested` can arrive more than once. The payment service
must commit at most one capture per event. Options:

| Option | Stops redelivered events? | Stops concurrent copies? | Keeps a demonstrable naive variant? |
|---|---|---|---|
| Check "already captured?" with a `SELECT`, then insert | Yes, sequentially | **No**: both copies can pass the check before either inserts | Yes |
| Unique constraint on `payment_captures.event_id` | Yes | Yes | **No**: the naive handler would also be protected |
| **Inbox table `processed_events(event_id PK)`, written in the same transaction as the capture** | Yes | Yes | Yes |

## Decision

`IdempotentCaptureHandler` runs one transaction:

1. `INSERT INTO processed_events (event_id) VALUES (?) ON CONFLICT DO NOTHING`
2. Only if that inserted a row, insert the capture.

Both writes commit together or not at all. If two copies run concurrently, PostgreSQL makes the
second `INSERT` wait for the first transaction, then skip. A test with 8 concurrent copies verifies
exactly one capture.

`payment.capture.handler` selects `idempotent` (the default) or `naive`, which is kept only so
StateVerdict has a real defect to detect.

## Consequences

- Deduplication is by **event id**. Two *different* events for the same order would both capture.
  That would be an upstream bug, and rule SV-002 still reports it because it counts captures per
  order; a test documents this scope.
- `processed_events` grows by one row per event. A retention policy is needed in a real system and
  is out of scope here.
- In production code a unique constraint on captures would be a sensible extra safety net. It is
  left out here only to keep the naive variant demonstrable.
