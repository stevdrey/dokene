// ABOUTME: Maps a non-2xx Graph API answer to a send result from its status and Meta error code only (ADR 0027).
// ABOUTME: A 4xx is a definitive refusal; a 5xx is uncertain unless Meta names an explicitly transient code.
package io.github.stevdrey.dokene.integration.whatsapp;

import io.github.stevdrey.dokene.messaging.domain.FailureCategory;
import io.github.stevdrey.dokene.messaging.domain.ProviderSendResult;
import io.github.stevdrey.dokene.messaging.domain.ProviderSendResult.FailedTransiently;
import io.github.stevdrey.dokene.messaging.domain.ProviderSendResult.OutcomeUnknown;
import io.github.stevdrey.dokene.messaging.domain.ProviderSendResult.RejectedPermanently;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

final class GraphApiSendErrors {
    /** Meta throttling codes: the request was refused before sending, so another attempt later is safe. */
    private static final Set<Integer> RATE_LIMITED = Set.of(4, 80007, 130429, 131048, 131056);
    /** Meta "service unavailable" codes that, in a 5xx, still state the message was not taken (agreed in #137). */
    private static final Set<Integer> TRANSIENT_IN_5XX = Set.of(1, 2, 131016);
    /** Meta "service unavailable" codes; in a 4xx the refusal is definitive, so retrying is safe. */
    private static final Set<Integer> UNAVAILABLE = Set.of(1, 2, 131000, 131016, 131057);
    private static final Set<Integer> AUTHENTICATION = Set.of(0, 3, 10, 190, 131005);
    private static final Set<Integer> INVALID_RECIPIENT = Set.of(131021, 131026);
    private static final Set<Integer> POLICY_VIOLATION = Set.of(368, 130472, 130497, 131031, 131047, 131049, 131050);
    private static final Set<Integer> TEMPLATE_REJECTED = Set.of(100, 131008, 131009, 131051, 135000);

    private GraphApiSendErrors() {
    }

    /** {@code status} is outside 2xx; {@code code} is Meta's {@code error.code} when the body carried one. */
    static ProviderSendResult classify(int status, OptionalInt code) {
        if (status >= 400 && status < 500) {
            return refused(status, code);
        }
        if (status >= 500 && status < 600 && code.isPresent() && TRANSIENT_IN_5XX.contains(code.getAsInt())) {
            return new FailedTransiently(FailureCategory.PROVIDER_UNAVAILABLE);
        }
        // Any other 5xx, a redirect or an unexpected status: Meta may already have accepted the message.
        return new OutcomeUnknown(Optional.empty());
    }

    private static ProviderSendResult refused(int status, OptionalInt code) {
        if (code.isEmpty()) {
            return switch (status) {
                case 401, 403 -> new RejectedPermanently(FailureCategory.AUTHENTICATION);
                case 429 -> new FailedTransiently(FailureCategory.RATE_LIMITED);
                // An intermediary that did not receive the whole request (or refused early data) took nothing.
                case 408, 425 -> new FailedTransiently(FailureCategory.PROVIDER_UNAVAILABLE);
                default -> new RejectedPermanently(FailureCategory.UNKNOWN);
            };
        }
        int value = code.getAsInt();
        if (RATE_LIMITED.contains(value) || status == 429) {
            return new FailedTransiently(FailureCategory.RATE_LIMITED);
        }
        if (UNAVAILABLE.contains(value)) {
            return new FailedTransiently(FailureCategory.PROVIDER_UNAVAILABLE);
        }
        if (AUTHENTICATION.contains(value) || (value >= 200 && value <= 299) || status == 401) {
            return new RejectedPermanently(FailureCategory.AUTHENTICATION);
        }
        if (INVALID_RECIPIENT.contains(value)) {
            return new RejectedPermanently(FailureCategory.INVALID_RECIPIENT);
        }
        if (POLICY_VIOLATION.contains(value)) {
            return new RejectedPermanently(FailureCategory.POLICY_VIOLATION);
        }
        if (TEMPLATE_REJECTED.contains(value) || (value >= 132000 && value <= 132999)) {
            return new RejectedPermanently(FailureCategory.TEMPLATE_REJECTED);
        }
        return new RejectedPermanently(FailureCategory.UNKNOWN);
    }
}
