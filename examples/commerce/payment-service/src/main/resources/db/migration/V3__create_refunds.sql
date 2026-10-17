-- Refunds of committed captures. At most one refund per capture (UNIQUE capture_id),
-- so a redelivered or repeated refund request can never refund twice.
CREATE TABLE refunds (
    id           UUID        PRIMARY KEY,
    capture_id   UUID        NOT NULL UNIQUE REFERENCES payment_captures (id),
    order_id     UUID        NOT NULL,
    amount_cents BIGINT      NOT NULL CHECK (amount_cents > 0),
    refunded_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_refunds_order_id ON refunds (order_id);
