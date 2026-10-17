package dev.stateverdict.core.saga;

import dev.stateverdict.core.evidence.EvidenceBundle;
import dev.stateverdict.core.evidence.EvidenceRecord;
import dev.stateverdict.core.rules.Rule;
import dev.stateverdict.core.rules.RuleResult;
import dev.stateverdict.core.verdict.EvaluationStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * Once the order is decided, the side's balance is exactly what the decision says:
 * <ul>
 *   <li>CONFIRMED: the order's amount was taken once, and nothing is left reserved.</li>
 *   <li>REJECTED or ROLLBACK: nothing was taken, and nothing is left reserved.</li>
 * </ul>
 * The run uses a fresh customer and product, so the starting balance is known and no other order
 * touches it. Two instances: SAGA-2 for the customer's money, SAGA-3 for the product's stock.
 * Eventual: settlement happens after the decision, so the runner re-observes within its window.
 */
public final class SettlementRule implements Rule {

    public static final String MONEY_ID = "SAGA-2";
    public static final String STOCK_ID = "SAGA-3";

    private final String id;
    private final String description;
    private final String evidenceId;
    private final String availableField;
    private final String reservedField;
    private final String startField;
    private final String amountField;
    private final String unit;

    private SettlementRule(String id, String description, String evidenceId, String availableField,
                           String reservedField, String startField, String amountField, String unit) {
        this.id = id;
        this.description = description;
        this.evidenceId = evidenceId;
        this.availableField = availableField;
        this.reservedField = reservedField;
        this.startField = startField;
        this.amountField = amountField;
        this.unit = unit;
    }

    public static SettlementRule money() {
        return new SettlementRule(MONEY_ID, "The customer's money settles as the order was decided",
                SagaEvidence.CUSTOMER, "amountAvailable", "amountReserved", "funds", "price", "money");
    }

    public static SettlementRule stock() {
        return new SettlementRule(STOCK_ID, "The product's stock settles as the order was decided",
                SagaEvidence.PRODUCT, "availableItems", "reservedItems", "stock", "count", "items");
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public int version() {
        return 1;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public boolean eventual() {
        return true;
    }

    @Override
    public RuleResult evaluate(EvidenceBundle evidence) {
        List<String> refs = List.of(SagaEvidence.SETUP, SagaEvidence.ORDER, evidenceId);
        Optional<EvidenceRecord> setup = evidence.find(SagaEvidence.SETUP);
        Optional<EvidenceRecord> order = evidence.find(SagaEvidence.ORDER);
        Optional<EvidenceRecord> side = evidence.find(evidenceId);
        if (setup.isEmpty() || order.isEmpty() || side.isEmpty()) {
            return result(EvaluationStatus.UNKNOWN, "?", "not observed",
                    "The run's setup, order or " + unit + " could not be read.", refs);
        }
        String status = order.get().body().path("status").asString("");
        if (!SagaEvidence.decided(status)) {
            return result(EvaluationStatus.UNKNOWN, "?", "order not decided",
                    "The order is not decided (" + (status.isEmpty() ? "no status" : status)
                            + "), so the final " + unit + " cannot be judged; see SAGA-1.", refs);
        }
        Long start = SagaEvidence.number(setup.get().body().path(startField));
        Long amount = SagaEvidence.number(setup.get().body().path(amountField));
        Long available = SagaEvidence.number(side.get().body().path(availableField));
        Long reserved = SagaEvidence.number(side.get().body().path(reservedField));
        if (start == null || amount == null || available == null || reserved == null) {
            return result(EvaluationStatus.UNKNOWN, "?", "unreadable", "The " + unit + " balances could not be read.", refs);
        }
        boolean confirmed = "CONFIRMED".equals(status);
        long expectedAvailable = start - (confirmed ? amount : 0);
        String expected = "available " + expectedAvailable + ", reserved 0";
        String observed = "available " + available + ", reserved " + reserved;
        if (available == expectedAvailable && reserved == 0) {
            return result(EvaluationStatus.SATISFIED, expected, observed,
                    "Order " + status + ": " + (confirmed ? amount + " " + unit + " taken once" : "nothing taken")
                            + ", nothing left reserved.", refs);
        }
        List<String> why = new ArrayList<>();
        if (reserved > 0) {
            why.add(reserved + " " + unit + " left reserved for an order that is already " + status);
        }
        if (reserved < 0) {
            why.add("the reservation is negative (" + reserved + "): more was released than was ever reserved");
        }
        if (available > start) {
            why.add("it ended with " + (available - start) + " more " + unit + " than it started with");
        } else if (available != expectedAvailable) {
            why.add("available is " + available + " instead of " + expectedAvailable);
        }
        return result(EvaluationStatus.VIOLATED, expected, observed,
                "Order " + status + " (" + amountField + " " + amount + ", started with " + start + "): "
                        + String.join("; ", why) + ".", refs);
    }

    private RuleResult result(EvaluationStatus status, String expected, String observed, String rationale,
                              List<String> refs) {
        return new RuleResult(id, version(), description, status, expected, observed, rationale, refs);
    }
}
