# Event contract: `payment.completed`

Published by **payment-service** once a `payment.requested` event has been decided. Consumed by
**order-service**, which moves the order out of `PENDING`.

| Property | Value |
|---|---|
| Topic | `payment.completed` |
| Partitions | 1 (v1 controlled-dispatch topology, ADR-002) |
| Key | order id (UUID string) |
| Value | JSON, UTF-8 |
| Headers | `correlation-id` (optional): copied from the `payment.requested` record; StateVerdict's run id |
| Delivery guarantee | at least once: consumers **must** expect duplicates |

## Value

```json
{
  "eventId": "8a2b5a0e-1f7d-4b0f-9c62-0f3e0f2d9a41",
  "orderId": "b3f1a8f4-2c5e-4a39-8d0b-7a3c9e6f2d10",
  "requestEventId": "4f0c7b0e-6a0e-4d55-9a53-1e5f3c2d9b11",
  "outcome": "CAPTURED",
  "occurredAt": "2026-10-16T07:40:12.512Z"
}
```

| Field | Meaning |
|---|---|
| `eventId` | Identity of this event. |
| `orderId` | The order the payment was for. |
| `requestEventId` | The `payment.requested` event this answers. |
| `outcome` | `CAPTURED` (money taken, a capture is committed) or `DECLINED` (no capture). The simulated provider declines amounts above `payment.limit-cents`, default 500000. |
| `occurredAt` | When the payment service decided (UTC). |

## Order-service handling

| Current order status | `CAPTURED` | `DECLINED` |
|---|---|---|
| `PENDING` | → `CONFIRMED` | → `CANCELLED` |
| `CANCELLED` by the customer | guarded: stays `CANCELLED` (refund not yet implemented); **naive: → `CONFIRMED` (the defect)** | stays `CANCELLED` |
| `CONFIRMED` | unchanged | guarded: unchanged; naive: → `CANCELLED` |

## Evidence

Order state is read from `GET /orders/{id}`: `status` and `cancelRequestedAt` (set once when the
customer cancels, never cleared).
