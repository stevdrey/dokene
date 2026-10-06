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
        boolean regression = false;
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
            if (before != null && before.applicable() > 0 && after.applicable() == 0) {
                warnings.add(id + " no longer applies to any case (was " + before.applicable() + "): coverage dropped");
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
        candidate.summary().operations().forEach((operation, coverage) -> {
            EvalReport.Coverage previous = baseline.summary().operations().get(operation);
            lines.add("Delivered outcomes " + operation + ": " + (previous == null ? "n/a" : previous.deliveredCases()
                    + "/" + previous.invokedCases()) + " -> " + coverage.deliveredCases() + "/" + coverage.invokedCases());
        });
        lines.add("Latency p50/p95 ms (reported): " + ub.reportedLatencyP50Ms() + "/" + ub.reportedLatencyP95Ms()
                + " -> " + uc.reportedLatencyP50Ms() + "/" + uc.reportedLatencyP95Ms());
        lines.add("Tokens in/out: " + ub.inputTokens() + "/" + ub.outputTokens() + " -> " + uc.inputTokens() + "/"
                + uc.outputTokens());
        lines.add("Cost USD: " + ub.estimatedCostUsd() + " -> " + uc.estimatedCostUsd());
        lines.add("Human rubric dimensions are compared by reviewers; no aggregate score is computed.");
        return new Comparison(regression, comparable, warnings, lines);
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
