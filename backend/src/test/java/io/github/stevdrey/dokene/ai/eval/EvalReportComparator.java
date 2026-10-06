package io.github.stevdrey.dokene.ai.eval;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Side-by-side comparison of two reports (baseline vs candidate). Hard-invariant regressions are flagged; quality,
 * latency and token differences are only displayed, never merged into a verdict or a single score. Run before
 * changing provider/model, prompt/context policy or the structured contract.
 */
public final class EvalReportComparator {

    public record Comparison(boolean regression, boolean comparable, List<String> warnings, List<String> lines) {
        public String render() {
            StringBuilder out = new StringBuilder();
            warnings.forEach(w -> out.append("WARNING: ").append(w).append('\n'));
            lines.forEach(l -> out.append(l).append('\n'));
            out.append(regression ? "RESULT: HARD-INVARIANT REGRESSION" : "RESULT: no hard-invariant regression")
                    .append('\n');
            return out.toString();
        }
    }

    private EvalReportComparator() {
    }

    public static Comparison compare(EvalReport baseline, EvalReport candidate) {
        List<String> warnings = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        boolean comparable = true;
        boolean schemaMismatch = false;
        if (baseline.schemaVersion() != candidate.schemaVersion()) {
            // Field and tally meanings may differ between report formats: never a clean result.
            warnings.add("report schema versions differ (" + baseline.schemaVersion() + " vs "
                    + candidate.schemaVersion() + "): the reports are not comparable, regenerate the baseline");
            comparable = false;
            schemaMismatch = true;
        }
        if (!Objects.equals(baseline.datasetVersion(), candidate.datasetVersion())) {
            warnings.add("dataset versions differ (" + baseline.datasetVersion() + " vs " + candidate.datasetVersion()
                    + "): per-case results are not comparable");
            comparable = false;
        }
        if (!Objects.equals(baseline.evaluationDate(), candidate.evaluationDate())) {
            warnings.add("evaluation dates differ (" + baseline.evaluationDate() + " vs " + candidate.evaluationDate()
                    + "): prompts contain different dates, so quality deltas are confounded");
        }
        if (!Objects.equals(baseline.contractFingerprint(), candidate.contractFingerprint())) {
            warnings.add("structured-output contract fingerprint changed (" + baseline.contractFingerprint() + " -> "
                    + candidate.contractFingerprint() + ")");
        }
        lines.add("Baseline : " + describe(baseline));
        lines.add("Candidate: " + describe(candidate));
        lines.add("");
        lines.add("Hard invariants (delivered layer) failed count: baseline -> candidate");
        boolean regression = schemaMismatch;
        java.util.Set<String> invariantIds = new java.util.LinkedHashSet<>(baseline.summary().deliveredInvariants().keySet());
        invariantIds.addAll(candidate.summary().deliveredInvariants().keySet());
        for (String id : invariantIds) {
            EvalReport.Tally before = baseline.summary().deliveredInvariants().get(id);
            EvalReport.Tally after = candidate.summary().deliveredInvariants().get(id);
            if (after == null) {
                // A removed or renamed hard invariant silently drops safety coverage: never a clean result.
                regression = true;
                lines.add("  " + id + ": " + before.failed() + " -> MISSING  <-- REGRESSION (invariant removed or renamed)");
                continue;
            }
            int beforeFailed = before == null ? 0 : before.failed();
            if (after.failed() > 0 || after.failed() > beforeFailed) {
                regression = true;
            }
            lines.add("  " + id + ": " + beforeFailed + " -> " + after.failed()
                    + (after.failed() > beforeFailed ? "  <-- REGRESSION" : after.failed() > 0 ? "  <-- FAILING" : ""));
            if (before != null && after.applicable() < before.applicable()) {
                // Fewer applicable cases means less delivered output was checked: zero failures no longer prove the
                // same safety coverage (a live run may legitimately deliver only a share of the cases).
                String coverage = "  " + id + " applicability: " + before.applicable() + " -> " + after.applicable();
                if (comparable || after.applicable() == 0) {
                    regression = true;
                    lines.add(coverage + "  <-- REGRESSION (coverage lost)");
                } else {
                    warnings.add(id + " applies to fewer cases (" + before.applicable() + " -> " + after.applicable()
                            + "): coverage dropped, datasets differ");
                }
            }
        }
        int unexpectedBefore = baseline.summary().unexpectedFailures();
        int unexpectedAfter = candidate.summary().unexpectedFailures();
        lines.add("Unexpected runtime exceptions: " + unexpectedBefore + " -> " + unexpectedAfter
                + (unexpectedAfter > 0 ? "  <-- REGRESSION (broken experiment)" : ""));
        if (unexpectedAfter > 0) {
            regression = true;
        }
        if (comparable) {
            Map<String, EvalReport.CaseReport> before = new LinkedHashMap<>();
            baseline.cases().forEach(c -> before.put(c.id(), c));
            Map<String, EvalReport.CaseReport> after = new LinkedHashMap<>();
            candidate.cases().forEach(c -> after.put(c.id(), c));
            for (String id : before.keySet()) {
                if (!after.containsKey(id)) {
                    // Dropping a baseline case silently loses safety-scenario coverage.
                    regression = true;
                    lines.add("  case " + id + " is missing from the candidate  <-- REGRESSION (coverage lost)");
                }
            }
            for (String id : after.keySet()) {
                if (!before.containsKey(id)) {
                    warnings.add("case " + id + " is not in the baseline (same dataset version)");
                }
            }
            for (EvalReport.CaseReport current : candidate.cases()) {
                EvalReport.CaseReport previous = before.get(current.id());
                if (previous == null) {
                    continue;
                }
                for (var verdict : current.deliveredInvariants().entrySet()) {
                    String old = previous.deliveredInvariants().get(verdict.getKey());
                    if (!"FAIL".equals(old) && "FAIL".equals(verdict.getValue())) {
                        regression = true;
                        lines.add("  case " + current.id() + " regressed on " + verdict.getKey());
                    }
                    if ("PASS".equals(old) && "NOT_APPLICABLE".equals(verdict.getValue())) {
                        regression = true;
                        lines.add("  case " + current.id() + " lost coverage on " + verdict.getKey()
                                + " (PASS -> NOT_APPLICABLE)  <-- REGRESSION");
                    }
                }
                if (!Objects.equals(previous.recommendationStatus(), current.recommendationStatus())
                        || !Objects.equals(previous.draftStatus(), current.draftStatus())) {
                    lines.add("  case " + current.id() + " outcome changed: " + previous.recommendationStatus() + "/"
                            + previous.draftStatus() + " -> " + current.recommendationStatus() + "/"
                            + current.draftStatus());
                }
            }
        }
        EvalReport.RawTotals rb = baseline.summary().rawModelFindings();
        EvalReport.RawTotals rc = candidate.summary().rawModelFindings();
        lines.add("");
        lines.add("Raw model findings (informational): schemaInvalid " + rb.schemaInvalid() + "->" + rc.schemaInvalid()
                + ", allowlist " + rb.allowlistViolation() + "->" + rc.allowlistViolation() + ", unsafeDraft "
                + rb.unsafeDraft() + "->" + rc.unsafeDraft() + ", refusal " + rb.refusal() + "->" + rc.refusal()
                + ", providerFailure " + rb.providerFailure() + "->" + rc.providerFailure());
        EvalReport.Usage ub = baseline.summary().usage();
        EvalReport.Usage uc = candidate.summary().usage();
        boolean operationCoverageLost = false;
        for (var entry : baseline.summary().operations().entrySet()) {
            EvalReport.Coverage current = candidate.summary().operations().get(entry.getKey());
            boolean lost = current == null || current.deliveredCases() < entry.getValue().deliveredCases();
            lines.add("Delivered outcomes " + entry.getKey() + ": " + entry.getValue().deliveredCases() + "/"
                    + entry.getValue().invokedCases() + " -> " + (current == null ? "MISSING"
                    : current.deliveredCases() + "/" + current.invokedCases())
                    + (lost && comparable ? "  <-- REGRESSION (delivery coverage lost)" : ""));
            if (lost) {
                if (comparable) {
                    operationCoverageLost = true;
                } else {
                    warnings.add(entry.getKey() + " delivers fewer outcomes (datasets or report schemas differ)");
                }
            }
        }
        candidate.summary().operations().forEach((operation, coverage) -> {
            if (!baseline.summary().operations().containsKey(operation)) {
                lines.add("Delivered outcomes " + operation + ": n/a -> " + coverage.deliveredCases() + "/"
                        + coverage.invokedCases());
            }
        });
        // Fewer delivered outcomes per operation means less output was checked, even when every case-level
        // invariant verdict still applies through the other operation.
        regression |= operationCoverageLost;
        lines.add("Latency p50/p95 ms (reported): " + ub.reportedLatencyP50Ms() + "/" + ub.reportedLatencyP95Ms()
                + " -> " + uc.reportedLatencyP50Ms() + "/" + uc.reportedLatencyP95Ms());
        lines.add("Tokens in/out: " + ub.inputTokens() + "/" + ub.outputTokens() + " -> " + uc.inputTokens() + "/"
                + uc.outputTokens());
        lines.add("Price table USD/1M tokens (in/out): " + rates(ub) + " -> " + rates(uc));
        if (!Objects.equals(ub.inputUsdPerMillionTokens(), uc.inputUsdPerMillionTokens())
                || !Objects.equals(ub.outputUsdPerMillionTokens(), uc.outputUsdPerMillionTokens())) {
            warnings.add("price tables differ: the cost delta is not comparable (it mixes pricing and token usage)");
        }
        if (ub.callsMissingUsage() > 0 || uc.callsMissingUsage() > 0) {
            warnings.add("calls without token usage (baseline " + ub.callsMissingUsage() + ", candidate "
                    + uc.callsMissingUsage() + "): token and cost totals are incomplete");
        }
        lines.add("Cost USD: " + ub.estimatedCostUsd() + " -> " + uc.estimatedCostUsd());
        lines.add("Human rubric dimensions are compared by reviewers; no aggregate score is computed.");
        return new Comparison(regression, comparable, warnings, lines);
    }

    private static String rates(EvalReport.Usage usage) {
        return usage.inputUsdPerMillionTokens() == null ? "n/a"
                : usage.inputUsdPerMillionTokens() + "/" + usage.outputUsdPerMillionTokens();
    }

    private static String describe(EvalReport report) {
        return report.mode() + " | " + report.provider() + " | " + report.model() + " | dataset "
                + report.datasetVersion() + " | policy " + report.promptPolicyLabel();
    }

    /** CLI used by the {@code aiEvalCompare} Gradle task: exit 1 on a hard-invariant regression. */
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: aiEvalCompare -Pbaseline=<report.json> -Pcandidate=<report.json>");
            System.exit(2);
        }
        Comparison result = compare(EvalReportWriter.read(Path.of(args[0])), EvalReportWriter.read(Path.of(args[1])));
        System.out.print(result.render());
        System.exit(result.regression() ? 1 : 0);
    }
}
