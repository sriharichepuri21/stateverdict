-- Inbox of business events the payment service has already processed.
-- Used only by the idempotent capture handler: inserting the event id and the
-- capture in one transaction means a redelivered event is recognised and skipped,
-- even when two copies are processed at the same time.
-- payment_captures itself stays unconstrained, so the naive handler can still
-- demonstrate the double-capture defect.
CREATE TABLE processed_events (
    event_id     UUID        PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
