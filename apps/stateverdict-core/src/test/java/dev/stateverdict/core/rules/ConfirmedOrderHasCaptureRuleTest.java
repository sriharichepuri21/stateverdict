package dev.stateverdict.core.rules;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stateverdict.core.evidence.EvidenceBundle;
import dev.stateverdict.core.evidence.EvidenceRecord;
import dev.stateverdict.core.verdict.EvaluationStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ConfirmedOrderHasCaptureRuleTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ConfirmedOrderHasCaptureRule rule = new ConfirmedOrderHasCaptureRule();

    private static EvidenceBundle evidence(String status, Integer captures) {
        List<EvidenceRecord> records = new ArrayList<>();
        if (status != null) {
            String order = "{\"id\":\"o-1\",\"status\":\"" + status + "\"}";
            records.add(EvidenceRecord.of("order.state", "test", Instant.EPOCH, order, JSON.readTree(order)));
        }
        if (captures != null) {
            StringBuilder list = new StringBuilder("[");
            for (int i = 0; i < captures; i++) {
                list.append(i == 0 ? "" : ",").append("{\"eventId\":\"e\"}");
            }
            String body = "{\"orderId\":\"o-1\",\"captures\":" + list.append("]") + "}";
            records.add(EvidenceRecord.of("payment.captures", "test", Instant.EPOCH, body, JSON.readTree(body)));
        }
        return new EvidenceBundle("run-1", records);
    }

    @Test
    void confirmedOrderWithoutACaptureIsAViolation() {
        RuleResult result = rule.evaluate(evidence("CONFIRMED", 0));

        assertThat(result.status()).isEqualTo(EvaluationStatus.VIOLATED);
        assertThat(result.evidenceRefs()).containsExactly("order.state", "payment.captures");
    }

    @Test
    void confirmedAndPaidSatisfiesTheRule() {
        assertThat(rule.evaluate(evidence("CONFIRMED", 1)).status()).isEqualTo(EvaluationStatus.SATISFIED);
    }

    @Test
    void anOrderThatIsNotConfirmedSatisfiesTheRule() {
        assertThat(rule.evaluate(evidence("CANCELLED", 0)).status()).isEqualTo(EvaluationStatus.SATISFIED);
        assertThat(rule.evaluate(evidence("PENDING", null)).status()).isEqualTo(EvaluationStatus.SATISFIED);
    }

    @Test
    void missingEvidenceIsUnknownNeverSatisfied() {
        assertThat(rule.evaluate(evidence(null, 1)).status()).isEqualTo(EvaluationStatus.UNKNOWN);
        assertThat(rule.evaluate(evidence("CONFIRMED", null)).status()).isEqualTo(EvaluationStatus.UNKNOWN);
    }
}
