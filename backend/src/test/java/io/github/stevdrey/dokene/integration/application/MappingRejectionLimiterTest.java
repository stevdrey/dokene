package io.github.stevdrey.dokene.integration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.integration.application.MappingRejectionLimiter.MappingAttempt;
import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MappingRejectionLimiterTest {
    private static final Duration WINDOW = Duration.ofMinutes(15);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-14T10:00:00Z"));
    private final MappingRejectionLimiter limiter = new MappingRejectionLimiter(
            new MappingRejectionLimitProperties(3, 2, WINDOW), clock);
    private final TenantId tenant = TenantId.random();
    private final IdentityId actor = identity();

    @Test
    void availableUntilTheActorBudgetIsExhaustedAndReportsTheExhaustingRejection() {
        assertThat(available(tenant, actor)).isTrue();
        assertThat(rejected(tenant, actor)).isFalse();
        assertThat(available(tenant, actor)).isTrue();

        assertThat(rejected(tenant, actor)).isTrue();

        assertThatThrownBy(() -> limiter.begin(tenant, actor))
                .isInstanceOfSatisfying(IntegrationRateLimitedException.class,
                        throttled -> assertThat(throttled.code()).isEqualTo(MessagingErrorCode.RATE_LIMITED));
    }

    @Test
    void anotherActorKeepsItsBudgetUntilTheTenantBudgetIsExhausted() {
        IdentityId other = identity();
        rejected(tenant, actor);
        rejected(tenant, actor);

        assertThat(available(tenant, other)).isTrue();
        assertThat(rejected(tenant, other)).isTrue();

        assertThatThrownBy(() -> limiter.begin(tenant, other))
                .isInstanceOf(IntegrationRateLimitedException.class);
        assertThatThrownBy(() -> limiter.begin(tenant, identity()))
                .isInstanceOf(IntegrationRateLimitedException.class);
    }

    @Test
    void otherTenantsAreIsolatedEvenForTheSameIdentity() {
        rejected(tenant, actor);
        rejected(tenant, actor);

        assertThat(available(TenantId.random(), actor)).isTrue();
    }

    @Test
    void concurrentAttemptsReserveTheBudgetBeforeTheirOutcomeIsKnown() {
        rejected(tenant, actor);
        MappingAttempt inFlight = limiter.begin(tenant, actor);

        // The last unit is reserved, so a parallel probe cannot run until the first outcome is known.
        assertThatThrownBy(() -> limiter.begin(tenant, actor))
                .isInstanceOfSatisfying(IntegrationRateLimitedException.class,
                        throttled -> assertThat(throttled.retryAfterSeconds()).isEqualTo(1));
        assertThat(inFlight.reject()).isTrue();
        inFlight.close();

        assertThatThrownBy(() -> limiter.begin(tenant, actor)).isInstanceOf(IntegrationRateLimitedException.class);
    }

    @Test
    void closingAnAttemptThatWasNotRejectedReleasesItsReservation() {
        rejected(tenant, actor);
        MappingAttempt succeeded = limiter.begin(tenant, actor);
        succeeded.close();
        succeeded.close();

        assertThat(rejected(tenant, actor)).isTrue();
        assertThat(succeeded.reject()).isFalse();
    }

    @Test
    void parallelAttemptsAcrossActorsNeverExceedTheTenantBudget() {
        List<MappingAttempt> attempts = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            attempts.add(limiter.begin(tenant, identity()));
        }
        assertThatThrownBy(() -> limiter.begin(tenant, identity()))
                .isInstanceOf(IntegrationRateLimitedException.class);
        assertThat(attempts.stream().filter(MappingAttempt::reject).count()).isEqualTo(1);
    }

    @Test
    void retryAfterIsTheTimeUntilTheOldestRejectionLeavesTheWindow() {
        rejected(tenant, actor);
        clock.advance(Duration.ofMinutes(5));
        rejected(tenant, actor);
        clock.advance(Duration.ofSeconds(30));

        assertThatThrownBy(() -> limiter.begin(tenant, actor))
                .isInstanceOfSatisfying(IntegrationRateLimitedException.class,
                        throttled -> assertThat(throttled.retryAfterSeconds()).isEqualTo(Duration.ofMinutes(9)
                                .plusSeconds(30).toSeconds()));
    }

    @Test
    void theBudgetIsRestoredOnceRejectionsLeaveTheWindow() {
        rejected(tenant, actor);
        clock.advance(Duration.ofMinutes(1));
        rejected(tenant, actor);

        clock.advance(WINDOW.minusMinutes(1));
        assertThat(available(tenant, actor)).isTrue();
        assertThat(rejected(tenant, actor)).isTrue();
    }

    @Test
    void expiredBucketsAreEvicted() {
        rejected(tenant, actor);
        assertThat(limiter.hasTenantBucket(tenant)).isTrue();
        assertThat(limiter.hasActorBucket(tenant, actor)).isTrue();

        clock.advance(WINDOW.plusSeconds(1));
        limiter.evictExpired(clock.instant());

        assertThat(limiter.hasTenantBucket(tenant)).isFalse();
        assertThat(limiter.hasActorBucket(tenant, actor)).isFalse();
    }

    @Test
    void retryAfterSecondsRoundsUpAndIsNeverBelowOne() {
        assertThat(new IntegrationRateLimitedException(Duration.ofMillis(1500)).retryAfterSeconds()).isEqualTo(2);
        assertThat(new IntegrationRateLimitedException(Duration.ZERO).retryAfterSeconds()).isEqualTo(1);
        assertThat(new IntegrationRateLimitedException(Duration.ofSeconds(42)).retryAfterSeconds()).isEqualTo(42);
    }

    @Test
    void propertiesDefaultMissingValuesAndRefuseNonPositiveOnes() {
        MappingRejectionLimitProperties defaults = MappingRejectionLimitProperties.defaults();
        assertThat(defaults.tenantRejections()).isEqualTo(MappingRejectionLimitProperties.DEFAULT_TENANT_REJECTIONS);
        assertThat(defaults.actorRejections()).isEqualTo(MappingRejectionLimitProperties.DEFAULT_ACTOR_REJECTIONS);
        assertThat(defaults.window()).isEqualTo(MappingRejectionLimitProperties.DEFAULT_WINDOW);

        assertThatThrownBy(() -> new MappingRejectionLimitProperties(0, 1, WINDOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MappingRejectionLimitProperties(1, -1, WINDOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MappingRejectionLimitProperties(1, 1, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Begins and releases an attempt, as a successful write does. */
    private boolean available(TenantId tenantId, IdentityId identityId) {
        try (MappingAttempt ignored = limiter.begin(tenantId, identityId)) {
            return true;
        }
    }

    /** Begins an attempt and rejects it, as a write onto a number another tenant owns does. */
    private boolean rejected(TenantId tenantId, IdentityId identityId) {
        try (MappingAttempt attempt = limiter.begin(tenantId, identityId)) {
            return attempt.reject();
        }
    }

    private static IdentityId identity() {
        return new IdentityId(UUID.randomUUID());
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        MutableClock(Instant start) {
            this.current = start;
        }

        void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
