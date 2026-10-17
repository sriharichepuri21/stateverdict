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

class RefundForCancelledCaptureRuleTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final RefundForCancelledCaptureRule rule = new RefundForCancelledCaptureRule();

    private static EvidenceBundle evidence(boolean cancelled, String capturesJson, String refundsJson) {
        List<EvidenceRecord> records = new ArrayList<>();
        String order = "{\"id\":\"o-1\",\"status\":\"CANCELLED\",\"cancelRequestedAt\":"
                + (cancelled ? "\"2026-10-17T10:00:00Z\"" : "null") + "}";
        records.add(record("order.state", order));
        if (capturesJson != null) {
            records.add(record("payment.captures", "{\"captures\":" + capturesJson + "}"));
        }
        if (refundsJson != null) {
            records.add(record("payment.refunds", "{\"refunds\":" + refundsJson + "}"));
        }
        return new EvidenceBundle("run-1", records);
    }

    private static EvidenceRecord record(String id, String body) {
        return EvidenceRecord.of(id, "test", Instant.EPOCH, body, JSON.readTree(body));
    }

    @Test
    void cancelledAndCapturedButNotRefundedIsAViolationNamingTheCapture() {
        RuleResult result = rule.evaluate(evidence(true, "[{\"id\":\"c1\"}]", "[]"));

        assertThat(result.status()).isEqualTo(EvaluationStatus.VIOLATED);
        assertThat(result.rationale()).contains("c1");
    }

    @Test
    void everyCaptureMustBeRefunded() {
        assertThat(rule.evaluate(evidence(true, "[{\"id\":\"c1\"},{\"id\":\"c2\"}]", "[{\"captureId\":\"c1\"}]"))
                .status()).isEqualTo(EvaluationStatus.VIOLATED);
        assertThat(rule.evaluate(evidence(true, "[{\"id\":\"c1\"},{\"id\":\"c2\"}]",
                "[{\"captureId\":\"c1\"},{\"captureId\":\"c2\"}]")).status()).isEqualTo(EvaluationStatus.SATISFIED);
    }

    @Test
    void notCancelledOrNothingCapturedSatisfiesTheRule() {
        assertThat(rule.evaluate(evidence(false, "[{\"id\":\"c1\"}]", null)).status()).isEqualTo(EvaluationStatus.SATISFIED);
        assertThat(rule.evaluate(evidence(true, "[]", null)).status()).isEqualTo(EvaluationStatus.SATISFIED);
    }

    @Test
    void missingRefundEvidenceIsUnknown() {
        assertThat(rule.evaluate(evidence(true, "[{\"id\":\"c1\"}]", null)).status()).isEqualTo(EvaluationStatus.UNKNOWN);
        assertThat(rule.evaluate(evidence(true, null, null)).status()).isEqualTo(EvaluationStatus.UNKNOWN);
    }

    @Test
    void itIsAnEventualRule() {
        assertThat(rule.eventual()).isTrue();
        assertThat(new CaptureUniquenessRule().eventual()).isFalse();
    }
}
