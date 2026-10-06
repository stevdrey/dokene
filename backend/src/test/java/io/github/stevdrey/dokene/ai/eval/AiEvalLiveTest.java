package io.github.stevdrey.dokene.ai.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.stevdrey.dokene.ai.application.AiProvider;
import io.github.stevdrey.dokene.audit.application.AuditExecutionContext;
import io.github.stevdrey.dokene.customer.application.ContactPolicyService;
import io.github.stevdrey.dokene.customer.application.CustomerService;
import io.github.stevdrey.dokene.followup.application.FollowUpDraftService;
import io.github.stevdrey.dokene.followup.application.FollowUpRecommendationService;
import io.github.stevdrey.dokene.followup.application.FollowUpService;
import io.github.stevdrey.dokene.purchase.application.PurchaseService;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * OPT-IN live evaluation of the configured hosted provider against the synthetic dataset. It never runs in
 * {@code ./gradlew test} (excluded by tag) and is additionally skipped unless {@code DOKENE_AI_EVAL_LIVE=true}.
 * Run with {@code ./gradlew aiEvalLive}; it spends real provider tokens, needs {@code DOKENE_AI_OPENAI_API_KEY}, and
 * sends only invented synthetic data. The application services, Action Gate and database are the production ones.
 *
 * <p>Pass/fail is decided by the delivered-layer hard invariants only. Quality is assessed by humans from the report.
 */
@Tag("ai-eval-live")
@EnabledIfEnvironmentVariable(named = "DOKENE_AI_EVAL_LIVE", matches = "true")
@EnabledIfEnvironmentVariable(named = "DOKENE_AI_OPENAI_API_KEY", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "dokene.ai.provider=openai",
        "dokene.eval.mode=live",
        "dokene.ai.rate-limit.tenant-permits-per-minute=10000",
        "dokene.ai.rate-limit.actor-permits-per-minute=10000"})
@Import(EvalProviderConfiguration.class)
class AiEvalLiveTest {

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws SQLException {
        TenantSecurityIntegrationFixture.configure(registry);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
    }

    @Autowired FollowUpService followUps;
    @Autowired FollowUpRecommendationService recommendations;
    @Autowired FollowUpDraftService drafts;
    @Autowired CustomerService customers;
    @Autowired ContactPolicyService contacts;
    @Autowired PurchaseService purchases;
    @Autowired TenantRepository tenants;
    @Autowired TenantMembershipRepository memberships;
    @Autowired TenantContextProvider contexts;
    @Autowired AuditExecutionContext auditExecution;
    @Autowired AiProvider provider;

    @Test
    void liveProviderRespectsEveryDeliveredHardInvariant() throws Exception {
        // Every live setting is validated before the first paid provider call: a typo must not cost a full run.
        double minSuccessRatio = EvalLiveSettings.minSuccessRatio(env("DOKENE_AI_EVAL_MIN_SUCCESS_RATIO"));
        EvalReportBuilder.Pricing pricing = pricing();
        String reportName = EvalLiveSettings.reportName(env("DOKENE_AI_EVAL_REPORT_NAME"));
        EvalDataset dataset = EvalDatasetLoader.loadDefault();
        List<CaseObservation> observations = new EvalRunner(followUps, recommendations, drafts, customers, contacts,
                purchases, tenants, memberships, contexts, auditExecution, provider).run(dataset);

        EvalReport report = EvalReportBuilder.build(dataset, observations, "live", env("DOKENE_AI_EVAL_PROMPT_POLICY"),
                pricing, true);
        Path json = EvalReportWriter.write(report, Path.of("build", "reports", "ai-eval"), reportName);

        assertThat(json).exists();
        assertThat(report.summary().unexpectedFailures())
                .as("unexpected runtime exceptions break the experiment: %s", report.cases().stream()
                        .filter(EvalReport.CaseReport::unexpectedFailure).map(EvalReport.CaseReport::id).toList())
                .isZero();
        assertEnoughSuccessfulOutputs(report, minSuccessRatio);
        assertThat(report.cases()).allSatisfy(c -> assertThat(c.violations())
                .as("hard invariant violations for case %s", c.id()).isEmpty());
    }

    /**
     * Provider outages, invalid keys or wholesale invalid output surface as AI_UNAVAILABLE results, not exceptions,
     * which would leave every output-dependent invariant not applicable. Each operation is checked separately so a
     * healthy recommendation path cannot mask a wholesale draft failure (or the reverse): a minimum share of the
     * cases that reached the provider must have produced a delivered outcome (action, draft or refusal).
     */
    private static void assertEnoughSuccessfulOutputs(EvalReport report, double minRatio) {
        report.summary().operations().forEach((operation, coverage) -> {
            assertThat(coverage.invokedCases()).as("%s: cases that reached the provider", operation).isPositive();
            assertThat((double) coverage.deliveredCases() / coverage.invokedCases())
                    .as("%s: share of provider-invoked cases with a delivered outcome (%s of %s); provider "
                            + "unavailable or output invalid?", operation, coverage.deliveredCases(),
                            coverage.invokedCases())
                    .isGreaterThanOrEqualTo(minRatio);
        });
    }

    private static EvalReportBuilder.Pricing pricing() {
        return EvalLiveSettings.pricing(env("DOKENE_AI_EVAL_PRICE_INPUT_PER_MTOK"),
                env("DOKENE_AI_EVAL_PRICE_OUTPUT_PER_MTOK"));
    }

    private static String env(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : value;
    }
}
