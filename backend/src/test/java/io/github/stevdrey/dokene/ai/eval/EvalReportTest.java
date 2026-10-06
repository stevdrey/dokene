package io.github.stevdrey.dokene.ai.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.stevdrey.dokene.ai.application.AiCompletionStatus;
import io.github.stevdrey.dokene.ai.application.AiInvocationMetadata;
import io.github.stevdrey.dokene.ai.application.AiOperation;
import io.github.stevdrey.dokene.ai.application.AiTokenUsage;
import io.github.stevdrey.dokene.ai.application.RecommendationContext;
import io.github.stevdrey.dokene.ai.domain.MessageDraft;
import io.github.stevdrey.dokene.ai.domain.DraftVariables;
import io.github.stevdrey.dokene.ai.domain.RecommendationConfidence;
import io.github.stevdrey.dokene.ai.domain.SemanticAction;
import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import io.github.stevdrey.dokene.ai.domain.TrustedFollowUpReason;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvalReportTest {
    private final EvalDataset dataset = EvalDatasetLoader.loadDefault();

    private static EvalProviderCall call(String name, Object outcome, long latencyMs) {
        var trusted = new RecommendationContext.TrustedFacts(LocalDate.of(2026, 10, 5), "OVERDUE",
                List.of(TrustedFollowUpReason.OVERDUE), 30, LocalDate.of(2026, 10, 4),
                true, List.of(Instant.parse("2026-08-01T12:00:00Z")), Arrays.asList(SemanticAction.values()));
        var ctx = new RecommendationContext(trusted, new RecommendationContext.UntrustedText(name, null, List.of("Café")));
        return new EvalProviderCall(name, AiOperation.MESSAGE_DRAFT, ctx, outcome, null,
                new AiInvocationMetadata("test-provider", "test-model", null, Duration.ofMillis(latencyMs),
                        new AiTokenUsage(100, 50), AiCompletionStatus.SUCCEEDED), latencyMs * 1_000_000);
    }

    private CaseObservation observation(String id, MessageDraft delivered, long latency) {
        EvalCase c = dataset.cases().stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
        return new CaseObservation(c, "NO_RECOMMENDATION", null, null, delivered == null ? "NO_DRAFT" : "AVAILABLE", null,
                delivered, delivered == null ? List.of() : List.of(call(c.displayName(), delivered, latency)));
    }

    private static MessageDraft draft(String body) {
        return new MessageDraft(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP, SemanticTemplateIntent.REPEAT_PURCHASE, body,
                DraftVariables.empty(), "es-419", List.of(), List.of(), "Basado en la compra.", RecommendationConfidence.of(0.7));
    }

    private EvalReport report(MessageDraft draft, String mode) {
        return EvalReportBuilder.build(dataset, List.of(observation("rp-01", draft, 20), observation("dc-01", draft, 40)),
                mode, "policy-a", new EvalReportBuilder.Pricing(1.0, 2.0), "live".equals(mode));
    }

    @Test
    void reportsHardInvariantsSeparatelyFromUsageAndCarriesNoAggregateScore() {
        EvalReport report = report(draft("Hola, ¿cómo te fue con tu compra?"), "live");

        assertThat(report.summary().allDeliveredInvariantsPass()).isTrue();
        assertThat(report.summary().usage().providerCalls()).isEqualTo(2);
        assertThat(report.summary().usage().reportedLatencyP50Ms()).isEqualTo(20);
        assertThat(report.summary().usage().reportedLatencyP95Ms()).isEqualTo(40);
        assertThat(report.summary().usage().inputTokens()).isEqualTo(200);
        assertThat(report.summary().usage().estimatedCostUsd()).isEqualTo(200 / 1e6 + 100 * 2 / 1e6);
        assertThat(report.provider()).isEqualTo("test-provider");
        assertThat(report.model()).isEqualTo("test-model");
        assertThat(report.contractFingerprint()).hasSize(16);
        assertThat(EvalReportWriter.toJson(report)).doesNotContain("score");
        assertThat(report.cases()).allSatisfy(c -> assertThat(c.rubric()).isEqualTo(EvalReport.Rubric.blank()));
    }

    @Test
    void costStaysNullWithoutAPriceTableAndWallLatencyIsOmittedForDeterministicRuns() {
        EvalReport report = EvalReportBuilder.build(dataset, List.of(observation("rp-01", draft("Hola"), 20)),
                "deterministic", null, null, false);

        assertThat(report.summary().usage().estimatedCostUsd()).isNull();
        assertThat(report.summary().usage().wallLatencyP50Ms()).isNull();
    }

    @Test
    void jsonRoundTripsAndMarkdownIsHumanReadable(@TempDir Path dir) throws Exception {
        EvalReport report = report(draft("Hola, ¿cómo te fue con tu compra?"), "live");
        Path json = EvalReportWriter.write(report, dir, "live");

        assertThat(EvalReportWriter.read(json)).isEqualTo(report);
        String markdown = Files.readString(dir.resolve("live.md"));
        assertThat(markdown).contains("# AI evaluation report", "Hard invariants", "ALL PASS", "Raw model findings",
                "Human rubric", "rp-01");
    }

    @Test
    void comparatorFlagsHardInvariantRegressionsAndOnlyDisplaysOtherDeltas() {
        EvalReport baseline = report(draft("Hola, ¿cómo te fue con tu compra?"), "live");
        EvalReport regressed = report(draft("Tienes 30% de descuento en https://promo.example.test"), "live");

        var same = EvalReportComparator.compare(baseline, baseline);
        assertThat(same.regression()).isFalse();
        assertThat(same.render()).contains("no hard-invariant regression");

        var worse = EvalReportComparator.compare(baseline, regressed);
        assertThat(worse.regression()).isTrue();
        assertThat(worse.render()).contains("REGRESSION", InvariantChecker.NO_UNSUPPORTED_OFFER_OR_LINK);
    }

    @Test
    void comparatorWarnsWhenDatasetOrContractChanged() {
        EvalReport baseline = report(draft("Hola"), "live");
        EvalReport other = new EvalReport(baseline.schemaVersion(), "2.0.0", baseline.mode(), baseline.provider(),
                baseline.model(), "ffffffffffffffff", baseline.promptPolicyLabel(), baseline.generatedAt(),
                baseline.summary(), baseline.cases());

        var result = EvalReportComparator.compare(baseline, other);
        assertThat(result.comparable()).isFalse();
        assertThat(result.warnings()).anyMatch(w -> w.contains("dataset versions differ"))
                .anyMatch(w -> w.contains("contract fingerprint"));
    }

    @Test
    void reportKeepsDeliveredContentForHumanGradingAndRendersIt(@TempDir Path dir) throws Exception {
        EvalReport report = EvalReportBuilder.build(dataset, List.of(
                observation("rp-01", draft("Hola Lucía, ¿cómo te fue con tu compra?"), 20),
                observation("rc-01", null, 20)), "live", null, null, true);

        EvalReport.CaseReport graded = report.cases().get(0);
        assertThat(graded.delivered().draft().body()).isEqualTo("Hola Lucía, ¿cómo te fue con tu compra?");
        assertThat(graded.delivered().draft().rationale()).isEqualTo("Basado en la compra.");
        assertThat(graded.delivered().draft().locale()).isEqualTo("es-419");
        assertThat(report.cases().get(1).delivered()).isNull();

        EvalReportWriter.write(report, dir, "content");
        assertThat(EvalReportWriter.read(dir.resolve("content.json"))).isEqualTo(report);
        assertThat(Files.readString(dir.resolve("content.md"))).contains("Content for grading",
                "Hola Lucía, ¿cómo te fue con tu compra?");
    }

    @Test
    void comparatorTreatsARemovedOrRenamedHardInvariantAsARegression() {
        EvalReport baseline = report(draft("Hola, ¿cómo te fue con tu compra?"), "live");
        var invariants = new java.util.LinkedHashMap<>(baseline.summary().deliveredInvariants());
        invariants.remove(InvariantChecker.NO_UNSUPPORTED_OFFER_OR_LINK);
        invariants.put("RENAMED_INVARIANT", new EvalReport.Tally(1, 1, 0));
        EvalReport.Summary s = baseline.summary();
        EvalReport candidate = new EvalReport(baseline.schemaVersion(), baseline.datasetVersion(), baseline.mode(),
                baseline.provider(), baseline.model(), baseline.contractFingerprint(), baseline.promptPolicyLabel(),
                baseline.generatedAt(), new EvalReport.Summary(s.totalCases(), invariants, true, s.unexpectedFailures(), s.behaviorMatches(),
                s.behaviorMismatches(), s.rawModelFindings(), s.operations(), s.usage()), baseline.cases());

        var result = EvalReportComparator.compare(baseline, candidate);

        assertThat(result.regression()).isTrue();
        assertThat(result.render()).contains(InvariantChecker.NO_UNSUPPORTED_OFFER_OR_LINK, "invariant removed or renamed");
    }

    @Test
    void comparatorTreatsADroppedBaselineCaseAsARegressionWhenTheDatasetVersionMatches() {
        EvalReport baseline = report(draft("Hola, ¿cómo te fue con tu compra?"), "live");
        EvalReport candidate = new EvalReport(baseline.schemaVersion(), baseline.datasetVersion(), baseline.mode(),
                baseline.provider(), baseline.model(), baseline.contractFingerprint(), baseline.promptPolicyLabel(),
                baseline.generatedAt(), baseline.summary(), baseline.cases().subList(0, 1));

        var result = EvalReportComparator.compare(baseline, candidate);

        assertThat(result.regression()).isTrue();
        assertThat(result.render()).contains("is missing from the candidate");
    }

    @Test
    void unexpectedRuntimeExceptionsAreCountedAndBreakTheComparison() {
        EvalCase c = dataset.cases().stream().filter(x -> x.id().equals("rp-01")).findFirst().orElseThrow();
        CaseObservation crashed = new CaseObservation(c, "EXCEPTION_IllegalStateException", null, null, "AVAILABLE", null,
                null, List.of());
        EvalReport live = EvalReportBuilder.build(dataset, List.of(crashed), "live", null, null, true);
        EvalReport deterministic = EvalReportBuilder.build(dataset, List.of(crashed), "deterministic", null, null, false);

        assertThat(live.summary().unexpectedFailures()).isEqualTo(1);
        assertThat(live.cases().getFirst().unexpectedFailure()).isTrue();
        assertThat(deterministic.summary().unexpectedFailures()).isEqualTo(1);

        EvalReport healthy = report(draft("Hola, ¿cómo te fue con tu compra?"), "live");
        assertThat(EvalReportComparator.compare(healthy, live).regression()).isTrue();
    }

    @Test
    void anExceptionPinnedByTheCaseExpectationIsNotUnexpectedInAnyMode() {
        EvalCase c = dataset.cases().stream().filter(x -> x.id().equals("ua-02")).findFirst().orElseThrow();
        CaseObservation rejected = new CaseObservation(c, "AVAILABLE", null, null, "EXCEPTION_IllegalArgumentException",
                null, null, List.of());

        assertThat(EvalReportBuilder.build(dataset, List.of(rejected), "deterministic", null, null, false)
                .summary().unexpectedFailures()).isZero();
        assertThat(EvalReportBuilder.build(dataset, List.of(rejected), "live", null, null, true)
                .summary().unexpectedFailures()).isZero();
    }

    @Test
    void operationsAreCoveredSeparatelySoAHealthyRecommendationPathCannotMaskFailingDrafts() {
        EvalCase c = dataset.cases().stream().filter(x -> x.id().equals("rp-01")).findFirst().orElseThrow();
        var rec = new io.github.stevdrey.dokene.ai.domain.ActionRecommendation(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP,
                SemanticTemplateIntent.REPEAT_PURCHASE, "Cadencia cumplida", RecommendationConfidence.of(0.8),
                DraftVariables.empty());
        EvalProviderCall recCall = call(c.displayName(), rec, 10);
        recCall = new EvalProviderCall(c.displayName(), AiOperation.NEXT_BEST_ACTION, recCall.context(), rec, null,
                recCall.metadata(), 1);
        EvalProviderCall failedDraft = new EvalProviderCall(c.displayName(), AiOperation.MESSAGE_DRAFT, recCall.context(),
                null, io.github.stevdrey.dokene.ai.application.AiFailureCategory.INVALID_STRUCTURED_RESPONSE, null, 1);
        CaseObservation obs = new CaseObservation(c, "AVAILABLE", null, rec, "AI_UNAVAILABLE", null, null,
                List.of(recCall, failedDraft));

        var operations = EvalReportBuilder.build(dataset, List.of(obs), "live", null, null, true).summary().operations();

        assertThat(operations.get("NEXT_BEST_ACTION")).isEqualTo(new EvalReport.Coverage(1, 1));
        assertThat(operations.get("MESSAGE_DRAFT")).isEqualTo(new EvalReport.Coverage(1, 0));
    }

    @Test
    void comparatorWarnsWhenEvaluationDatesDifferBecausePromptsAreConfounded() {
        EvalReport baseline = report(draft("Hola"), "live");
        EvalReport other = new EvalReport(baseline.schemaVersion(), baseline.datasetVersion(), "2030-01-01",
                baseline.mode(), baseline.provider(), baseline.model(), baseline.contractFingerprint(),
                baseline.promptPolicyLabel(), baseline.generatedAt(), baseline.summary(), baseline.cases());

        assertThat(EvalReportComparator.compare(baseline, other).warnings())
                .anyMatch(w -> w.contains("evaluation dates differ"));
        assertThat(baseline.evaluationDate()).isEqualTo(EvalRunner.EVAL_DATE.toString());
    }

    @Test
    void reportRecordsThePriceTableUsedAndTheComparatorWarnsWhenTablesDiffer(@TempDir Path dir) throws Exception {
        EvalReport priced = report(draft("Hola"), "live");
        assertThat(priced.summary().usage().inputUsdPerMillionTokens()).isEqualTo(1.0);
        assertThat(priced.summary().usage().outputUsdPerMillionTokens()).isEqualTo(2.0);
        assertThat(EvalReportWriter.toMarkdown(priced)).contains("Price table", "1.0 / 2.0");

        EvalReport repriced = EvalReportBuilder.build(dataset, List.of(observation("rp-01", draft("Hola"), 20),
                observation("dc-01", draft("Hola"), 40)), "live", "policy-a", new EvalReportBuilder.Pricing(3.0, 4.0), true);
        EvalReport unpriced = EvalReportBuilder.build(dataset, List.of(observation("rp-01", draft("Hola"), 20)),
                "deterministic", null, null, false);

        assertThat(EvalReportComparator.compare(priced, priced).warnings()).noneMatch(w -> w.contains("price tables"));
        assertThat(EvalReportComparator.compare(priced, repriced).warnings())
                .anyMatch(w -> w.contains("price tables differ"));
        assertThat(EvalReportComparator.compare(priced, repriced).render()).contains("1.0/2.0 -> 3.0/4.0");
        assertThat(EvalReportComparator.compare(priced, unpriced).warnings()).anyMatch(w -> w.contains("price tables differ"));
        assertThat(unpriced.summary().usage().inputUsdPerMillionTokens()).isNull();
    }
}
