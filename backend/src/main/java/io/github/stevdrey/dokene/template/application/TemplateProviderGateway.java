// ABOUTME: Port to the provider side of template verification and to the tenant's current sending identity.
// ABOUTME: Implemented in integration; template never learns credentials, endpoints or provider payloads.
package io.github.stevdrey.dokene.template.application;

import io.github.stevdrey.dokene.template.domain.TemplateApprovalStatus;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public interface TemplateProviderGateway {
    /** The current tenant's configured integration, regardless of kill switches; empty when none exists. */
    Optional<Sender> currentSender();

    /**
     * Asks the provider for the template's current state in the current tenant's business account. Never throws for
     * provider or credential problems: they are {@link Unavailable}, which carries no provider text.
     */
    Lookup lookup(String providerTemplateName, String language);

    record Sender(UUID integrationId, String businessAccountId) {
        public Sender {
            Objects.requireNonNull(integrationId, "Integration id is required");
            Objects.requireNonNull(businessAccountId, "Business account id is required");
        }
    }

    sealed interface Lookup permits Reported, Absent, Unavailable {
    }

    /** The provider reported this status for exactly this business account. */
    record Reported(TemplateApprovalStatus status, String businessAccountId) implements Lookup {
        public Reported {
            Objects.requireNonNull(status, "Status is required");
            Objects.requireNonNull(businessAccountId, "Business account id is required");
            if (status == TemplateApprovalStatus.UNVERIFIED || status == TemplateApprovalStatus.NOT_FOUND) {
                throw new IllegalArgumentException("Status is not provider evidence");
            }
        }
    }

    /** The provider answered and has no such template. */
    record Absent(String businessAccountId) implements Lookup {
        public Absent {
            Objects.requireNonNull(businessAccountId, "Business account id is required");
        }
    }

    /** No answer was obtained: timeout, authentication, unresolvable credential or provider error. */
    record Unavailable() implements Lookup {
    }
}
