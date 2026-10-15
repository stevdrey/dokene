package io.github.stevdrey.dokene.tenant.security;

import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.context;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedMembership;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Spike evidence for Issue #180 (ADR 0026) against the real durable audit path (PostgreSQL, Hikari, the
 * {@code append_audit_event} function): is a won cancel still audited as {@code FAILED/CANCELLED} on the request
 * thread? Case A reproduces what {@code OpenAiResponsesApiAdapter} does today for a {@code CancellationException}
 * (re-asserts the interrupt flag); case B is the proposed behaviour for an explicit cancel (flag not left set).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "dokene.ai.provider=fake",
        "dokene.ai.retry.max-attempts=2",
        "dokene.ai.retry.initial-backoff=10ms",
        "dokene.ai.retry.max-backoff=20ms",
        "dokene.ai.retry.min-attempt-budget=100ms"})
class CancelledInvocationDurableAuditSpikeIntegrationTest {

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws SQLException {
        TenantSecurityIntegrationFixture.configure(registry);
    }

    @MockitoSpyBean AiProvider provider;
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

    private TenantContext tenantContext;
    private Customer customer;

    @BeforeEach
    void setUp() throws Exception {
        Mockito.reset(provider);
        Instant now = Instant.now();
        Tenant tenant = seedTenant(tenants, contexts, "AI Cancel Tenant " + UUID.randomUUID(), now);
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
    void caseA_cancelledWithTheInterruptFlagLeftSetByTheAdapter() throws Exception {
        doAnswer(invocation -> {
            Thread.currentThread().interrupt();
            throw cancelled();
        }).when(provider).recommend(any());

        Object observed;
        boolean stillInterrupted;
        try {
            observed = inContext(() -> recommendations.recommendSafe(customer.id(), null, null));
        } catch (Throwable t) {
            observed = t;
        } finally {
            stillInterrupted = Thread.interrupted();
        }

        List<AuditEvent> events = aiEvents();
        System.out.printf("[spike-180] durable audit, interrupt flag left set: result=%s, flagStillSet=%s, aiAuditRows=%d%n",
                describe(observed), stillInterrupted, events.size());
        assertObservedCaseA(observed, stillInterrupted, events);
    }

    @Test
    void caseB_cancelledWithoutTheInterruptFlagIsDurablyAuditedOnce() throws Exception {
        doThrow(cancelled()).when(provider).recommend(any());

        FollowUpRecommendationResult result = inContext(() -> recommendations.recommendSafe(customer.id(), null, null));

        assertThat(result.status()).isEqualTo(RecommendationStatus.AI_UNAVAILABLE);
        assertThat(result.unavailableReason()).isEqualTo(AiUnavailableReason.CANCELLED);
        Mockito.verify(provider, Mockito.times(1)).recommend(any());
        assertThat(aiEvents()).singleElement().satisfies(event -> {
            assertThat(event.metadata()).isEqualTo(new AuditMetadata.AiInvocation(
                    AiAuditOperation.NEXT_BEST_ACTION, AiAuditOutcome.FAILED, AiAuditDetail.CANCELLED));
            assertThat(event.target().id()).isEqualTo(customer.id().value());
        });
    }

    /**
     * Encodes the behaviour observed on JDK 26 / Hikari / PostgreSQL 17 (see ADR 0026, interrupt-flag row): with an
     * idle pooled connection the durable row is written despite the flag, but the flag survives the request, so a
     * pooled worker thread would carry it into its next request. A borrow that has to wait for a connection is not
     * exercised here.
     */
    private void assertObservedCaseA(Object observed, boolean stillInterrupted, List<AuditEvent> events) {
        assertThat(observed).isInstanceOfSatisfying(FollowUpRecommendationResult.class,
                result -> assertThat(result.unavailableReason()).isEqualTo(AiUnavailableReason.CANCELLED));
        assertThat(events).singleElement().satisfies(event -> assertThat(event.metadata()).isEqualTo(
                new AuditMetadata.AiInvocation(AiAuditOperation.NEXT_BEST_ACTION, AiAuditOutcome.FAILED,
                        AiAuditDetail.CANCELLED)));
        assertThat(stillInterrupted).as("the interrupt flag leaks past the request").isTrue();
    }

    private static String describe(Object observed) {
        if (observed instanceof FollowUpRecommendationResult result) {
            return result.status() + "/" + result.unavailableReason();
        }
        if (observed instanceof Throwable t) {
            Throwable root = t;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            return t.getClass().getSimpleName() + " (root " + root.getClass().getSimpleName() + ": "
                    + root.getMessage() + ")";
        }
        return String.valueOf(observed);
    }

    private List<AuditEvent> aiEvents() throws Exception {
        return inContext(() -> auditReader.read().events().stream()
                .filter(event -> event.type() == AuditEventType.AI_INVOCATION_OUTCOME)
                .toList());
    }

    private static AiProviderException cancelled() {
        return new AiProviderException(AiFailureCategory.CANCELLED, new AiInvocationMetadata("fake-provider",
                "deterministic-fake", null, Duration.ofMillis(5), null, AiCompletionStatus.CANCELLED));
    }

    private <T> T inContext(Callable<T> operation) throws Exception {
        return auditExecution.callWithCorrelation(UUID.randomUUID(),
                () -> contexts.callWithContext(tenantContext, operation::call));
    }
}
