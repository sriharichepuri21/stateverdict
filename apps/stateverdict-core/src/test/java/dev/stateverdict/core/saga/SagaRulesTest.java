package dev.stateverdict.core.saga;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stateverdict.core.evidence.EvidenceBundle;
import dev.stateverdict.core.evidence.EvidenceRecord;
import dev.stateverdict.core.verdict.EvaluationStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SagaRulesTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SETUP =
            "{\"orderId\":1,\"customerId\":7,\"productId\":9,\"price\":100,\"count\":1,\"funds\":1000,\"stock\":10}";

    private static EvidenceBundle bundle(String order, String customer, String product) {
        List<EvidenceRecord> records = new ArrayList<>();
        records.add(record(SagaEvidence.SETUP, SETUP));
        if (order != null) {
            records.add(record(SagaEvidence.ORDER, order));
        }
        if (customer != null) {
            records.add(record(SagaEvidence.CUSTOMER, customer));
        }
        if (product != null) {
            records.add(record(SagaEvidence.PRODUCT, product));
        }
        return new EvidenceBundle("order-1", records);
    }

    private static EvidenceRecord record(String id, String body) {
        return EvidenceRecord.of(id, "test", Instant.EPOCH, body, JSON.readTree(body));
    }

    private static String order(String status) {
        return "{\"id\":1,\"status\":\"" + status + "\"}";
    }

    private static String customer(int available, int reserved) {
        return "{\"id\":7,\"amountAvailable\":" + available + ",\"amountReserved\":" + reserved + "}";
    }

    private static String product(int available, int reserved) {
        return "{\"id\":9,\"availableItems\":" + available + ",\"reservedItems\":" + reserved + "}";
    }

    @Test
    void decidedOrdersSatisfySaga1AndUndecidedOnesViolateIt() {
        OrderDecidedRule rule = new OrderDecidedRule();
        for (String status : List.of("CONFIRMED", "REJECTED", "ROLLBACK")) {
            assertThat(rule.evaluate(bundle(order(status), null, null)).status()).isEqualTo(EvaluationStatus.SATISFIED);
        }
        assertThat(rule.evaluate(bundle(order("NEW"), null, null)).status()).isEqualTo(EvaluationStatus.VIOLATED);
        assertThat(rule.evaluate(bundle("{\"id\":1,\"found\":false}", null, null)).status()).isEqualTo(EvaluationStatus.VIOLATED);
        assertThat(rule.evaluate(bundle(null, null, null)).status()).isEqualTo(EvaluationStatus.UNKNOWN);
        assertThat(rule.eventual()).isTrue();
    }

    @Test
    void aConfirmedOrderTakesThePriceOnceAndLeavesNothingReserved() {
        SettlementRule money = SettlementRule.money();
        assertThat(money.evaluate(bundle(order("CONFIRMED"), customer(900, 0), null)).status())
                .isEqualTo(EvaluationStatus.SATISFIED);
        // charged twice
        var twice = money.evaluate(bundle(order("CONFIRMED"), customer(800, 0), null));
        assertThat(twice.status()).isEqualTo(EvaluationStatus.VIOLATED);
        assertThat(twice.rationale()).contains("available is 800 instead of 900");
        // still reserved
        var stuck = money.evaluate(bundle(order("CONFIRMED"), customer(900, 100), null));
        assertThat(stuck.status()).isEqualTo(EvaluationStatus.VIOLATED);
        assertThat(stuck.rationale()).contains("100 money left reserved");
    }

    @Test
    void aRolledBackOrderTakesNothingAndAnythingReturnedThatWasNeverReservedIsNamed() {
        SettlementRule money = SettlementRule.money();
        assertThat(money.evaluate(bundle(order("ROLLBACK"), customer(1000, 0), null)).status())
                .isEqualTo(EvaluationStatus.SATISFIED);
        var created = money.evaluate(bundle(order("ROLLBACK"), customer(1100, -100), null));
        assertThat(created.status()).isEqualTo(EvaluationStatus.VIOLATED);
        assertThat(created.rationale()).contains("negative (-100)", "100 more money than it started with");
    }

    @Test
    void stockIsJudgedTheSameWayOnItsOwnFields() {
        SettlementRule stock = SettlementRule.stock();
        assertThat(stock.id()).isEqualTo("SAGA-3");
        assertThat(stock.evaluate(bundle(order("CONFIRMED"), null, product(9, 0))).status()).isEqualTo(EvaluationStatus.SATISFIED);
        assertThat(stock.evaluate(bundle(order("CONFIRMED"), null, product(9, -1))).status()).isEqualTo(EvaluationStatus.VIOLATED);
    }

    @Test
    void settlementCannotBeJudgedBeforeTheOrderIsDecidedOrWithoutEvidence() {
        SettlementRule money = SettlementRule.money();
        assertThat(money.evaluate(bundle(order("NEW"), customer(900, 100), null)).status()).isEqualTo(EvaluationStatus.UNKNOWN);
        assertThat(money.evaluate(bundle(order("CONFIRMED"), null, null)).status()).isEqualTo(EvaluationStatus.UNKNOWN);
        assertThat(money.eventual()).isTrue();
    }
}
