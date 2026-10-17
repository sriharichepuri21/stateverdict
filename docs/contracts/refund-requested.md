# Event contract: `refund.requested`

Published by **order-service** when a `payment.completed: CAPTURED` arrives for an order the customer
has already cancelled. Consumed by **payment-service**, which refunds every capture of the order.

| Property | Value |
|---|---|
| Topic | `refund.requested` |
| Partitions | 1 (ADR-002) |
| Key | order id (UUID string) |
| Value | JSON, UTF-8 |
| Headers | `correlation-id` (optional): copied from the `payment.completed` record |
| Delivery guarantee | at least once; refunds are idempotent per capture (`refunds.capture_id` is unique) |

```json
{
  "eventId": "0b7c6e1a-3f6d-4b8e-9d0e-2f1a5c7b9e44",
  "orderId": "b3f1a8f4-2c5e-4a39-8d0b-7a3c9e6f2d10",
  "reason": "captured after customer cancellation",
  "occurredAt": "2026-10-17T16:02:11.120Z"
}
```

Committed refunds are read from payment-service `GET /refunds?orderId=` (evidence for rule SV-004).
A request for an order with no capture refunds nothing; the order service only sends one after it
has received CAPTURED, which the payment service publishes after the capture commits.
