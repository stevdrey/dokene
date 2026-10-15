package io.github.stevdrey.dokene.tenant.security;

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
import io.github.stevdrey.dokene.customer.domain.Customer;
import io.github.stevdrey.dokene.followup.application.AiUnavailableReason;
import io.github.stevdrey.dokene.followup.application.FollowUpRecommendationResult;
import io.github.stevdrey.dokene.followup.application.FollowUpRecommendationService;
import io.github.stevdrey.dokene.followup.application.FollowUpService;
import io.github.stevdrey.dokene.followup.application.RecommendationStatus;
import io.github.stevdrey.dokene.purchase.application.PurchaseService;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import java.io.InterruptedIOException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
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
@Tag("spike")
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

    private AiInvocationSeed seed;
    private TenantContext tenantContext;
    private Customer customer;

    @BeforeEach
    void setUp() throws Exception {
        Mockito.reset(provider);
        seed = new AiInvocationSeed(tenants, memberships, contexts, auditExecution, followUps, customers, contacts,
                purchases);
        AiInvocationSeed.Seeded seeded = seed.dueCustomer("AI Cancel Tenant");
        tenantContext = seeded.tenantContext();
        customer = seeded.customer();
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
     * The flag survives the request in every environment: nothing on the request path clears it, which is the risk
     * ADR 0026 records. Whether the durable write survives it depends on the pool: with an idle connection the row
     * is written (what was observed locally); when the borrow has to wait, Hikari's interruptible wait may fail the
     * write instead. Both are accepted here, so the test does not depend on pool timing, but nothing else is.
     */
    private void assertObservedCaseA(Object observed, boolean stillInterrupted, List<AuditEvent> events) {
        assertThat(stillInterrupted).as("the interrupt flag outlives the request").isTrue();
        if (observed instanceof FollowUpRecommendationResult result) {
            assertThat(result.unavailableReason()).isEqualTo(AiUnavailableReason.CANCELLED);
            assertThat(events).singleElement().satisfies(event -> assertThat(event.metadata()).isEqualTo(
                    new AuditMetadata.AiInvocation(AiAuditOperation.NEXT_BEST_ACTION, AiAuditOutcome.FAILED,
                            AiAuditDetail.CANCELLED)));
        } else {
            assertThat(observed).as("only an interrupted audit write may fail the request").isInstanceOf(Throwable.class);
            assertThat(causedByInterruption((Throwable) observed)).as(describe(observed)).isTrue();
            assertThat(events).isEmpty();
        }
    }

    private static boolean causedByInterruption(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof InterruptedException || t instanceof InterruptedIOException
                    || String.valueOf(t.getMessage()).toLowerCase(Locale.ROOT).contains("interrupt")) {
                return true;
            }
        }
        return false;
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
        return seed.inContext(tenantContext, operation);
    }
}
