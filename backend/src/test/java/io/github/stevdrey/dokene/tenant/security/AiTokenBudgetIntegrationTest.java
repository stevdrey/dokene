package io.github.stevdrey.dokene.tenant.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.github.stevdrey.dokene.ai.application.AiCompletionStatus;
import io.github.stevdrey.dokene.ai.application.AiDraftResponse;
import io.github.stevdrey.dokene.ai.application.AiFailureCategory;
import io.github.stevdrey.dokene.ai.application.AiInvocationMetadata;
import io.github.stevdrey.dokene.ai.application.AiProvider;
import io.github.stevdrey.dokene.ai.application.AiProviderException;
import io.github.stevdrey.dokene.ai.application.AiRecommendationResponse;
import io.github.stevdrey.dokene.ai.application.AiTokenUsage;
import io.github.stevdrey.dokene.audit.application.AuditExecutionContext;
import io.github.stevdrey.dokene.audit.application.AuditReader;
import io.github.stevdrey.dokene.audit.domain.AiAuditDetail;
import io.github.stevdrey.dokene.audit.domain.AiAuditOperation;
import io.github.stevdrey.dokene.audit.domain.AiAuditOutcome;
import io.github.stevdrey.dokene.audit.domain.AuditEvent;
import io.github.stevdrey.dokene.audit.domain.AuditEventType;
import io.github.stevdrey.dokene.audit.domain.AuditMetadata;
import io.github.stevdrey.dokene.customer.application.ContactPolicyService;
import io.github.stevdrey.dokene.customer.application.CustomerService;
import io.github.stevdrey.dokene.followup.application.AiTokenUsageRepository;
import io.github.stevdrey.dokene.followup.application.AiUnavailableReason;
import io.github.stevdrey.dokene.followup.application.DraftStatus;
import io.github.stevdrey.dokene.followup.application.FollowUpDraftResult;
import io.github.stevdrey.dokene.followup.application.FollowUpDraftService;
import io.github.stevdrey.dokene.followup.application.FollowUpQueueQuery;
import io.github.stevdrey.dokene.followup.application.FollowUpRecommendationResult;
import io.github.stevdrey.dokene.followup.application.FollowUpRecommendationService;
import io.github.stevdrey.dokene.followup.application.FollowUpService;
import io.github.stevdrey.dokene.followup.application.RecommendationStatus;
import io.github.stevdrey.dokene.purchase.application.PurchaseService;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Issue 346 / ADR 0035: the per-tenant monthly AI token budget against the real PostgreSQL store, row-level security,
 * retry decorator and audit, with a scripted provider that reports token usage.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "dokene.ai.provider=fake",
        "dokene.ai.token-budget.monthly-tokens=100",
        "dokene.ai.retry.max-attempts=2",
        "dokene.ai.retry.initial-backoff=10ms",
        "dokene.ai.retry.max-backoff=20ms",
        "dokene.ai.retry.min-attempt-budget=100ms"})
class AiTokenBudgetIntegrationTest {

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws SQLException {
        TenantSecurityIntegrationFixture.configure(registry);
    }

    @MockitoSpyBean AiProvider provider;
    @Autowired FollowUpService followUps;
    @Autowired FollowUpRecommendationService recommendations;
    @Autowired FollowUpDraftService drafts;
    @Autowired AiTokenUsageRepository usage;
    @Autowired CustomerService customers;
    @Autowired ContactPolicyService contacts;
    @Autowired PurchaseService purchases;
    @Autowired TenantRepository tenants;
    @Autowired TenantMembershipRepository memberships;
    @Autowired TenantContextProvider contexts;
    @Autowired AuditExecutionContext auditExecution;
    @Autowired AuditReader auditReader;
    @Autowired Clock clock;
    @Autowired JdbcTemplate jdbc;

    private AiInvocationSeed seed;
    private AiInvocationSeed.Seeded tenantA;
    private AiInvocationSeed.Seeded tenantB;

    @BeforeEach
    void setUp() throws Exception {
        Mockito.reset(provider);
        seed = new AiInvocationSeed(tenants, memberships, contexts, auditExecution, followUps, customers, contacts,
                purchases);
        tenantA = seed.dueCustomer("Budget Tenant A");
        tenantB = seed.dueCustomer("Budget Tenant B");
    }

