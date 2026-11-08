// ABOUTME: Unit tests for the approval refresh budget (issue 321): provider requests are metered per page, local answers
// ABOUTME: cost nothing, members are held to their share, denials are audited once per window, and retries recover.
package io.github.stevdrey.dokene.template.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import io.github.stevdrey.dokene.tenant.application.TenantAuthorizationService;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import io.github.stevdrey.dokene.template.domain.TemplateMapping;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

class TemplateRegistryServiceRefreshLimitTest {
    private static final Instant START = Instant.parse("2026-11-07T10:00:00Z");
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final SemanticTemplateIntent INTENT = SemanticTemplateIntent.REPEAT_PURCHASE;
    private static final String WABA = "400000000000001";

    private final MovingClock clock = new MovingClock(START);
    private final TenantId tenantId = TenantId.random();
    private final UUID integrationId = UUID.randomUUID();
    private final TemplateMappingRepository repository = mock(TemplateMappingRepository.class);
    private final TemplateAuditPort audit = mock(TemplateAuditPort.class);
    private final TenantContextProvider contexts = mock(TenantContextProvider.class);
    private final PagingGateway gateway = new PagingGateway();
    private final TemplateRegistryService service = new TemplateRegistryService(repository, gateway, audit,
            mock(TenantAuthorizationService.class), contexts, new TemplateRegistryProperties(null),
            new TemplateRefreshLimiter(new TemplateRefreshLimitProperties(5, 3, WINDOW), clock), clock,
            mock(PlatformTransactionManager.class));

    @BeforeEach
    void storeAnUnverifiedMapping() {
        actAs(new IdentityId(UUID.randomUUID()));
        TemplateMapping mapping = TemplateMapping.create(tenantId, integrationId, INTENT, "repeat_purchase_v1",
                "es_MX", List.of("customer_name"), true, START);
        when(repository.find(tenantId, INTENT)).thenReturn(Optional.of(mapping));
    }

    @Test
    void everyPageRequestIsChargedAndAThrottledRefreshNeverReachesTheProvider() {
        gateway.pages = 3;
        assertRefusedWith(TemplateErrorCode.PROVIDER_UNAVAILABLE);

        // The member's share of 3 is spent by the three pages of one refresh.
        TemplateRateLimitedException throttled = assertThrottled();
        assertThat(throttled.retryAfterSeconds()).isEqualTo(WINDOW.toSeconds());
        assertThat(gateway.lookups).hasValue(1);
    }

    @Test
    void anAnswerDecidedWithoutAProviderRequestCostsNothing() {
        gateway.pages = 0;
        for (int refresh = 0; refresh < 20; refresh++) {
            assertRefusedWith(TemplateErrorCode.PROVIDER_UNAVAILABLE);
        }
        assertThat(gateway.lookups).hasValue(20);
        verify(audit, never()).refreshThrottled();
    }

    @Test
    void aLoopingMemberIsAuditedOncePerWindowAndCannotLockOthersOut() {
        for (int refresh = 0; refresh < 3; refresh++) {
            assertRefusedWith(TemplateErrorCode.PROVIDER_UNAVAILABLE);
        }
        for (int retry = 0; retry < 10; retry++) {
            assertThrottled();
        }
        verify(audit, times(1)).refreshThrottled();
        assertThat(gateway.lookups).hasValue(3);

        // Another member still has the rest of the tenant's budget, then the tenant budget holds everyone.
        actAs(new IdentityId(UUID.randomUUID()));
        assertRefusedWith(TemplateErrorCode.PROVIDER_UNAVAILABLE);
        assertRefusedWith(TemplateErrorCode.PROVIDER_UNAVAILABLE);
        assertThrottled();
        actAs(new IdentityId(UUID.randomUUID()));
        assertThrottled();
        verify(audit, times(3)).refreshThrottled();
        assertThat(gateway.lookups).hasValue(5);
    }

    @Test
    void aRetryAfterTheWindowReachesTheProviderAgain() {
        for (int refresh = 0; refresh < 3; refresh++) {
            assertRefusedWith(TemplateErrorCode.PROVIDER_UNAVAILABLE);
        }
        long retryAfter = assertThrottled().retryAfterSeconds();

        clock.advance(Duration.ofSeconds(retryAfter));
        assertRefusedWith(TemplateErrorCode.PROVIDER_UNAVAILABLE);
        assertThat(gateway.lookups).hasValue(4);
    }

    private void actAs(IdentityId identity) {
        when(contexts.requireCurrent()).thenReturn(new TenantContext(tenantId, identity, TenantMembershipId.random(),
                TenantRole.ADMIN));
    }

    private void assertRefusedWith(TemplateErrorCode code) {
        assertThatThrownBy(() -> service.refreshApproval(INTENT, 0))
                .isExactlyInstanceOf(TemplateRefusedException.class)
                .extracting(failure -> ((TemplateRefusedException) failure).code()).isEqualTo(code);
    }

    private TemplateRateLimitedException assertThrottled() {
        int asked = gateway.lookups.get();
        Throwable failure = org.assertj.core.api.Assertions.catchThrowable(() -> service.refreshApproval(INTENT, 0));
        assertThat(failure).isInstanceOf(TemplateRateLimitedException.class);
        assertThat(((TemplateRateLimitedException) failure).code()).isEqualTo(TemplateErrorCode.RATE_LIMITED);
        assertThat(gateway.lookups).hasValue(asked);
        return (TemplateRateLimitedException) failure;
    }

    /** Pretends each lookup reads {@code pages} provider pages and finds no conclusion. */
    private final class PagingGateway implements TemplateProviderGateway {
        final AtomicInteger lookups = new AtomicInteger();
        volatile int pages = 1;

        @Override
        public Optional<Sender> currentSender() {
            return Optional.of(Sender.of(integrationId, WABA));
        }

        @Override
        public Lookup lookup(Sender sender, String providerTemplateName, String language, RequestMeter meter) {
            lookups.incrementAndGet();
            for (int page = 0; page < pages; page++) {
                meter.request();
            }
            return new Unavailable();
        }
    }

    private static final class MovingClock extends Clock {
        private volatile Instant now;

        MovingClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
