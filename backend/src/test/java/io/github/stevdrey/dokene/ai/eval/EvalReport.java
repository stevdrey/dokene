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
        String evaluationDate,
        String mode,
        String provider,
        String model,
        String contractFingerprint,
        String promptPolicyLabel,
        String generatedAt,
        Summary summary,
        List<CaseReport> cases) {

    public static final int SCHEMA_VERSION = 1;

    /** Report for the pinned evaluation date (see {@link EvalRunner#EVAL_DATE}). */
    public EvalReport(int schemaVersion, String datasetVersion, String mode, String provider, String model,
            String contractFingerprint, String promptPolicyLabel, String generatedAt, Summary summary,
            List<CaseReport> cases) {
        this(schemaVersion, datasetVersion, EvalRunner.EVAL_DATE.toString(), mode, provider, model, contractFingerprint,
                promptPolicyLabel, generatedAt, summary, cases);
    }

    public record Summary(
            int totalCases,
            Map<String, Tally> deliveredInvariants,
            boolean allDeliveredInvariantsPass,
            int unexpectedFailures,
            Integer behaviorMatches,
            Integer behaviorMismatches,
            RawTotals rawModelFindings,
            Map<String, Coverage> operations,
            Usage usage) {
    }

    /** Per operation: cases that reached the provider and cases with a delivered outcome (action/draft or refusal). */
    public record Coverage(int invokedCases, int deliveredCases) {
    }

    public record Tally(int applicable, int passed, int failed) {
    }

    /** Case counts, informational: how often the untrusted model output was unsafe before the deterministic gate. */
    public record RawTotals(int schemaInvalid, int providerFailure, int allowlistViolation, int unsafeDraft,
            int refusal) {
    }

    public record Usage(int providerCalls, Long reportedLatencyP50Ms, Long reportedLatencyP95Ms,
            Long wallLatencyP50Ms, Long wallLatencyP95Ms, long inputTokens, long outputTokens,
            Double estimatedCostUsd, Double inputUsdPerMillionTokens, Double outputUsdPerMillionTokens) {
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
            boolean unexpectedFailure,
            Boolean behaviorMatch,
            Map<String, Object> informational,
            Delivered delivered,
            Rubric rubric) {
    }

    /**
     * Content exactly as delivered to the operator after the gate, kept so reviewers can grade quality after the
     * run. Null parts were not delivered. Contains only model output about invented synthetic customers.
     */
    public record Delivered(DeliveredRecommendation recommendation, DeliveredDraft draft,
            String recommendationRefusal, String draftRefusal) {
    }

    public record DeliveredRecommendation(String action, String templateIntent, String rationale, double confidence,
            Map<String, String> draftVariables) {
    }

    public record DeliveredDraft(String action, String templateIntent, String locale, String body,
            List<String> evidence, List<String> warnings, String rationale, double confidence,
            Map<String, String> draftVariables) {
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