    @Test
    void aTenantOverBudgetMakesNoProviderCallAndKeepsTheDeterministicWorkflow() throws Exception {
        reportUsageOnRecommend(new AiTokenUsage(70, 40));

        FollowUpRecommendationResult first = recommend(tenantA);
        assertThat(first.status()).isEqualTo(RecommendationStatus.AVAILABLE);
        assertThat(used(tenantA)).isEqualTo(110);

        FollowUpRecommendationResult second = recommend(tenantA);

        assertThat(second.status()).isEqualTo(RecommendationStatus.AI_UNAVAILABLE);
        assertThat(second.unavailableReason()).isEqualTo(AiUnavailableReason.TOKEN_BUDGET_EXHAUSTED);
        assertThat(second.unavailableReason().retryable()).isFalse();
        assertThat(second.evaluation()).isNotNull();
        verify(provider, times(1)).recommend(any());
        assertThat(aiEvents(tenantA)).extracting(AuditEvent::metadata).containsExactlyInAnyOrder(
                new AuditMetadata.AiInvocation(AiAuditOperation.NEXT_BEST_ACTION, AiAuditOutcome.GENERATED,
                        AiAuditDetail.NONE),
                new AuditMetadata.AiInvocation(AiAuditOperation.NEXT_BEST_ACTION, AiAuditOutcome.FAILED,
                        AiAuditDetail.TOKEN_BUDGET_EXHAUSTED));
        var queue = in(tenantA, () -> followUps.dueQueue(new FollowUpQueueQuery(null, null, 10)));
        assertThat(queue.items()).anyMatch(item -> item.customerId().equals(tenantA.customer().id()));
    }

    @Test
    void anExhaustedTenantGetsNoDraftEitherAndNoProviderCall() throws Exception {
        in(tenantA, () -> {
            usage.add(tenantA.tenantContext().tenantId(), period(), 100, 0);
            return null;
        });

        FollowUpDraftResult result = in(tenantA, () -> drafts.draftSafe(tenantA.customer().id(), null, null, null, null));

        assertThat(result.status()).isEqualTo(DraftStatus.AI_UNAVAILABLE);
        assertThat(result.unavailableReason()).isEqualTo(AiUnavailableReason.TOKEN_BUDGET_EXHAUSTED);
        verify(provider, never()).draft(any());
        assertThat(aiEvents(tenantA)).singleElement().satisfies(event -> assertThat(event.metadata())
                .isEqualTo(new AuditMetadata.AiInvocation(AiAuditOperation.MESSAGE_DRAFT, AiAuditOutcome.FAILED,
                        AiAuditDetail.TOKEN_BUDGET_EXHAUSTED)));
    }

    @Test
    void draftUsageIsCountedAgainstTheSameBudget() throws Exception {
        doAnswer(invocation -> {
            AiDraftResponse real = (AiDraftResponse) invocation.callRealMethod();
            return new AiDraftResponse(real.outcome(), withUsage(real.metadata(), new AiTokenUsage(20, 15)));
        }).when(provider).draft(any());

        in(tenantA, () -> drafts.draftSafe(tenantA.customer().id(), null, null, null, null));

        verify(provider, times(1)).draft(any());
        assertThat(used(tenantA)).isEqualTo(35);
    }

    @Test
    void failedAndRetriedAttemptsAreCountedBecauseTheProviderBillsThem() throws Exception {
        AiTokenUsage failedAttempt = new AiTokenUsage(30, 10);
        doAnswer(invocation -> {
            throw new AiProviderException(AiFailureCategory.THROTTLED, new AiInvocationMetadata("fake-provider",
                    "deterministic-fake", null, Duration.ofMillis(5), failedAttempt, AiCompletionStatus.FAILED));
        }).doAnswer(invocation -> {
            AiRecommendationResponse real = (AiRecommendationResponse) invocation.callRealMethod();
            return new AiRecommendationResponse(real.outcome(), withUsage(real.metadata(), new AiTokenUsage(5, 5)));
        }).when(provider).recommend(any());

        FollowUpRecommendationResult result = recommend(tenantA);

        assertThat(result.status()).isEqualTo(RecommendationStatus.AVAILABLE);
        verify(provider, times(2)).recommend(any());
        assertThat(used(tenantA)).isEqualTo(50);
    }

    @Test
    void aTerminalProviderFailureStillConsumesTheReportedBudget() throws Exception {
        doAnswer(invocation -> {
            throw new AiProviderException(AiFailureCategory.INVALID_STRUCTURED_RESPONSE, new AiInvocationMetadata(
                    "fake-provider", "deterministic-fake", null, Duration.ofMillis(5), new AiTokenUsage(80, 25),
                    AiCompletionStatus.FAILED));
        }).when(provider).recommend(any());

        FollowUpRecommendationResult result = recommend(tenantA);

        assertThat(result.unavailableReason()).isEqualTo(AiUnavailableReason.INVALID_STRUCTURED_RESPONSE);
        assertThat(used(tenantA)).isEqualTo(105);
        assertThat(recommend(tenantA).unavailableReason()).isEqualTo(AiUnavailableReason.TOKEN_BUDGET_EXHAUSTED);
        verify(provider, times(1)).recommend(any());
    }

