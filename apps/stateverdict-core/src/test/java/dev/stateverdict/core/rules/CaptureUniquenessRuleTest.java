package dev.stateverdict.core.rules;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stateverdict.core.evidence.EvidenceBundle;
import dev.stateverdict.core.evidence.EvidenceRecord;
import dev.stateverdict.core.verdict.EvaluationStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class CaptureUniquenessRuleTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final CaptureUniquenessRule rule = new CaptureUniquenessRule();

    private static EvidenceBundle bundleWithCaptures(String capturesJson) {
        String body = "{\"orderId\":\"o-1\",\"captures\":" + capturesJson + "}";
        return new EvidenceBundle("run-1", List.of(EvidenceRecord.of(
                CaptureUniquenessRule.CAPTURES_EVIDENCE, "test", Instant.EPOCH, body, JSON.readTree(body))));
    }

    @Test
    void noCaptureSatisfiesTheRule() {
        assertThat(rule.evaluate(bundleWithCaptures("[]")).status()).isEqualTo(EvaluationStatus.SATISFIED);
    }

    @Test
    void oneCaptureSatisfiesTheRule() {
        RuleResult result = rule.evaluate(bundleWithCaptures("[{\"eventId\":\"e-1\"}]"));

        assertThat(result.status()).isEqualTo(EvaluationStatus.SATISFIED);
        assertThat(result.observed()).isEqualTo("1 capture");
        assertThat(result.evidenceRefs()).containsExactly(CaptureUniquenessRule.CAPTURES_EVIDENCE);
    }

    @Test
    void twoCapturesViolateTheRuleAndTheRationaleNamesTheEvents() {
        RuleResult result = rule.evaluate(bundleWithCaptures("[{\"eventId\":\"e-1\"},{\"eventId\":\"e-1\"}]"));

        assertThat(result.status()).isEqualTo(EvaluationStatus.VIOLATED);
        assertThat(result.observed()).isEqualTo("2 captures");
        assertThat(result.rationale()).contains("o-1", "2 times", "e-1, e-1");
    }

    @Test
    void missingEvidenceIsUnknownNotSatisfied() {
        RuleResult result = rule.evaluate(new EvidenceBundle("run-1", List.of()));

        assertThat(result.status()).isEqualTo(EvaluationStatus.UNKNOWN);
        assertThat(result.evidenceRefs()).isEmpty();
    }

    @Test
    void malformedEvidenceIsUnknown() {
        String body = "{\"orderId\":\"o-1\",\"count\":1}";
        EvidenceBundle bundle = new EvidenceBundle("run-1", List.of(EvidenceRecord.of(
                CaptureUniquenessRule.CAPTURES_EVIDENCE, "test", Instant.EPOCH, body, JSON.readTree(body))));

        assertThat(rule.evaluate(bundle).status()).isEqualTo(EvaluationStatus.UNKNOWN);
    }

    @Test
    void evaluationIsDeterministicForTheSameEvidence() {
        EvidenceBundle bundle = bundleWithCaptures("[{\"eventId\":\"e-1\"},{\"eventId\":\"e-1\"}]");

        assertThat(rule.evaluate(bundle)).isEqualTo(rule.evaluate(bundle));
    }

    @Test
    void evidenceHashIsStableAndContentSensitive() {
        EvidenceRecord a = EvidenceRecord.of("x", "test", Instant.EPOCH, "{\"n\":1}", JSON.readTree("{\"n\":1}"));
        EvidenceRecord b = EvidenceRecord.of("x", "test", Instant.EPOCH, "{\"n\":1}", JSON.readTree("{\"n\":1}"));
        EvidenceRecord c = EvidenceRecord.of("x", "test", Instant.EPOCH, "{\"n\":2}", JSON.readTree("{\"n\":2}"));

        assertThat(a.sha256()).hasSize(64).isEqualTo(b.sha256()).isNotEqualTo(c.sha256());
    }
}
