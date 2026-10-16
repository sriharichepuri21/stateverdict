package dev.stateverdict.examples.order;

import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP API of the order service.
 *
 * <p>{@code GET /orders/{id}} is the read-only, authoritative state API that StateVerdict uses as
 * evidence for order status (spec section 3, "Trust boundaries").
 */
@RestController
@RequestMapping("/orders")
public class OrderController {

    record CreateOrderRequest(long amountCents) {
    }

    private final CheckoutService checkout;
    private final OrderRepository orders;

    public OrderController(CheckoutService checkout, OrderRepository orders) {
        this.checkout = checkout;
        this.orders = orders;
    }

    @PostMapping
    public ResponseEntity<Order> create(@RequestBody CreateOrderRequest request) {
        Order order = checkout.checkout(request.amountCents());
        return ResponseEntity.created(URI.create("/orders/" + order.id())).body(order);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Order> get(@PathVariable UUID id) {
        return ResponseEntity.of(orders.findById(id));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<String> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(e.getMessage());
    }

    @ExceptionHandler(CheckoutService.EventPublishException.class)
    ResponseEntity<String> unavailable(CheckoutService.EventPublishException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(e.getMessage());
    }
}
