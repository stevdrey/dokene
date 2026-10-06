package io.github.stevdrey.dokene.ai.eval;

import io.github.stevdrey.dokene.ai.domain.MessageDraft;
import io.github.stevdrey.dokene.ai.eval.InvariantChecker.InvariantResult;
import io.github.stevdrey.dokene.ai.eval.InvariantChecker.RawFindings;
import io.github.stevdrey.dokene.ai.eval.InvariantChecker.Verdict;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Turns observations into a report: hard invariants, raw-model findings and usage, kept strictly separate. */
public final class EvalReportBuilder {
    /** Optional cost inputs (USD per million tokens); cost stays null when no price table is supplied. */
    public record Pricing(double inputUsdPerMillionTokens, double outputUsdPerMillionTokens) {
    }

    private EvalReportBuilder() {
    }

    public static EvalReport build(EvalDataset dataset, List<CaseObservation> observations, String mode,
            String promptPolicyLabel, Pricing pricing, boolean includeWallLatency) {
        List<EvalReport.CaseReport> cases = new ArrayList<>();
        Map<String, int[]> tallies = new LinkedHashMap<>();
        InvariantChecker.DELIVERED_INVARIANTS.forEach(id -> tallies.put(id, new int[3]));
        int[] recCoverage = new int[2];
        int[] draftCoverage = new int[2];
        int unexpected = 0;
        int matches = 0;
        int mismatches = 0;
        int[] raw = new int[5];
        List<Long> reported = new ArrayList<>();
        List<Long> wall = new ArrayList<>();
        long input = 0;
        long output = 0;
        int calls = 0;

        for (CaseObservation obs : observations) {
            Map<String, InvariantResult> delivered = InvariantChecker.checkDelivered(obs);
            RawFindings findings = InvariantChecker.rawFindings(obs);
            Map<String, String> verdicts = new LinkedHashMap<>();
            Map<String, List<String>> violations = new LinkedHashMap<>();
            delivered.forEach((id, result) -> {
                verdicts.put(id, result.verdict().name());
                int[] tally = tallies.get(id);
                if (result.verdict() != Verdict.NOT_APPLICABLE) {
                    tally[0]++;
                    tally[result.verdict() == Verdict.PASS ? 1 : 2]++;
                }
                if (result.verdict() == Verdict.FAIL) {
                    violations.put(id, result.violations());
                }
            });
            boolean recInvoked = obs.calls().stream().anyMatch(c -> c.operation() == io.github.stevdrey.dokene.ai.application.AiOperation.NEXT_BEST_ACTION);
            boolean draftInvoked = obs.calls().stream().anyMatch(c -> c.operation() == io.github.stevdrey.dokene.ai.application.AiOperation.MESSAGE_DRAFT);
            recCoverage[0] += recInvoked ? 1 : 0;
            recCoverage[1] += recInvoked && (obs.deliveredRecommendation() != null || obs.deliveredRecommendationRefusal() != null) ? 1 : 0;
            draftCoverage[0] += draftInvoked ? 1 : 0;
            draftCoverage[1] += draftInvoked && (obs.deliveredDraft() != null || obs.deliveredDraftRefusal() != null) ? 1 : 0;
            boolean unexpectedFailure = unexpectedFailure(obs);
            unexpected += unexpectedFailure ? 1 : 0;
            Boolean behaviorMatch = "deterministic".equals(mode) ? behaviorMatches(obs) : null;
            if (Boolean.TRUE.equals(behaviorMatch)) {
                matches++;
            } else if (Boolean.FALSE.equals(behaviorMatch)) {
                mismatches++;
            }
            raw[0] += findings.schemaInvalid() ? 1 : 0;
            raw[1] += findings.providerFailure() ? 1 : 0;
            raw[2] += findings.allowlistViolation() ? 1 : 0;
            raw[3] += findings.unsafeDraft() ? 1 : 0;
            raw[4] += findings.refusal() ? 1 : 0;
            for (EvalProviderCall call : obs.calls()) {
                calls++;
                if (call.metadata() != null) {
                    reported.add(call.metadata().latency().toMillis());
                    if (call.metadata().usage() != null) {
                        input += call.metadata().usage().inputTokens();
                        output += call.metadata().usage().outputTokens();
                    }
                }
                wall.add(call.wallLatencyNanos() / 1_000_000);
            }
            cases.add(new EvalReport.CaseReport(obs.evalCase().id(), obs.evalCase().family().name(),
                    obs.recommendationStatus(), obs.recommendationRejection(), obs.draftStatus(),
                    obs.draftRejection(), obs.calls().size(), verdicts, violations,
                    new EvalReport.RawFlags(findings.schemaInvalid(), findings.providerFailure(),
                            findings.allowlistViolation(), findings.unsafeDraft(), findings.refusal()),
                    unexpectedFailure, behaviorMatch, informational(obs), delivered(obs), EvalReport.Rubric.blank()));
        }

        Map<String, EvalReport.Tally> deliveredTotals = new LinkedHashMap<>();
        boolean allPass = true;
        for (var entry : tallies.entrySet()) {
            int[] t = entry.getValue();
            deliveredTotals.put(entry.getKey(), new EvalReport.Tally(t[0], t[1], t[2]));
            allPass &= t[2] == 0;
        }
        Double cost = pricing == null ? null
                : input / 1_000_000.0 * pricing.inputUsdPerMillionTokens()
                        + output / 1_000_000.0 * pricing.outputUsdPerMillionTokens();
        EvalReport.Usage usage = new EvalReport.Usage(calls, percentile(reported, 50), percentile(reported, 95),
                includeWallLatency ? percentile(wall, 50) : null, includeWallLatency ? percentile(wall, 95) : null,
                input, output, cost);
        EvalReport.Summary summary = new EvalReport.Summary(observations.size(), deliveredTotals, allPass, unexpected,
                "deterministic".equals(mode) ? matches : null, "deterministic".equals(mode) ? mismatches : null,
                new EvalReport.RawTotals(raw[0], raw[1], raw[2], raw[3], raw[4]),
                operations(recCoverage, draftCoverage), usage);
        return new EvalReport(EvalReport.SCHEMA_VERSION, dataset.datasetVersion(), mode,
                String.join(",", EvalRunner.sortedModels(observations, true)),
                String.join(",", EvalRunner.sortedModels(observations, false)), EvalRunner.contractFingerprint(),
                promptPolicyLabel, Instant.now().toString(), summary, cases);
    }

