// ABOUTME: What an admission answers (ADR 0040): Admitted, or Denied with a closed reason and the delay after which
// ABOUTME: asking again can succeed. Named apart from ProviderHealthGate.Admission, which is a different decision.
package io.github.stevdrey.dokene.admission;

import java.time.Duration;
import java.util.Objects;

public sealed interface AdmissionDecision permits AdmissionDecision.Admitted, AdmissionDecision.Denied {

    record Admitted() implements AdmissionDecision {
    }

    /** Not attempted, try later: a denial is never a failed attempt and must not feed a retry counter. */
    record Denied(AdmissionDenialReason reason, Duration retryAfter) implements AdmissionDecision {
        public Denied {
            Objects.requireNonNull(reason, "Reason is required");
            Objects.requireNonNull(retryAfter, "Retry delay is required");
            if (retryAfter.isNegative() || retryAfter.isZero()) {
                throw new IllegalArgumentException("Retry delay must be positive");
            }
        }
    }
}
