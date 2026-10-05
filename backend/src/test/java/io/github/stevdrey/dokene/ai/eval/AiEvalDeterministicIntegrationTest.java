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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Deterministic, network-free run of the whole synthetic dataset through the production recommendation/draft
 * services, the real Action Gate and PostgreSQL (RLS), with a scripted provider that replays unsafe model output.
 * No API key or cost involved: this runs in the normal PR CI.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "dokene.ai.provider=fake",
        "dokene.eval.mode=deterministic",
        // Rate limiting is verified elsewhere; the evaluation issues one burst of calls from a single synthetic actor.
        "dokene.ai.rate-limit.tenant-permits-per-minute=10000",
        "dokene.ai.rate-limit.actor-permits-per-minute=10000"})
@Import(EvalProviderConfiguration.class)
class AiEvalDeterministicIntegrationTest {

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws SQLException {
        TenantSecurityIntegrationFixture.configure(registry);
        // The shared fixture pins the pool to one connection; gate-rejection audits run in an independent
        // transaction (REQUIRES_NEW) and need a second connection, as they have in the real application pool.
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
    void datasetRunsThroughRealGateWithAllDeliveredHardInvariantsHolding() throws Exception {
        EvalDataset dataset = EvalDatasetLoader.loadDefault();
        List<CaseObservation> observations = new EvalRunner(followUps, recommendations, drafts, customers, contacts,
                purchases, tenants, memberships, contexts, auditExecution, provider).run(dataset);

        EvalReport report = EvalReportBuilder.build(dataset, observations, "deterministic", "scripted", null, false);
        Path json = EvalReportWriter.write(report, Path.of("build", "reports", "ai-eval"), "deterministic");

        assertThat(json).exists();
        assertThat(report.summary().totalCases()).isEqualTo(dataset.cases().size());
        assertThat(report.cases()).allSatisfy(c -> assertThat(c.violations())
                .as("hard invariant violations for case %s", c.id()).isEmpty());
        assertThat(report.summary().allDeliveredInvariantsPass()).isTrue();
        assertMatchesCommittedBaseline(report);
        assertThat(report.cases()).allSatisfy(c -> assertThat(c.behaviorMatch())
                .as("pinned platform behavior for case %s: rec=%s/%s draft=%s/%s", c.id(), c.recommendationStatus(),
                        c.recommendationRejection(), c.draftStatus(), c.draftRejection()).isTrue());
    }

    /**
     * The deterministic report must be reproducible: identical to the committed baseline except for the timestamp.
     * A deliberate change (dataset, contract, gate behavior) requires regenerating the baseline; see
     * docs/verification/issue-98-ai-evaluation-verification.md.
     */
    private static void assertMatchesCommittedBaseline(EvalReport report) throws Exception {
        String baselineJson = new String(EvalProviderConfiguration.class
                .getResourceAsStream("/ai-eval/baselines/deterministic-v1.json").readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        EvalReport baseline = EvalReportWriter.fromJson(baselineJson);
        assertThat(EvalReportComparator.compare(baseline, report).regression()).isFalse();
        assertThat(withoutTimestamp(report))
                .as("deterministic report drifted from ai-eval/baselines/deterministic-v1.json; if intentional, "
                        + "copy build/reports/ai-eval/deterministic.json over it (generatedAt=normalized)")
                .isEqualTo(withoutTimestamp(baseline));
    }

    private static EvalReport withoutTimestamp(EvalReport r) {
        return new EvalReport(r.schemaVersion(), r.datasetVersion(), r.mode(), r.provider(), r.model(),
                r.contractFingerprint(), r.promptPolicyLabel(), "normalized", r.summary(), r.cases());
    }
}
