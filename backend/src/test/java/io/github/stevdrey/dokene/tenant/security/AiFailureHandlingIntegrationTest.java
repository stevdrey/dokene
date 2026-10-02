package io.github.stevdrey.dokene.tenant.security;

import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.context;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedMembership;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.github.stevdrey.dokene.ai.application.AiCompletionStatus;
import io.github.stevdrey.dokene.ai.application.AiFailureCategory;
import io.github.stevdrey.dokene.ai.application.AiInvocationMetadata;
import io.github.stevdrey.dokene.ai.application.AiProvider;
import io.github.stevdrey.dokene.ai.application.AiProviderException;
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
import io.github.stevdrey.dokene.customer.application.CustomerService.PhoneInput;
import io.github.stevdrey.dokene.customer.domain.ConsentStatus;
import io.github.stevdrey.dokene.customer.domain.ContactChannel;
import io.github.stevdrey.dokene.customer.domain.ContactIntentSource;
import io.github.stevdrey.dokene.customer.domain.Customer;
import io.github.stevdrey.dokene.followup.application.AiUnavailableReason;
import io.github.stevdrey.dokene.followup.application.FollowUpQueueQuery;
import io.github.stevdrey.dokene.followup.application.FollowUpRecommendationResult;
import io.github.stevdrey.dokene.followup.application.FollowUpRecommendationService;
import io.github.stevdrey.dokene.followup.application.FollowUpService;
import io.github.stevdrey.dokene.followup.application.RecommendationStatus;
import io.github.stevdrey.dokene.purchase.application.PurchaseService;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * End-to-end behavior of AI failure handling against the real gate, audit store and metrics registry, with a
 * scripted provider. Proves that the deterministic follow-up queue stays usable whatever the provider does.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "dokene.ai.provider=fake",
        "dokene.ai.retry.max-attempts=2",
        "dokene.ai.retry.initial-backoff=10ms",
        "dokene.ai.retry.max-backoff=20ms",
        "dokene.ai.retry.min-attempt-budget=100ms"})
class AiFailureHandlingIntegrationTest {

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws SQLException {
        TenantSecurityIntegrationFixture.configure(registry);
    }

    @MockitoSpyBean AiProvider provider;
    @Autowired ApplicationContext applicationContext;
    @Autowired FollowUpService followUps;
    @Autowired FollowUpRecommendationService recommendations;
    @Autowired CustomerService customers;
    @Autowired ContactPolicyService contacts;
    @Autowired PurchaseService purchases;
    @Autowired TenantRepository tenants;
    @Autowired TenantMembershipRepository memberships;
    @Autowired TenantContextProvider contexts;
    @Autowired AuditExecutionContext auditExecution;
    @Autowired AuditReader auditReader;
    @Autowired MeterRegistry meters;

    private TenantContext tenantContext;
    private Customer customer;

    @BeforeEach
    void setUp() throws Exception {
        Mockito.reset(provider);
        Instant now = Instant.now();
        Tenant tenant = seedTenant(tenants, "AI Failure Tenant " + UUID.randomUUID(), now);
        tenantContext = context(seedMembership(memberships, contexts, tenant.id(),
                new IdentityId(UUID.randomUUID()), TenantRole.OWNER, now));
        inContext(() -> followUps.configureTenant(14, ZoneId.of("America/Costa_Rica"), 0L));
        customer = inContext(() -> customers.create("Jane Doe", null,
                List.of(new PhoneInput("8888" + String.format("%04d", Math.abs(UUID.randomUUID().hashCode()) % 10000),
                        "CR", true))));
        UUID contactId = customer.phones().getFirst().id();
        var contactPolicy = inContext(() -> contacts.get(customer.id()));
        inContext(() -> contacts.changeConsent(customer.id(), contactId, ContactChannel.WHATSAPP,
                ConsentStatus.GRANTED, ContactIntentSource.CUSTOMER_WRITTEN, contactPolicy.version()));
        Instant past = LocalDate.now(ZoneId.of("America/Costa_Rica")).minusDays(20)
                .atStartOfDay(ZoneId.of("America/Costa_Rica")).toInstant();
        inContext(() -> purchases.record(customer.id(), past, "Coffee Beans", "order-" + UUID.randomUUID()));
    }