    @Test
    void theBudgetIsIsolatedPerTenant() throws Exception {
        in(tenantA, () -> {
            usage.add(tenantA.tenantContext().tenantId(), period(), 60, 40);
            return null;
        });
        reportUsageOnRecommend(new AiTokenUsage(4, 3));

        assertThat(recommend(tenantA).unavailableReason()).isEqualTo(AiUnavailableReason.TOKEN_BUDGET_EXHAUSTED);
        FollowUpRecommendationResult other = recommend(tenantB);

        assertThat(other.status()).isEqualTo(RecommendationStatus.AVAILABLE);
        verify(provider, times(1)).recommend(any());
        assertThat(used(tenantB)).isEqualTo(7);
        assertThat(used(tenantA)).isEqualTo(100);
        // row-level security: tenant B can neither read nor change tenant A's counter
        assertThat(in(tenantB, () -> usage.usedTokens(tenantA.tenantContext().tenantId(), period()))).isZero();
        assertThatThrownBy(() -> in(tenantB, () -> {
            usage.add(tenantA.tenantContext().tenantId(), period(), 1, 1);
            return null;
        })).rootCause().hasMessageContaining("row-level security");
        assertThat(used(tenantA)).isEqualTo(100);
    }

    @Test
    void theRuntimeRoleCannotDeleteAUsageCounter() throws Exception {
        in(tenantA, () -> {
            usage.add(tenantA.tenantContext().tenantId(), period(), 1, 1);
            return null;
        });
        try (Connection connection = TenantSecurityIntegrationFixture.runtimeConnectionWithoutContext();
                Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.execute("DELETE FROM dokene.ai_token_usage"))
                    .isInstanceOf(SQLException.class);
        }
        assertThat(used(tenantA)).isEqualTo(2);
    }

    /** The month the service charges, from the same clock bean, so a run across a month boundary cannot disagree. */
    private LocalDate period() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).withDayOfMonth(1);
    }

    @Test
    void theRuntimeRoleCanOnlyIncreaseACounter() throws Exception {
        in(tenantA, () -> {
            usage.add(tenantA.tenantContext().tenantId(), period(), 40, 30);
            return null;
        });

        for (String change : List.of("input_tokens = 0", "output_tokens = output_tokens - 1",
                "period_start = period_start - INTERVAL '1 month'")) {
            assertThatThrownBy(() -> in(tenantA, () -> jdbc.update(
                    "UPDATE dokene.ai_token_usage SET " + change + " WHERE tenant_id = ?",
                    tenantA.tenantContext().tenantId().value())))
                    .as(change).rootCause().hasMessageContaining("AI token usage can only increase");
        }
        assertThat(used(tenantA)).isEqualTo(70);
        in(tenantA, () -> {
            usage.add(tenantA.tenantContext().tenantId(), period(), 1, 2);
            return null;
        });
        assertThat(used(tenantA)).isEqualTo(73);
    }

    private void reportUsageOnRecommend(AiTokenUsage reported) {
        doAnswer(invocation -> {
            AiRecommendationResponse real = (AiRecommendationResponse) invocation.callRealMethod();
            return new AiRecommendationResponse(real.outcome(), withUsage(real.metadata(), reported));
        }).when(provider).recommend(any());
    }

    private static AiInvocationMetadata withUsage(AiInvocationMetadata metadata, AiTokenUsage reported) {
        return new AiInvocationMetadata(metadata.providerId(), metadata.modelId(), metadata.providerRequestId(),
                metadata.latency(), reported, metadata.status());
    }

    private FollowUpRecommendationResult recommend(AiInvocationSeed.Seeded tenant) throws Exception {
        return in(tenant, () -> recommendations.recommendSafe(tenant.customer().id(), null, null));
    }

    private long used(AiInvocationSeed.Seeded tenant) throws Exception {
        return in(tenant, () -> usage.usedTokens(tenant.tenantContext().tenantId(), period()));
    }

    private List<AuditEvent> aiEvents(AiInvocationSeed.Seeded tenant) throws Exception {
        return in(tenant, () -> auditReader.read().events().stream()
                .filter(event -> event.type() == AuditEventType.AI_INVOCATION_OUTCOME)
                .toList());
    }

    private <T> T in(AiInvocationSeed.Seeded tenant, Callable<T> operation) throws Exception {
        return seed.inContext(tenant.tenantContext(), operation);
    }
}
