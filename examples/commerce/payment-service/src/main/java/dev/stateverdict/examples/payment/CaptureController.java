package dev.stateverdict.examples.payment;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only, authoritative state API for committed captures. StateVerdict reads this as evidence;
 * it never infers a capture from logs or traces (spec ADR-003).
 */
@RestController
public class CaptureController {

    record CapturesView(UUID orderId, int count, List<Capture> captures) {
    }

    private final CaptureRepository captures;

    public CaptureController(CaptureRepository captures) {
        this.captures = captures;
    }

    @GetMapping("/captures")
    public CapturesView byOrder(@RequestParam UUID orderId) {
        List<Capture> found = captures.findByOrderId(orderId);
        return new CapturesView(orderId, found.size(), found);
    }
}