    @Test
    void temporaryProviderRecoveryYieldsAvailableAndAuditsOnlyTheFinalOutcome() throws Exception {
        double retriesBefore = counter("dokene.ai.retries");
        doThrow(failure(AiFailureCategory.THROTTLED)).doCallRealMethod().when(provider).recommend(any());

        FollowUpRecommendationResult result = inContext(() -> recommendations.recommendSafe(customer.id(), null, null));

        assertThat(result.status()).isEqualTo(RecommendationStatus.AVAILABLE);
        verify(provider, times(2)).recommend(any());
        assertThat(counter("dokene.ai.retries")).isEqualTo(retriesBefore + 1);
        assertThat(aiEvents()).singleElement().satisfies(event -> {
            assertThat(event.metadata()).isEqualTo(new AuditMetadata.AiInvocation(
                    AiAuditOperation.NEXT_BEST_ACTION, AiAuditOutcome.GENERATED, AiAuditDetail.NONE));
            assertThat(event.target().id()).isEqualTo(customer.id().value());
        });
    }

    @Test
    void persistentProviderOutageDegradesToRetryableUnavailableAndKeepsTheQueueUsable() throws Exception {
        doThrow(failure(AiFailureCategory.UNAVAILABLE)).when(provider).recommend(any());

        FollowUpRecommendationResult result = inContext(() -> recommendations.recommendSafe(customer.id(), null, null));

        assertThat(result.status()).isEqualTo(RecommendationStatus.AI_UNAVAILABLE);
        assertThat(result.unavailableReason()).isEqualTo(AiUnavailableReason.UNAVAILABLE);
        assertThat(result.unavailableReason().retryable()).isTrue();
        verify(provider, times(2)).recommend(any());
        assertThat(aiEvents()).singleElement().satisfies(event -> assertThat(event.metadata())
                .isEqualTo(new AuditMetadata.AiInvocation(AiAuditOperation.NEXT_BEST_ACTION, AiAuditOutcome.FAILED,
                        AiAuditDetail.UNAVAILABLE)));
        var queue = inContext(() -> followUps.dueQueue(new FollowUpQueueQuery(null, null, 10)));
        assertThat(queue.items()).anyMatch(item -> item.customerId().equals(customer.id()));
    }

    @Test
    void malformedStructuredOutputIsNotRetriedAndIsAuditedWithItsCategory() throws Exception {
        doThrow(failure(AiFailureCategory.INVALID_STRUCTURED_RESPONSE)).when(provider).recommend(any());

        FollowUpRecommendationResult result = inContext(() -> recommendations.recommendSafe(customer.id(), null, null));

        assertThat(result.unavailableReason()).isEqualTo(AiUnavailableReason.INVALID_STRUCTURED_RESPONSE);
        assertThat(result.unavailableReason().retryable()).isFalse();
        verify(provider, times(1)).recommend(any());
        assertThat(aiEvents()).singleElement().satisfies(event -> assertThat(event.metadata())
                .isEqualTo(new AuditMetadata.AiInvocation(AiAuditOperation.NEXT_BEST_ACTION, AiAuditOutcome.FAILED,
                        AiAuditDetail.INVALID_STRUCTURED_RESPONSE)));
    }

    @Test
    void unexpectedProviderRuntimeFailureNeverEscapesAndNeverLeaksItsMessage() throws Exception {
        doThrow(new IllegalStateException("sk-secret-key +593991234567")).when(provider).recommend(any());

        FollowUpRecommendationResult result = inContext(() -> recommendations.recommendSafe(customer.id(), null, null));

        assertThat(result.status()).isEqualTo(RecommendationStatus.AI_UNAVAILABLE);
        assertThat(result.unavailableReason()).isEqualTo(AiUnavailableReason.UNAVAILABLE);
        assertThat(aiEvents().toString()).doesNotContain("secret").doesNotContain("991234567");
    }

    @Test
    void metricsRegistryIsAvailableWithoutExposingAnyActuatorEndpoint() {
        assertThat(meters).isNotNull();
        assertThat(applicationContext.getBeanNamesForAnnotation(
                org.springframework.boot.actuate.endpoint.annotation.Endpoint.class)).isEmpty();
        assertThat(applicationContext.getEnvironment().getProperty("management.endpoints.access.default"))
                .isEqualTo("none");
    }

    private double counter(String name) {
        return meters.find(name).counters().stream().mapToDouble(c -> c.count()).sum();
    }

    private List<AuditEvent> aiEvents() throws Exception {
        return inContext(() -> auditReader.read().events().stream()
                .filter(event -> event.type() == AuditEventType.AI_INVOCATION_OUTCOME)
                .toList());
    }

    private AiProviderException failure(AiFailureCategory category) {
        return new AiProviderException(category, new AiInvocationMetadata("fake-provider", "deterministic-fake", null,
                Duration.ofMillis(5), null, AiCompletionStatus.FAILED));
    }

    private <T> T inContext(Callable<T> operation) throws Exception {
        return auditExecution.callWithCorrelation(UUID.randomUUID(),
                () -> contexts.callWithContext(tenantContext, operation::call));
    }
}
