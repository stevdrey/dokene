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
        if (!Objects.equals(baseline.contractFingerprint(), candidate.contractFingerprint())) {
            warnings.add("structured-output contract fingerprint changed (" + baseline.contractFingerprint() + " -> "
                    + candidate.contractFingerprint() + ")");
        }
        lines.add("Baseline : " + describe(baseline));
        lines.add("Candidate: " + describe(candidate));
        lines.add("");
        lines.add("Hard invariants (delivered layer) failed count: baseline -> candidate");
        boolean regression = false;
        for (var entry : candidate.summary().deliveredInvariants().entrySet()) {
            EvalReport.Tally before = baseline.summary().deliveredInvariants().get(entry.getKey());
            int beforeFailed = before == null ? 0 : before.failed();
            int afterFailed = entry.getValue().failed();
            if (afterFailed > 0 || afterFailed > beforeFailed) {
                regression = true;
            }
            lines.add("  " + entry.getKey() + ": " + beforeFailed + " -> " + afterFailed
                    + (afterFailed > beforeFailed ? "  <-- REGRESSION" : afterFailed > 0 ? "  <-- FAILING" : ""));
        }
        if (comparable) {
            Map<String, EvalReport.CaseReport> before = new LinkedHashMap<>();
            baseline.cases().forEach(c -> before.put(c.id(), c));
            for (EvalReport.CaseReport after : candidate.cases()) {
                EvalReport.CaseReport previous = before.get(after.id());
                if (previous == null) {
                    continue;
                }
                for (var verdict : after.deliveredInvariants().entrySet()) {
                    String old = previous.deliveredInvariants().get(verdict.getKey());
                    if (!"FAIL".equals(old) && "FAIL".equals(verdict.getValue())) {
                        regression = true;
                        lines.add("  case " + after.id() + " regressed on " + verdict.getKey());
                    }
                }
                if (!Objects.equals(previous.recommendationStatus(), after.recommendationStatus())
                        || !Objects.equals(previous.draftStatus(), after.draftStatus())) {
                    lines.add("  case " + after.id() + " outcome changed: " + previous.recommendationStatus() + "/"
                            + previous.draftStatus() + " -> " + after.recommendationStatus() + "/"
                            + after.draftStatus());
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
