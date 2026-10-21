// ABOUTME: Maps a non-2xx Graph API answer to a send result from its status and Meta error code only (ADR 0027).
// ABOUTME: A 4xx is a definitive refusal; every 5xx is uncertain. The code table also classifies webhook failures.
package io.github.stevdrey.dokene.integration.whatsapp;

import io.github.stevdrey.dokene.messaging.domain.FailureCategory;
import io.github.stevdrey.dokene.messaging.domain.ProviderSendResult;
import io.github.stevdrey.dokene.messaging.domain.ProviderSendResult.FailedTransiently;
import io.github.stevdrey.dokene.messaging.domain.ProviderSendResult.OutcomeUnknown;
import io.github.stevdrey.dokene.messaging.domain.ProviderSendResult.RejectedPermanently;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

public final class GraphApiSendErrors {
    /** Meta throttling codes: the request was refused before sending, so another attempt later is safe. */
    private static final Set<Integer> RATE_LIMITED = Set.of(4, 80007, 130429, 131048, 131056);
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
        // A 5xx, a redirect or an unexpected status: Meta may already have accepted the message. No Meta error code
        // documents that a 5xx send was not taken, and there is no idempotency key, so a retry could send twice.
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
        FailureCategory category = categoryFor(code.getAsInt());
        if (category == FailureCategory.RATE_LIMITED || status == 429) {
            return new FailedTransiently(FailureCategory.RATE_LIMITED);
        }
        if (category == FailureCategory.PROVIDER_UNAVAILABLE) {
            return new FailedTransiently(FailureCategory.PROVIDER_UNAVAILABLE);
        }
        if (status == 401) {
            return new RejectedPermanently(FailureCategory.AUTHENTICATION);
        }
        return new RejectedPermanently(category);
    }

    /**
     * The failure category of a Meta error code alone, shared by the send adapter and the webhook parser so a code
     * means the same thing whether Meta refused the send or reported the failure later in a status callback.
     */
    public static FailureCategory categoryFor(int code) {
        if (RATE_LIMITED.contains(code)) {
            return FailureCategory.RATE_LIMITED;
        }
        if (UNAVAILABLE.contains(code)) {
            return FailureCategory.PROVIDER_UNAVAILABLE;
        }
        if (AUTHENTICATION.contains(code) || (code >= 200 && code <= 299)) {
            return FailureCategory.AUTHENTICATION;
        }
        if (INVALID_RECIPIENT.contains(code)) {
            return FailureCategory.INVALID_RECIPIENT;
        }
        if (POLICY_VIOLATION.contains(code)) {
            return FailureCategory.POLICY_VIOLATION;
        }
        if (TEMPLATE_REJECTED.contains(code) || (code >= 132000 && code <= 132999)) {
            return FailureCategory.TEMPLATE_REJECTED;
        }
        return FailureCategory.UNKNOWN;
    }
}
