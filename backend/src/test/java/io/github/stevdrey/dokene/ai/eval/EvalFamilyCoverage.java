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
    /** Prefix of every console line; the {@code aiEvalLive} Gradle task forwards exactly these lines. */
    static final String CONSOLE_PREFIX = "[ai-eval] ";

    private EvalFamilyCoverage() {
    }

    /** Whether the report carries per-family coverage at all; without it coverage is unknown, never complete. */
    static boolean hasFamilyData(EvalReport report) {
        return report.summary().families() != null;
    }

    /** Family/operation pairs that reached the provider without any delivered outcome, e.g. {@code F/OP (0 of 5)}. */
    static List<String> uncoveredFamilies(EvalReport report) {
        List<String> uncovered = new ArrayList<>();
        Objects.requireNonNullElse(report.summary().families(), Map.<String, Map<String, EvalReport.Coverage>>of())
                .forEach((family, operations) -> operations.forEach((operation, coverage) -> {
                    if (coverage.uncovered()) {
                        uncovered.add(family + "/" + operation + " (0 of " + coverage.invokedCases() + ")");
                    }
                }));
        return uncovered;
    }

    /** Operations whose every provider attempt failed (timeouts, outages, invalid output). */
    static List<EvalReport.UndeliveredCase> providerFailures(EvalReport report) {
        return undelivered(report).stream()
                .filter(u -> EvalReport.UndeliveredCase.PROVIDER_FAILURE.equals(u.cause()))
                .toList();
    }

    /** One line per provider failure: case, operation, status (rejection) and provider failure categories. */
    static List<String> undeliveredCases(EvalReport report) {
        return providerFailures(report).stream()
                .map(u -> u.caseId() + " " + u.operation() + " " + EvalReportWriter.status(u.status(), u.rejection())
                        + (u.failures().isEmpty() ? "" : " " + u.failures()))
                .toList();
    }

    /**
     * Console warning block, or an empty string when no family is uncovered and no provider attempt failed outright.
     * Gate rejections are the controls working, so they are only counted, never warned about.
     */
    static String consoleSummary(EvalReport report) {
        List<String> uncovered = uncoveredFamilies(report);
        List<String> failures = undeliveredCases(report);
        if (uncovered.isEmpty() && failures.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        if (!uncovered.isEmpty()) {
            out.append(CONSOLE_PREFIX).append("WARNING: AI evaluation coverage INCOMPLETE, families without delivered ")
                    .append("output (not graded): ").append(String.join(", ", uncovered)).append('\n');
        }
        if (!failures.isEmpty()) {
            out.append(CONSOLE_PREFIX).append("WARNING: ").append(failures.size())
                    .append(" operation(s) without delivered output because the provider failed:\n");
            failures.forEach(line -> out.append(CONSOLE_PREFIX).append("  - ").append(line).append('\n'));
        }
        long gateRejected = undelivered(report).size() - providerFailures(report).size();
        if (gateRejected > 0) {
            out.append(CONSOLE_PREFIX).append(gateRejected)
                    .append(" other operation(s) were rejected by the gate (expected for unsafe output, not a gap).\n");
        }
        return out.toString();
    }

    private static List<EvalReport.UndeliveredCase> undelivered(EvalReport report) {
        return Objects.requireNonNullElse(report.summary().undelivered(), List.of());
    }
}
