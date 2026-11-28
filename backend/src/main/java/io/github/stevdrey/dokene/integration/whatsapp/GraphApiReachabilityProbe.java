// ABOUTME: The provider health recovery probe against Meta (ADR 0038): one read only GET of the sending tenant's own
// ABOUTME: phone number list, bounded by the Graph deadline. It never posts and no provider text or body is kept.
package io.github.stevdrey.dokene.integration.whatsapp;

import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.ResolvedSecret;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.SecretPurpose;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.SecretReference;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.Tenant;
import io.github.stevdrey.dokene.integration.application.WhatsAppIntegrationRepository;
import io.github.stevdrey.dokene.integration.application.WhatsAppOutboundSafetyControl;
import io.github.stevdrey.dokene.integration.domain.WhatsAppIntegration;
import io.github.stevdrey.dokene.integration.whatsapp.GraphApiHttp.Reply;
import io.github.stevdrey.dokene.messaging.application.ProviderReachabilityProbe;
import io.github.stevdrey.dokene.messaging.domain.ApprovalFingerprint.SenderRevision;
import io.github.stevdrey.dokene.messaging.domain.ProviderProbeResult;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.net.URI;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Any answer Meta gives with a 200 or a 4xx status shows it is there to answer, a refused credential included, so it
 * passes. A 3xx or 5xx status, a timeout or a connection failure fails it. When no request left (the integration
 * changed or may no longer send since the send policy read it, the credential did not resolve, the deadline was
 * already used up) there is no result and the health state must not change.
 */
@Component
class GraphApiReachabilityProbe implements ProviderReachabilityProbe {
    private final WhatsAppIntegrationRepository integrations;
    private final ObjectProvider<IntegrationSecretResolver> secrets;
    private final TenantContextProvider contexts;
    private final GraphApiHttp http;

    GraphApiReachabilityProbe(WhatsAppIntegrationRepository integrations,
            ObjectProvider<IntegrationSecretResolver> secrets, TenantContextProvider contexts, GraphApiHttp http) {
        this.integrations = Objects.requireNonNull(integrations, "Integrations are required");
        this.secrets = Objects.requireNonNull(secrets, "Secrets are required");
        this.contexts = Objects.requireNonNull(contexts, "Tenant contexts are required");
        this.http = Objects.requireNonNull(http, "Graph API HTTP is required");
    }

    @Override
    public ProviderProbeResult probe(TenantId tenantId, SenderRevision sender) {
        try {
            IntegrationSecretResolver resolver = secrets.getIfAvailable();
            // The credential is resolved in the sending tenant's scope only, and only for the tenant now in context.
            if (resolver == null || tenantId == null || sender == null
                    || !tenantId.equals(contexts.requireCurrent().tenantId())) {
                return ProviderProbeResult.NO_RESULT;
            }
            Optional<WhatsAppIntegration> found = integrations.findByTenant(tenantId);
            // Read again outside the send transaction: a sender that changed since is not probed with, and neither is
            // one the send adapter would no longer send from (switched off, disabled or unproven since).
            if (found.isEmpty() || !found.get().outboundEnabled() || !found.get().enabled()
                    || !found.get().ownershipVerified()
                    || !WhatsAppOutboundSafetyControl.senderRevision(found.get()).equals(sender)
                    || !WhatsAppIntegration.isMetaId(found.get().businessAccountId())) {
                return ProviderProbeResult.NO_RESULT;
            }
            WhatsAppIntegration integration = found.get();
            URI uri = http.uri(GraphApiPhoneNumberOwnershipVerifier.firstPage(integration.businessAccountId()));
            long deadline = http.deadline();
            try (ResolvedSecret secret = resolver.resolve(new Tenant(tenantId, SecretPurpose.WHATSAPP_ACCESS_TOKEN),
                    new SecretReference(integration.secretRef()))) {
                return secret.withValue(token -> exchange(uri, token, deadline));
            }
        } catch (RuntimeException failure) {
            // Everything raised here happened before or outside the exchange, which reports its own failures.
            return ProviderProbeResult.NO_RESULT;
        }
    }

    /** The one GET. Its body is read within the shared bounds and dropped. */
    private ProviderProbeResult exchange(URI uri, char[] token, long deadline) {
        Reply reply;
        try {
            reply = http.get(uri, token, deadline);
        } catch (IllegalStateException transport) {
            // An interrupt is ours and says nothing about Meta. A timeout or a failed connection does.
            return Thread.currentThread().isInterrupted() ? ProviderProbeResult.NO_RESULT
                    : ProviderProbeResult.FAILED;
        }
        int status = reply.status();
        if (status == 0) {
            return ProviderProbeResult.NO_RESULT;
        }
        if (status == 429) {
            return ProviderProbeResult.PASSED_THROTTLED;
        }
        return status == 200 || (status >= 400 && status < 500) ? ProviderProbeResult.PASSED
                : ProviderProbeResult.FAILED;
    }
}
