package com.idavy.drtops.jtsimulator;

import java.util.List;
import java.util.Objects;

/**
 * Scenario outcome. The report only ever carries the masked terminal alias and connection names;
 * full identities, tokens and raw reply bodies are deliberately absent so reports can be attached
 * to evidence packages without de-identification work.
 */
public final class ScenarioReport {
    private final String scenarioName;
    private final String terminalAlias;
    private final List<StepRecord> steps;
    private final List<ReplyRecord> replies;

    public ScenarioReport(
            String scenarioName,
            String terminalAlias,
            List<StepRecord> steps,
            List<ReplyRecord> replies) {
        this.scenarioName = Objects.requireNonNull(scenarioName, "scenarioName");
        this.terminalAlias = Objects.requireNonNull(terminalAlias, "terminalAlias");
        this.steps = List.copyOf(steps);
        this.replies = List.copyOf(replies);
    }

    public String scenarioName() {
        return scenarioName;
    }

    public String terminalAlias() {
        return terminalAlias;
    }

    public List<StepRecord> steps() {
        return steps;
    }

    public List<ReplyRecord> replies() {
        return replies;
    }

    public boolean allPassed() {
        return steps.stream().allMatch(step -> step.outcome() == Outcome.PASS);
    }

    public String asText() {
        StringBuilder text = new StringBuilder();
        text.append("scenario=").append(scenarioName)
                .append(" terminal=").append(terminalAlias)
                .append(" result=").append(allPassed() ? "PASS" : "FAIL")
                .append('\n');
        for (StepRecord step : steps) {
            text.append("  step ").append(step.index())
                    .append(' ').append(step.action())
                    .append(step.connection() == null ? "" : " [" + step.connection() + "]")
                    .append(' ').append(step.outcome())
                    .append(step.detail().isBlank() ? "" : " - " + step.detail())
                    .append('\n');
        }
        text.append("  replies=").append(replies.size());
        return text.toString();
    }

    public enum Outcome {
        PASS,
        FAIL,
        SKIP
    }

    public record StepRecord(int index, String action, String connection, Outcome outcome, String detail) {
        public StepRecord {
            detail = detail == null ? "" : detail;
        }
    }

    /** Report-facing reply projection: message numbers and results only, never raw bodies. */
    public record ReplyRecord(int messageId, Integer requestMessageId, int result, int requestSerialNo) { }
}
