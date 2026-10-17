package dev.stateverdict.examples.payment;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only, authoritative state API for committed refunds; StateVerdict reads it as evidence. */
@RestController
public class RefundController {

    record RefundsView(UUID orderId, int count, List<Refund> refunds) {
    }

    private final RefundRepository refunds;

    public RefundController(RefundRepository refunds) {
        this.refunds = refunds;
    }

    @GetMapping("/refunds")
    public RefundsView byOrder(@RequestParam UUID orderId) {
        List<Refund> found = refunds.findByOrderId(orderId);
        return new RefundsView(orderId, found.size(), found);
    }
}
