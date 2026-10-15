// ABOUTME: Port for proving, at the provider, that a tenant's credential reaches the phone number it mapped (issue 210).
// ABOUTME: Implemented by the provider adapter; the configuration service never calls the network itself.
package io.github.stevdrey.dokene.integration.application;

import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.util.Objects;

public interface PhoneNumberOwnershipVerifier {
    /** What is checked: the stored mapping and the reference to the credential it is checked with. */
    record Claim(TenantId tenantId, String phoneNumberId, String businessAccountId, String secretRef) {
        public Claim {
            Objects.requireNonNull(tenantId, "Tenant id is required");
            Objects.requireNonNull(phoneNumberId, "Phone number id is required");
            Objects.requireNonNull(businessAccountId, "Business account id is required");
            Objects.requireNonNull(secretRef, "Secret reference is required");
        }
    }

    /** Closed outcome; carries no provider text. */
    sealed interface Outcome permits Proven, NotProven, Unavailable {
    }

    /** The provider listed the phone number under the business account for this credential. */
    record Proven() implements Outcome {
    }

    /** The provider answered, and the credential does not reach the number through that business account. */
    record NotProven() implements Outcome {
    }

    /** No conclusion could be reached (no resolver, timeout, provider error); never treated as either answer. */
    record Unavailable() implements Outcome {
    }

    /** Must not throw: every failure is {@link Unavailable}. */
    Outcome verify(Claim claim);
}
