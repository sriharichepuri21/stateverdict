<!-- Draft for the target's maintainer. To be posted by a person, from their own account, after review. -->

**Title:** A rejected order corrupts the balance of the service that rejected it (source case mismatch)

Hi, and thanks for this example; it's a really clear demo of the saga pattern with Kafka Streams.

I think I found a small bug in the rollback path. When an order is rolled back, the order service sets
`source` to `"PAYMENT"` or `"STOCK"` (`order-service/.../OrderManageService.java`):

```java
String source = orderPayment.getStatus().equals("REJECT") ? "PAYMENT" : "STOCK";
```

The payment and stock services compare it with lower-case constants (`SOURCE = "payment"` / `"stock"`):

```java
} else if (order.getStatus().equals("ROLLBACK") && !order.getSource().equals(SOURCE)) {
```

Because the comparison never matches, **both** services undo a reservation, including the one that
rejected the order and never reserved anything.

**To reproduce** (commit a76daeb):
1. Start the stack and place an order the customer cannot afford, but whose product is in stock. For
   example, `{"customerId": 1, "productId": 1, "productCount": 1, "price": 20000, "status": "NEW"}`
   (customer balances are generated between 200 and 10000).
2. The order ends in `ROLLBACK` with source `PAYMENT`.
3. Customer 1 now has `amountAvailable` increased by 20000 and `amountReserved` = -20000. You can see
   this in the payment service's next `Found: Customer{...}` log line for that customer.

The mirror case (stock rejects, payment accepts) makes the product's `availableItems` grow and
`reservedItems` go negative.

**Possible fix:** use the same constants on both sides, or compare with
`equalsIgnoreCase(SOURCE)`.

I found this while testing my project StateVerdict against your example. It reproduced on 10 of
10 runs with fresh accounts:
https://github.com/sriharichepuri21/stateverdict/blob/main/targets/piomin-saga/RESULTS.md

In the same runs, a redelivered `orders` message charges a customer twice (reserve and confirm aren't
idempotent), and a payment reply delayed past the 10 s join window leaves the order `NEW` with its
reservations held. I assume those are deliberate simplifications for a demo, but I'm happy to open a
PR for either if you'd like.
