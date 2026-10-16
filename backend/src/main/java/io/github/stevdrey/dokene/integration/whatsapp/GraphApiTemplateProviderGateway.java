// ABOUTME: Reads a template's approval status from Meta's Graph API with the tenant's own credential (ADR 0025).
// ABOUTME: Fails closed: any problem is Unavailable with no provider text; the secret never leaves this class.
package io.github.stevdrey.dokene.integration.whatsapp;

import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.ResolvedSecret;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.SecretReference;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.Tenant;
import io.github.stevdrey.dokene.integration.application.WhatsAppIntegrationRepository;
import io.github.stevdrey.dokene.integration.whatsapp.GraphApiHttp.Reply;
import io.github.stevdrey.dokene.integration.domain.WhatsAppIntegration;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.template.application.TemplateProviderGateway;
import io.github.stevdrey.dokene.template.domain.TemplateApprovalStatus;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class GraphApiTemplateProviderGateway implements TemplateProviderGateway {
    /** Most pages one lookup may read; a longer list is unavailable rather than assumed to lack the template. */
    private static final int MAX_PAGES = 5;

    private final WhatsAppIntegrationRepository integrations;
    private final ObjectProvider<IntegrationSecretResolver> secrets;
    private final TenantContextProvider contexts;
    private final GraphApiHttp http;
    private final JsonMapper json = JsonMapper.builder().build();

    public GraphApiTemplateProviderGateway(WhatsAppIntegrationRepository integrations,
            ObjectProvider<IntegrationSecretResolver> secrets, TenantContextProvider contexts,
            GraphApiHttp http) {
        this.integrations = Objects.requireNonNull(integrations, "Integrations are required");
        this.secrets = Objects.requireNonNull(secrets, "Secrets are required");
        this.contexts = Objects.requireNonNull(contexts, "Tenant contexts are required");
        this.http = Objects.requireNonNull(http, "Graph API HTTP is required");
    }

    /** The sender as this adapter returns it: the identity plus what is needed to call Meta, never exposed further. */
    private record GraphSender(UUID integrationId, String businessAccountId, TenantId tenantId, String secretRef)
            implements Sender {
    }

    @Override
    public Optional<Sender> currentSender() {
        return integrations.findByTenant(contexts.requireCurrent().tenantId())
                .map(i -> new GraphSender(i.id(), i.businessAccountId(), i.tenantId(), i.secretRef()));
    }

    @Override
    public Lookup lookup(Sender sender, String providerTemplateName, String language) {
        try {
            IntegrationSecretResolver resolver = secrets.getIfAvailable();
            if (resolver == null || !(sender instanceof GraphSender graph)
                    || !graph.tenantId().equals(contexts.requireCurrent().tenantId())) {
                return new Unavailable();
            }
            // One deadline covers every page, so the whole call never exceeds the configured timeout.
            long deadline = http.deadline();
            try (ResolvedSecret secret = resolver.resolve(new Tenant(graph.tenantId()),
                    new SecretReference(graph.secretRef()))) {
                return secret.withValue(token -> search(graph, token, providerTemplateName, language, deadline));
            }
        } catch (RuntimeException failure) {
            return new Unavailable();
        }
    }

    /**
     * Looks for the exact name and language, following Graph cursors on the same base URL for at most
     * {@code MAX_PAGES} pages. Absence is concluded only when the list really ended; running out of pages, time or a
     * usable cursor with more pages pending is unavailable.
     */
    private Lookup search(GraphSender graph, char[] token, String name, String language, long deadline) {
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            Reply reply = http.get(pageUri(graph, name, language, cursor), token, deadline);
            if (reply.status() != 200 || reply.body() == null) {
                return new Unavailable();
            }
            JsonNode root;
            try {
                root = json.readTree(reply.body());
            } catch (JacksonException malformed) {
                return new Unavailable();
            }
            JsonNode data = root.path("data");
            if (!data.isArray()) {
                return new Unavailable();
            }
            for (JsonNode template : data) {
                if (name.equals(template.path("name").asString(null))
                        && language.equals(template.path("language").asString(null))) {
                    return map(template.path("status").asString(null), graph.businessAccountId());
                }
            }
            if (root.path("paging").path("next").isMissingNode() || root.path("paging").path("next").isNull()) {
                return new Absent(graph.businessAccountId());
            }
            cursor = root.path("paging").path("cursors").path("after").asString(null);
            if (cursor == null || !GraphApiHttp.CURSOR.matcher(cursor).matches()) {
                return new Unavailable();
            }
        }
        return new Unavailable();
    }

    private URI pageUri(GraphSender graph, String name, String language, String cursor) {
        String path = graph.businessAccountId() + "/message_templates?name="
                + URLEncoder.encode(name, StandardCharsets.UTF_8) + "&language="
                + URLEncoder.encode(language, StandardCharsets.UTF_8);
        return http.uri(cursor == null ? path : path + "&after=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8));
    }

    /** Unknown or non-sendable-by-policy states (for example LIMIT_EXCEEDED) are not evidence. */
    private static Lookup map(String status, String businessAccountId) {
        if (status == null) {
            return new Unavailable();
        }
        TemplateApprovalStatus mapped = switch (status) {
            case "APPROVED" -> TemplateApprovalStatus.APPROVED;
            case "PENDING", "IN_APPEAL" -> TemplateApprovalStatus.PENDING;
            case "REJECTED" -> TemplateApprovalStatus.REJECTED;
            case "PAUSED" -> TemplateApprovalStatus.PAUSED;
            case "DISABLED" -> TemplateApprovalStatus.DISABLED;
            case "DELETED", "PENDING_DELETION", "ARCHIVED" -> TemplateApprovalStatus.DELETED;
            default -> null;
        };
        return mapped == null ? new Unavailable() : new Reported(mapped, businessAccountId);
    }
}
