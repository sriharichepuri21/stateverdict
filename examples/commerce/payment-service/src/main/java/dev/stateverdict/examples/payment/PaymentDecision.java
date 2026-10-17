package dev.stateverdict.examples.payment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Simulated payment provider: approves amounts up to a limit and declines anything above it.
 * Deterministic on purpose, so scenarios can choose the outcome through the order amount.
 */
@Component
public class PaymentDecision {

    private final long limitCents;

    public PaymentDecision(@Value("${payment.limit-cents:500000}") long limitCents) {
        this.limitCents = limitCents;
    }

    public String decide(long amountCents) {
        return amountCents <= limitCents ? PaymentCompleted.CAPTURED : PaymentCompleted.DECLINED;
    }
}