    /** Insertion-ordered so the serialized report is byte-reproducible across JVM runs. */
    private static Map<String, EvalReport.Coverage> operations(int[] recommendation, int[] draft) {
        Map<String, EvalReport.Coverage> operations = new LinkedHashMap<>();
        operations.put("NEXT_BEST_ACTION", new EvalReport.Coverage(recommendation[0], recommendation[1]));
        operations.put("MESSAGE_DRAFT", new EvalReport.Coverage(draft[0], draft[1]));
        return operations;
    }

    /**
     * An exception escaping the services is a broken experiment, not a result. Only an exception pinned by the
     * case's expectation (a deliberately malformed request such as {@code ua-02}, rejected before any provider is
     * reached) is expected, in deterministic and live runs alike.
     */
    static boolean unexpectedFailure(CaseObservation obs) {
        EvalCase.Expect expect = obs.evalCase().expect();
        return isException(obs.recommendationStatus()) && !obs.recommendationStatus().equals(expect.recommendationStatus())
                || isException(obs.draftStatus()) && !obs.draftStatus().equals(expect.draftStatus());
    }

    private static boolean isException(String status) {
        return status != null && status.startsWith("EXCEPTION_");
    }

    /** Deterministic baseline check: the observed statuses equal the dataset's pinned platform behavior. */
    static boolean behaviorMatches(CaseObservation obs) {
        EvalCase.Expect expect = obs.evalCase().expect();
        return expect.recommendationStatus().equals(obs.recommendationStatus())
                && java.util.Objects.equals(expect.recommendationRejection(), obs.recommendationRejection())
                && expect.draftStatus().equals(obs.draftStatus())
                && java.util.Objects.equals(expect.draftRejection(), obs.draftRejection())
                && expect.providerInvoked() == !obs.calls().isEmpty();
    }

    private static EvalReport.Delivered delivered(CaseObservation obs) {
        var rec = obs.deliveredRecommendation();
        var draft = obs.deliveredDraft();
        var recRefusal = obs.deliveredRecommendationRefusal();
        var draftRefusal = obs.deliveredDraftRefusal();
        if (rec == null && draft == null && recRefusal == null && draftRefusal == null) {
            return null;
        }
        return new EvalReport.Delivered(
                rec == null ? null : new EvalReport.DeliveredRecommendation(rec.action().name(),
                        rec.templateIntent().name(), rec.rationale(), rec.confidence().value(),
                        rec.draftVariables().asMap()),
                draft == null ? null : new EvalReport.DeliveredDraft(draft.action().name(),
                        draft.templateIntent().name(), draft.locale(), draft.body(), draft.evidence(),
                        draft.warnings(), draft.rationale(), draft.confidence().value(),
                        draft.draftVariables().asMap()),
                recRefusal == null ? null : recRefusal.reason() + ": " + recRefusal.rationale(),
                draftRefusal == null ? null : draftRefusal.reason() + ": " + draftRefusal.rationale());
    }

    /** Clearly labelled heuristics: informational only, never pass/fail and never part of a score. */
    private static Map<String, Object> informational(CaseObservation obs) {
        Map<String, Object> info = new LinkedHashMap<>();
        MessageDraft draft = obs.deliveredDraft();
        info.put("draftDelivered", draft != null);
        info.put("recommendationDelivered", obs.deliveredRecommendation() != null);
        if (draft != null) {
            info.put("draftBodyLength", draft.body().codePointCount(0, draft.body().length()));
            info.put("localeIsConfiguredLocale", MessageDraft.DEFAULT_LOCALE.equalsIgnoreCase(draft.locale()));
            String firstName = obs.evalCase().displayName().split("\\s+")[0].toLowerCase(Locale.ROOT);
            info.put("addressesCustomerByName", draft.body().toLowerCase(Locale.ROOT).contains(firstName));
        }
        return info;
    }

    static Long percentile(List<Long> values, int percentile) {
        if (values.isEmpty()) {
            return null;
        }
        List<Long> sorted = values.stream().sorted().toList();
        int rank = (int) Math.ceil(percentile / 100.0 * sorted.size());
        return sorted.get(Math.max(0, rank - 1));
    }
}
