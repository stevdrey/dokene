package io.github.stevdrey.dokene.ai.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Per scenario family delivery coverage (Issue #253). The global per-operation ratio can stay green while every case
 * of one family timed out, which leaves that family's controls ungraded; this makes such a gap explicit.
 */
final class EvalFamilyCoverage {

    private EvalFamilyCoverage() {
    }

    /** Family/operation pairs that reached the provider without any delivered outcome, e.g. {@code F/OP (0 of 5)}. */
    static List<String> uncoveredFamilies(EvalReport report) {
        List<String> uncovered = new ArrayList<>();
        Objects.requireNonNullElse(report.summary().families(), Map.<String, Map<String, EvalReport.Coverage>>of()).forEach((family, operations) -> operations.forEach((operation, coverage) -> {
            if (coverage.uncovered()) {
                uncovered.add(family + "/" + operation + " (0 of " + coverage.invokedCases() + ")");
            }
        }));
        return uncovered;
    }

    /** One line per undelivered operation: case, operation, status (rejection) and provider failure categories. */
    static List<String> undeliveredCases(EvalReport report) {
        return Objects.requireNonNullElse(report.summary().undelivered(), List.<EvalReport.UndeliveredCase>of()).stream()
                .map(u -> u.caseId() + " " + u.operation() + " " + u.status()
                        + (u.rejection() == null ? "" : " (" + u.rejection() + ")")
                        + (u.failures().isEmpty() ? "" : " " + u.failures()))
                .toList();
    }

    /** Prefix of every console line; the {@code aiEvalLive} Gradle task forwards exactly these lines. */
    static final String CONSOLE_PREFIX = "[ai-eval] ";

    /** Console warning block, or an empty string when every invoked family/operation delivered output. */
    static String consoleSummary(EvalReport report) {
        List<String> uncovered = uncoveredFamilies(report);
        List<String> undelivered = undeliveredCases(report);
        if (uncovered.isEmpty() && undelivered.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        if (!uncovered.isEmpty()) {
            out.append(CONSOLE_PREFIX).append("WARNING: AI evaluation coverage INCOMPLETE, families without delivered output (not graded): ")
                    .append(String.join(", ", uncovered)).append('\n');
        }
        out.append(CONSOLE_PREFIX).append("WARNING: ").append(undelivered.size())
                .append(" provider-invoked operation(s) without delivered output:\n");
        undelivered.forEach(line -> out.append(CONSOLE_PREFIX).append("  - ").append(line).append('\n'));
        return out.toString();
    }
}
