-- Committed payment captures: the authoritative evidence for rule SV-002
-- ("at most one successful capture per business order").
--
-- There is deliberately NO unique constraint on order_id or event_id here.
-- Idempotency is the application's job, and the vulnerable and corrected
-- handler variants added later must be able to differ in exactly that behaviour.
CREATE TABLE payment_captures (
    id           UUID        PRIMARY KEY,
    order_id     UUID        NOT NULL,
    event_id     UUID        NOT NULL,
    amount_cents BIGINT      NOT NULL CHECK (amount_cents > 0),
    captured_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_payment_captures_order_id ON payment_captures (order_id);
