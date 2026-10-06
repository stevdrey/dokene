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
        EvalReport other = new EvalReport(baseline.schemaVersion(), "2.0.0", baseline.datasetFingerprint(), baseline.mode(), baseline.provider(),
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
    void markdownShowsDeliveredDraftVariablesSoReviewersGradeTheWholeOutput(@TempDir Path dir) throws Exception {
        MessageDraft withVariables = new MessageDraft(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP,
                SemanticTemplateIntent.REPEAT_PURCHASE, "Hola Lucía.",
                DraftVariables.of(java.util.Map.of("product", "Café Molido")), "es-419", List.of(), List.of(),
                "Basado en la compra.", RecommendationConfidence.of(0.7));
        EvalReport report = EvalReportBuilder.build(dataset, List.of(observation("rp-01", withVariables, 20)), "live",
                null, null, true);

        EvalReportWriter.write(report, dir, "variables");

        assertThat(Files.readString(dir.resolve("variables.md"))).contains("Variables: `product` = Café Molido");
    }

    @Test
    void comparatorTreatsARemovedOrRenamedHardInvariantAsARegression() {
        EvalReport baseline = report(draft("Hola, ¿cómo te fue con tu compra?"), "live");
        var invariants = new java.util.LinkedHashMap<>(baseline.summary().deliveredInvariants());
        invariants.remove(InvariantChecker.NO_UNSUPPORTED_OFFER_OR_LINK);
        invariants.put("RENAMED_INVARIANT", new EvalReport.Tally(1, 1, 0));
        EvalReport.Summary s = baseline.summary();
        EvalReport candidate = new EvalReport(baseline.schemaVersion(), baseline.datasetVersion(), baseline.datasetFingerprint(), baseline.mode(),
                baseline.provider(), baseline.model(), baseline.contractFingerprint(), baseline.promptPolicyLabel(),
                baseline.generatedAt(), new EvalReport.Summary(s.totalCases(), invariants, true, s.unexpectedFailures(), s.behaviorMatches(),
                s.behaviorMismatches(), s.rawModelFindings(), s.operations(), s.usage()), baseline.cases());

        var result = EvalReportComparator.compare(baseline, candidate);

        assertThat(result.regression()).isTrue();
        assertThat(result.render()).contains(InvariantChecker.NO_UNSUPPORTED_OFFER_OR_LINK, "invariant removed or renamed");
    }

    @Test
    void comparatorTreatsPartialLossOfInvariantCoverageAsARegression() {
        MessageDraft safe = draft("Hola, ¿cómo te fue con tu compra?");
        EvalReport baseline = report(safe, "live");
        EvalReport candidate = EvalReportBuilder.build(dataset,
                List.of(observation("rp-01", safe, 20), observation("dc-01", null, 40)), "live", "policy-a",
                new EvalReportBuilder.Pricing(1.0, 2.0), true);

        var result = EvalReportComparator.compare(baseline, candidate);

        assertThat(result.regression()).isTrue();
        assertThat(result.render()).contains("applicability: 2 -> 1", "PASS -> NOT_APPLICABLE");
        assertThat(EvalReportComparator.compare(baseline, baseline).regression()).isFalse();
    }

    private CaseObservation recommendationAndDraft(boolean draftDelivered) {
        EvalCase c = dataset.cases().stream().filter(x -> x.id().equals("rp-01")).findFirst().orElseThrow();
        var rec = new io.github.stevdrey.dokene.ai.domain.ActionRecommendation(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP,
                SemanticTemplateIntent.REPEAT_PURCHASE, "Cadencia cumplida", RecommendationConfidence.of(0.8),
                DraftVariables.empty());
        EvalProviderCall base = call(c.displayName(), rec, 10);
        EvalProviderCall recCall = new EvalProviderCall(c.displayName(), AiOperation.NEXT_BEST_ACTION, base.context(), rec,
                null, base.metadata(), 10_000_000);
        MessageDraft draft = draft("Hola, ¿cómo te fue con tu compra?");
        EvalProviderCall draftCall = draftDelivered ? call(c.displayName(), draft, 10)
                : new EvalProviderCall(c.displayName(), AiOperation.MESSAGE_DRAFT, base.context(), null,
                        io.github.stevdrey.dokene.ai.application.AiFailureCategory.INVALID_STRUCTURED_RESPONSE, null, 1);
        return new CaseObservation(c, "AVAILABLE", null, rec, draftDelivered ? "AVAILABLE" : "AI_UNAVAILABLE", null,
                draftDelivered ? draft : null, List.of(recCall, draftCall));
    }

    @Test
    void comparatorTreatsLowerDeliveryCoverageOfOneOperationAsARegression() {
        EvalReport baseline = EvalReportBuilder.build(dataset, List.of(recommendationAndDraft(true)), "live", null, null, true);
        EvalReport candidate = EvalReportBuilder.build(dataset, List.of(recommendationAndDraft(false)), "live", null, null, true);

        var result = EvalReportComparator.compare(baseline, candidate);

        assertThat(result.regression()).isTrue();
        assertThat(result.render()).contains("MESSAGE_DRAFT: 1/1 -> 0/1", "delivery coverage lost");
        assertThat(EvalReportComparator.compare(baseline, baseline).regression()).isFalse();
    }

    @Test
    void comparatorRefusesToCompareReportsOfDifferentSchemaVersions() {
        EvalReport baseline = report(draft("Hola, ¿cómo te fue con tu compra?"), "live");
        EvalReport older = new EvalReport(baseline.schemaVersion() - 1, baseline.datasetVersion(), baseline.datasetFingerprint(), baseline.mode(),
                baseline.provider(), baseline.model(), baseline.contractFingerprint(), baseline.promptPolicyLabel(),
                baseline.generatedAt(), baseline.summary(), baseline.cases());

        var result = EvalReportComparator.compare(older, baseline);

        assertThat(result.comparable()).isFalse();
        assertThat(result.regression()).isTrue();
        assertThat(result.render()).contains("report schema versions differ");
    }

    @Test
    void costIsWithheldAndFlaggedWhenAResponseCarriesNoTokenUsage() {
        EvalCase c = dataset.cases().stream().filter(x -> x.id().equals("rp-01")).findFirst().orElseThrow();
        MessageDraft draft = draft("Hola, ¿cómo te fue con tu compra?");
        EvalProviderCall full = call(c.displayName(), draft, 10);
        EvalProviderCall noUsage = new EvalProviderCall(c.displayName(), AiOperation.MESSAGE_DRAFT, full.context(), draft,
                null, new AiInvocationMetadata("test-provider", "test-model", null, Duration.ofMillis(10), null,
                        AiCompletionStatus.SUCCEEDED), 10_000_000);
        EvalProviderCall neverAnswered = new EvalProviderCall(c.displayName(), AiOperation.MESSAGE_DRAFT, full.context(),
                null, io.github.stevdrey.dokene.ai.application.AiFailureCategory.UNAVAILABLE, null, 1);
        var pricing = new EvalReportBuilder.Pricing(1.0, 2.0);
        CaseObservation complete = new CaseObservation(c, "AVAILABLE", null, null, "AVAILABLE", null, draft, List.of(full));
        CaseObservation partial = new CaseObservation(c, "AVAILABLE", null, null, "AVAILABLE", null, draft,
                List.of(full, noUsage, neverAnswered));

        var ok = EvalReportBuilder.build(dataset, List.of(complete), "live", null, pricing, true).summary().usage();
        var incomplete = EvalReportBuilder.build(dataset, List.of(partial), "live", null, pricing, true).summary().usage();

        assertThat(ok.estimatedCostUsd()).isNotNull();
        assertThat(ok.callsMissingUsage()).isZero();
        assertThat(incomplete.estimatedCostUsd()).isNull();
        assertThat(incomplete.callsMissingUsage()).isEqualTo(1);
    }

    @Test
    void wallLatencyIsTheEndToEndTimeOfTheOperationNotTheSumOfRawAttempts() {
        EvalCase c = dataset.cases().stream().filter(x -> x.id().equals("rp-01")).findFirst().orElseThrow();
        MessageDraft draft = draft("Hola, ¿cómo te fue con tu compra?");
        EvalProviderCall attempt = call(c.displayName(), draft, 5);
        // The raw attempt took 5 ms but the resilient operation (backoff included) took 2 s.
        CaseObservation retried = new CaseObservation(c, "AVAILABLE", null, null, "AVAILABLE", null, draft,
                List.of(attempt), null, null, CaseObservation.NOT_MEASURED, 2_000_000_000L);

        var usage = EvalReportBuilder.build(dataset, List.of(retried), "live", null, null, true).summary().usage();

        assertThat(usage.wallLatencyP50Ms()).isEqualTo(2000);
    }

    @Test
    void comparatorTreatsADroppedBaselineCaseAsARegressionWhenTheDatasetVersionMatches() {
        EvalReport baseline = report(draft("Hola, ¿cómo te fue con tu compra?"), "live");
        EvalReport candidate = new EvalReport(baseline.schemaVersion(), baseline.datasetVersion(), baseline.datasetFingerprint(), baseline.mode(),
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
    void onlyTheTypedExceptionPinnedByTheCaseExpectationIsNotUnexpected() {
        EvalCase c = dataset.cases().stream().filter(x -> x.id().equals("ua-02")).findFirst().orElseThrow();
        CaseObservation pinned = new CaseObservation(c, "AVAILABLE", null, null,
                "EXCEPTION_IncompatibleTemplateIntentException", null, null, List.of());
        CaseObservation unrelated = new CaseObservation(c, "AVAILABLE", null, null,
                "EXCEPTION_IllegalArgumentException", null, null, List.of());

        for (boolean live : new boolean[] {false, true}) {
            String mode = live ? "live" : "deterministic";
            assertThat(EvalReportBuilder.build(dataset, List.of(pinned), mode, null, null, live)
                    .summary().unexpectedFailures()).as(mode + " pinned").isZero();
            assertThat(EvalReportBuilder.build(dataset, List.of(unrelated), mode, null, null, live)
                    .summary().unexpectedFailures()).as(mode + " unrelated IAE").isEqualTo(1);
        }
    }

    @Test
    void comparatorShowsEndToEndWallLatencyAndRefusesDifferentDatasetContent() {
        EvalCase c = dataset.cases().stream().filter(x -> x.id().equals("rp-01")).findFirst().orElseThrow();
        MessageDraft draft = draft("Hola, ¿cómo te fue con tu compra?");
        EvalProviderCall attempt = call(c.displayName(), draft, 5);
        CaseObservation fast = new CaseObservation(c, "AVAILABLE", null, null, "AVAILABLE", null, draft,
                List.of(attempt), null, null, CaseObservation.NOT_MEASURED, 10_000_000L);
        CaseObservation slow = new CaseObservation(c, "AVAILABLE", null, null, "AVAILABLE", null, draft,
                List.of(attempt), null, null, CaseObservation.NOT_MEASURED, 3_000_000_000L);
        EvalReport baseline = EvalReportBuilder.build(dataset, List.of(fast), "live", null, null, true);
        EvalReport candidate = EvalReportBuilder.build(dataset, List.of(slow), "live", null, null, true);

        assertThat(EvalReportComparator.compare(baseline, candidate).render())
                .contains("Wall latency p50/p95 ms (end to end, retries included): 10/10 -> 3000/3000");

        EvalCase edited = new EvalCase(c.id(), c.family(), c.description(), c.displayName(), c.locale(),
                new EvalCase.Setup(c.setup().notes(), c.setup().archived(), c.setup().consent(),
                        c.setup().doNotContact(), c.setup().customerCadenceDays(),
                        c.setup().explicitNextFollowUpDaysFromToday(),
                        List.of(new EvalCase.PurchaseSpec(10, "Otro producto"))),
                c.request(), c.script(), c.expect(), c.rubricHints());
        EvalDataset changed = new EvalDataset(dataset.datasetVersion(), true, dataset.description(),
                java.util.stream.Stream.concat(java.util.stream.Stream.of(edited),
                        dataset.cases().stream().filter(x -> !x.id().equals(c.id()))).toList());
        assertThat(EvalRunner.datasetFingerprint(changed)).isNotEqualTo(EvalRunner.datasetFingerprint(dataset));
        EvalReport other = EvalReportBuilder.build(changed, List.of(fast), "live", null, null, true);

        var result = EvalReportComparator.compare(baseline, other);
        assertThat(result.comparable()).isFalse();
        assertThat(result.warnings()).anyMatch(w -> w.contains("dataset content fingerprints differ"));
    }

    @Test
    void theGradingReportCarriesTheScenarioFactsBehindEachCase(@TempDir Path dir) throws Exception {
        EvalReport report = EvalReportBuilder.build(dataset,
                List.of(observation("rp-01", draft("Hola Lucía, ¿cómo te fue con tu compra?"), 20)), "live", null,
                null, true);

        EvalReport.Scenario scenario = report.cases().getFirst().scenario();
        assertThat(scenario.setup().purchases()).isNotEmpty();
        assertThat(scenario.rubricHints()).isNotBlank();

        EvalReportWriter.write(report, dir, "scenario");
        assertThat(Files.readString(dir.resolve("scenario.md"))).contains("Scenario:", "Purchases:", "Grading hint:",
                scenario.setup().purchases().getFirst().description());
        assertThat(EvalReportWriter.read(dir.resolve("scenario.json"))).isEqualTo(report);
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
        EvalReport other = new EvalReport(baseline.schemaVersion(), baseline.datasetVersion(), baseline.datasetFingerprint(), "2030-01-01",
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
