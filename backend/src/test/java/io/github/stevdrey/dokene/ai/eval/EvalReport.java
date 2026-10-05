package io.github.stevdrey.dokene.ai.eval;

import java.util.List;
import java.util.Map;

/**
 * Machine-readable evaluation report. There is intentionally no aggregate "score": hard invariants are pass/fail,
 * raw-model findings and cost/latency are reported side by side, and rubric dimensions are filled by human graders.
 * {@code generatedAt} is the only non-reproducible field of a deterministic run and is ignored when comparing.
 */
public record EvalReport(
        int schemaVersion,
        String datasetVersion,
        String mode,
        String provider,
        String model,
        String contractFingerprint,
        String promptPolicyLabel,
        String generatedAt,
        Summary summary,
        List<CaseReport> cases) {

    public static final int SCHEMA_VERSION = 1;

    public record Summary(
            int totalCases,
            Map<String, Tally> deliveredInvariants,
            boolean allDeliveredInvariantsPass,
            Integer behaviorMatches,
            Integer behaviorMismatches,
            RawTotals rawModelFindings,
            Usage usage) {
    }

    public record Tally(int applicable, int passed, int failed) {
    }

    /** Case counts, informational: how often the untrusted model output was unsafe before the deterministic gate. */
    public record RawTotals(int schemaInvalid, int providerFailure, int allowlistViolation, int unsafeDraft,
            int refusal) {
    }

    public record Usage(int providerCalls, Long reportedLatencyP50Ms, Long reportedLatencyP95Ms,
            Long wallLatencyP50Ms, Long wallLatencyP95Ms, long inputTokens, long outputTokens,
            Double estimatedCostUsd) {
    }

    public record CaseReport(
            String id,
            String family,
            String recommendationStatus,
            String recommendationRejection,
            String draftStatus,
            String draftRejection,
            int providerCalls,
            Map<String, String> deliveredInvariants,
            Map<String, List<String>> violations,
            RawFlags rawModel,
            Boolean behaviorMatch,
            Map<String, Object> informational,
            Rubric rubric) {
    }

    public record RawFlags(boolean schemaInvalid, boolean providerFailure, boolean allowlistViolation,
            boolean unsafeDraft, boolean refusal) {
    }

    /** Human-graded 1-5 (null until graded) against the anchors in docs/verification/issue-98. */
    public record Rubric(Integer recommendationRelevance, Integer rationaleUsefulness, Integer draftQuality,
            Integer factualGrounding, Integer editabilityAndTone, String notes) {
        public static Rubric blank() {
            return new Rubric(null, null, null, null, null, null);
        }
    }
}
