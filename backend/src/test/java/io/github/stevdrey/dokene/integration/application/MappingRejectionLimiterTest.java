package io.github.stevdrey.dokene.integration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
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
        assertThatCode(() -> limiter.requireAvailable(tenant, actor)).doesNotThrowAnyException();
        assertThat(limiter.recordRejection(tenant, actor)).isFalse();
        assertThatCode(() -> limiter.requireAvailable(tenant, actor)).doesNotThrowAnyException();

        assertThat(limiter.recordRejection(tenant, actor)).isTrue();

        assertThatThrownBy(() -> limiter.requireAvailable(tenant, actor))
                .isInstanceOfSatisfying(IntegrationRateLimitedException.class,
                        throttled -> assertThat(throttled.code()).isEqualTo(MessagingErrorCode.RATE_LIMITED));
    }

    @Test
    void anotherActorKeepsItsBudgetUntilTheTenantBudgetIsExhausted() {
        IdentityId other = identity();
        limiter.recordRejection(tenant, actor);
        limiter.recordRejection(tenant, actor);

        assertThatCode(() -> limiter.requireAvailable(tenant, other)).doesNotThrowAnyException();
        assertThat(limiter.recordRejection(tenant, other)).isTrue();

        assertThatThrownBy(() -> limiter.requireAvailable(tenant, other))
                .isInstanceOf(IntegrationRateLimitedException.class);
        assertThatThrownBy(() -> limiter.requireAvailable(tenant, identity()))
                .isInstanceOf(IntegrationRateLimitedException.class);
    }

    @Test
    void otherTenantsAreIsolatedEvenForTheSameIdentity() {
        limiter.recordRejection(tenant, actor);
        limiter.recordRejection(tenant, actor);

        assertThatCode(() -> limiter.requireAvailable(TenantId.random(), actor)).doesNotThrowAnyException();
    }

    @Test
    void retryAfterIsTheTimeUntilTheOldestRejectionLeavesTheWindow() {
        limiter.recordRejection(tenant, actor);
        clock.advance(Duration.ofMinutes(5));
        limiter.recordRejection(tenant, actor);
        clock.advance(Duration.ofSeconds(30));

        assertThatThrownBy(() -> limiter.requireAvailable(tenant, actor))
                .isInstanceOfSatisfying(IntegrationRateLimitedException.class,
                        throttled -> assertThat(throttled.retryAfterSeconds()).isEqualTo(Duration.ofMinutes(9)
                                .plusSeconds(30).toSeconds()));
    }

    @Test
    void theBudgetIsRestoredOnceRejectionsLeaveTheWindow() {
        limiter.recordRejection(tenant, actor);
        clock.advance(Duration.ofMinutes(1));
        limiter.recordRejection(tenant, actor);

        clock.advance(WINDOW.minusMinutes(1));
        assertThatCode(() -> limiter.requireAvailable(tenant, actor)).doesNotThrowAnyException();
        assertThat(limiter.recordRejection(tenant, actor)).isTrue();
    }

    @Test
    void expiredBucketsAreEvicted() {
        limiter.recordRejection(tenant, actor);
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
