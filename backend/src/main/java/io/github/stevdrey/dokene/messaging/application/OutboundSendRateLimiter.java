// ABOUTME: Port for the per tenant send rate limit (ADR 0023 §4.3 step 2b); keyed on the tenant id only.
// ABOUTME: A refused request still consumes a permit; the answer carries the wait so the handler can write Retry-After.
package io.github.stevdrey.dokene.messaging.application;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

public interface OutboundSendRateLimiter {

    /** Empty when a permit was granted; otherwise the time until the current window ends, whole seconds, at least one. */
    Optional<Duration> acquire(UUID tenantId);
}
