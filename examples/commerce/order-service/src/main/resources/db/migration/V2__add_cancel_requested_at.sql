-- When the customer asked to cancel the order. Set once, never cleared, so it stays as
-- evidence even if a buggy handler later overwrites the status (rule SV-003).
ALTER TABLE orders ADD COLUMN cancel_requested_at TIMESTAMPTZ NULL;
