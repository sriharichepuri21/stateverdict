package dev.stateverdict.core.report;

import dev.stateverdict.core.rules.RuleResult;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** Renders a {@link RunReport} as JSON (for machines) and as console text (for people). */
public final class ReportWriter {

    private final JsonMapper json;

    public ReportWriter(JsonMapper json) {
        this.json = json;
    }

    public String toJson(RunReport report) {
        return json.writerWithDefaultPrettyPrinter().writeValueAsString(report);
    }

    public RunReport fromJson(String text) {
        return json.readValue(text, RunReport.class);
    }

    public String toConsole(RunReport report) {
        StringBuilder out = new StringBuilder();
        out.append("StateVerdict v").append(report.stateverdictVersion()).append("\n\n");
        out.append("Scenario:  ").append(report.scenario()).append(" (v").append(report.scenarioVersion()).append(")\n");
        out.append("Run:       ").append(report.runId()).append("\n");
        out.append("Faults:    ");
        if (report.faultSchedule().isEmpty()) {
            out.append("none");
        } else {
            for (int i = 0; i < report.faultSchedule().size(); i++) {
                Map<String, Object> fault = report.faultSchedule().get(i);
                out.append(i == 0 ? "" : ", ").append(fault);
            }
        }
        out.append("\nExecution: ").append(report.executionStatus()).append("\n");

        for (RuleResult rule : report.ruleResults()) {
            out.append("\nRule ").append(rule.ruleId()).append(" v").append(rule.ruleVersion()).append("\n");
            out.append("  ").append(rule.description()).append("\n\n");
            out.append("  Expected: ").append(rule.expected()).append("\n");
            out.append("  Observed: ").append(rule.observed()).append("\n");
            out.append("  Result:   ").append(rule.status()).append("\n");
            out.append("  Why:      ").append(rule.rationale()).append("\n");
            out.append("  Evidence: ").append(rule.evidenceRefs().isEmpty() ? "none" : String.join(", ", rule.evidenceRefs()))
                    .append("\n");
        }
        if (!report.notes().isEmpty()) {
            out.append("\nNotes:\n");
            report.notes().forEach(note -> out.append("  - ").append(note).append("\n"));
        }
        out.append("\nOverall verdict: ").append(report.verdict()).append("\n");
        return out.toString();
    }
}
