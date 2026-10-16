# Event contract: `payment.requested`

Published by **order-service** when a checkout starts. Consumed by **payment-service**.

| Property | Value |
|---|---|
| Topic | `payment.requested` |
| Partitions | 1 (v1 controlled-dispatch topology, spec section 4) |
| Key | order id (UUID string), so all events for one order stay in order |
| Value | JSON, UTF-8 |
| Headers | `correlation-id` (optional): copied from the `X-Correlation-Id` HTTP header of `POST /orders`; StateVerdict uses it as the run id |
| Delivery guarantee | at least once: consumers **must** expect duplicates |

## Value

```json
{
  "eventId": "4f0c7b0e-6a0e-4d55-9a53-1e5f3c2d9b11",
  "orderId": "b3f1a8f4-2c5e-4a39-8d0b-7a3c9e6f2d10",
  "amountCents": 129900,
  "occurredAt": "2026-10-16T07:40:12.345Z"
}
```

| Field | Meaning |
|---|---|
| `eventId` | Identity of this business event. A redelivery carries the **same** `eventId`; this is what makes duplicates detectable. |
| `orderId` | The order to be paid. |
| `amountCents` | Amount in cents; always positive. |
| `occurredAt` | When the order service created the event (UTC). |

## Evidence

The committed outcome of consuming this event is read from payment-service's state API,
`GET /captures?orderId=<id>`, never from logs or traces (spec ADR-003).
