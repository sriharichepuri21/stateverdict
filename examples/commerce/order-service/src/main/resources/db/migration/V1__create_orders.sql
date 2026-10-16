-- Orders owned by the order service.
-- Status values follow the checkout state machine in the specification (figure 3).
CREATE TABLE orders (
    id           UUID        PRIMARY KEY,
    status       TEXT        NOT NULL
                 CHECK (status IN ('PENDING', 'RESERVED', 'AUTHORIZED', 'CONFIRMED', 'CANCELLED')),
    amount_cents BIGINT      NOT NULL CHECK (amount_cents > 0),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
