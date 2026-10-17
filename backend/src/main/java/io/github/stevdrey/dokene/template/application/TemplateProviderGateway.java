// ABOUTME: Port to the provider side of template verification and to the tenant's current sending identity.
// ABOUTME: Implemented in integration; template never learns credentials, endpoints or provider payloads.
package io.github.stevdrey.dokene.template.application;

import io.github.stevdrey.dokene.template.domain.ProviderTemplateSchema;
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
    Lookup lookup(Sender sender, String providerTemplateName, String language);

    /**
     * The sending identity a mapping is bound to. An adapter may return its own subtype carrying what it needs to
     * call the provider (for example a credential reference) and receives it back in {@link #lookup}, so the
     * template module never handles credentials and the integration is read once per operation.
     */
    interface Sender {
        UUID integrationId();

        String businessAccountId();

        static Sender of(UUID integrationId, String businessAccountId) {
            return new BasicSender(integrationId, businessAccountId);
        }
    }

    record BasicSender(UUID integrationId, String businessAccountId) implements Sender {
        public BasicSender {
            Objects.requireNonNull(integrationId, "Integration id is required");
            Objects.requireNonNull(businessAccountId, "Business account id is required");
        }
    }

    sealed interface Lookup permits Reported, Absent, Unavailable {
    }

    /**
     * The provider reported this status for exactly this business account. An approval always carries the template's
     * parameter schema, which the template module checks against the mapping; no other status carries one.
     */
    record Reported(TemplateApprovalStatus status, String businessAccountId, ProviderTemplateSchema schema)
            implements Lookup {
        public Reported {
            Objects.requireNonNull(status, "Status is required");
            Objects.requireNonNull(businessAccountId, "Business account id is required");
            if (status == TemplateApprovalStatus.UNVERIFIED || status == TemplateApprovalStatus.NOT_FOUND
                    || status.derivedFromSchema()) {
                throw new IllegalArgumentException("Status is not provider evidence");
            }
            if ((status == TemplateApprovalStatus.APPROVED) != (schema != null)) {
                throw new IllegalArgumentException("Only an approval carries a parameter schema");
            }
        }

        /** A provider status other than approval. */
        public Reported(TemplateApprovalStatus status, String businessAccountId) {
            this(status, businessAccountId, null);
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
