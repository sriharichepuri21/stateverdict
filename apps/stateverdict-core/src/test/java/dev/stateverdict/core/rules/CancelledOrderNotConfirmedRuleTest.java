package dev.stateverdict.core.rules;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stateverdict.core.evidence.EvidenceBundle;
import dev.stateverdict.core.evidence.EvidenceRecord;
import dev.stateverdict.core.verdict.EvaluationStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class CancelledOrderNotConfirmedRuleTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final CancelledOrderNotConfirmedRule rule = new CancelledOrderNotConfirmedRule();

    private static EvidenceBundle order(String status, String cancelRequestedAt) {
        String body = "{\"id\":\"o-1\",\"status\":\"" + status + "\",\"cancelRequestedAt\":"
                + (cancelRequestedAt == null ? "null" : "\"" + cancelRequestedAt + "\"") + "}";
        return new EvidenceBundle("run-1", List.of(EvidenceRecord.of(
                CancelledOrderNotConfirmedRule.ORDER_EVIDENCE, "test", Instant.EPOCH, body, JSON.readTree(body))));
    }

    @Test
    void cancelledOrderThatEndsConfirmedIsAViolation() {
        RuleResult result = rule.evaluate(order("CONFIRMED", "2026-10-16T10:00:00Z"));

        assertThat(result.status()).isEqualTo(EvaluationStatus.VIOLATED);
        assertThat(result.rationale()).contains("o-1", "cancelled", "CONFIRMED");
    }

    @Test
    void cancelledOrderThatStaysCancelledSatisfiesTheRule() {
        assertThat(rule.evaluate(order("CANCELLED", "2026-10-16T10:00:00Z")).status())
                .isEqualTo(EvaluationStatus.SATISFIED);
    }

    @Test
    void orderThatWasNeverCancelledSatisfiesTheRule() {
        assertThat(rule.evaluate(order("CONFIRMED", null)).status()).isEqualTo(EvaluationStatus.SATISFIED);
    }

    @Test
    void missingOrUnreadableEvidenceIsUnknown() {
        assertThat(rule.evaluate(new EvidenceBundle("run-1", List.of())).status()).isEqualTo(EvaluationStatus.UNKNOWN);
        String body = "{\"id\":\"o-1\"}";
        EvidenceBundle noStatus = new EvidenceBundle("run-1", List.of(EvidenceRecord.of(
                CancelledOrderNotConfirmedRule.ORDER_EVIDENCE, "test", Instant.EPOCH, body, JSON.readTree(body))));
        assertThat(rule.evaluate(noStatus).status()).isEqualTo(EvaluationStatus.UNKNOWN);
    }
}
