// ABOUTME: Reads a template's approval status from Meta's Graph API with the tenant's own credential (ADR 0025).
// ABOUTME: Fails closed: any problem is Unavailable with no provider text; the secret never leaves this class.
package io.github.stevdrey.dokene.integration.whatsapp;

import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.ResolvedSecret;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.SecretReference;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.Tenant;
import io.github.stevdrey.dokene.integration.application.WhatsAppIntegrationRepository;
import io.github.stevdrey.dokene.integration.domain.WhatsAppIntegration;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.template.application.TemplateProviderGateway;
import io.github.stevdrey.dokene.template.domain.TemplateApprovalStatus;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
@EnableConfigurationProperties(GraphApiProperties.class)
public class GraphApiTemplateProviderGateway implements TemplateProviderGateway {
    private static final int MAX_BODY_CHARS = 262_144;

    private final WhatsAppIntegrationRepository integrations;
    private final ObjectProvider<IntegrationSecretResolver> secrets;
    private final TenantContextProvider contexts;
    private final GraphApiProperties properties;
    private final HttpClient http;
    private final JsonMapper json = JsonMapper.builder().build();

    public GraphApiTemplateProviderGateway(WhatsAppIntegrationRepository integrations,
            ObjectProvider<IntegrationSecretResolver> secrets, TenantContextProvider contexts,
            GraphApiProperties properties) {
        this.integrations = Objects.requireNonNull(integrations, "Integrations are required");
        this.secrets = Objects.requireNonNull(secrets, "Secrets are required");
        this.contexts = Objects.requireNonNull(contexts, "Tenant contexts are required");
        this.properties = Objects.requireNonNull(properties, "Properties are required");
        this.http = HttpClient.newBuilder().connectTimeout(properties.timeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public Optional<Sender> currentSender() {
        return integrations.findByTenant(contexts.requireCurrent().tenantId())
                .map(i -> new Sender(i.id(), i.businessAccountId()));
    }

    @Override
    public Lookup lookup(String providerTemplateName, String language) {
        try {
            Optional<WhatsAppIntegration> found = integrations.findByTenant(contexts.requireCurrent().tenantId());
            IntegrationSecretResolver resolver = secrets.getIfAvailable();
            if (found.isEmpty() || resolver == null) {
                return new Unavailable();
            }
            WhatsAppIntegration integration = found.get();
            URI uri = URI.create(properties.baseUrl().toString().replaceAll("/+$", "") + "/"
                    + integration.businessAccountId() + "/message_templates?name="
                    + URLEncoder.encode(providerTemplateName, StandardCharsets.UTF_8) + "&language="
                    + URLEncoder.encode(language, StandardCharsets.UTF_8));
            try (ResolvedSecret secret = resolver.resolve(new Tenant(integration.tenantId()),
                    new SecretReference(integration.secretRef()))) {
                HttpResponse<String> response = secret.withValue(token -> send(uri, token));
                return interpret(response, integration.businessAccountId(), providerTemplateName, language);
            }
        } catch (RuntimeException failure) {
            return new Unavailable();
        }
    }

    private HttpResponse<String> send(URI uri, char[] token) {
        try {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(properties.timeout())
                    .header("Authorization", "Bearer " + new String(token)).header("Accept", "application/json")
                    .GET().build();
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException failure) {
            throw new IllegalStateException("Provider request failed");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Provider request interrupted");
        }
    }

    /** Only a well-formed 200 with an exact name and language match is evidence; everything else is not. */
    private Lookup interpret(HttpResponse<String> response, String businessAccountId, String name, String language) {
        if (response.statusCode() != 200 || response.body() == null || response.body().length() > MAX_BODY_CHARS) {
            return new Unavailable();
        }
        try {
            JsonNode data = json.readTree(response.body()).path("data");
            if (!data.isArray()) {
                return new Unavailable();
            }
            for (JsonNode template : data) {
                if (name.equals(template.path("name").asString(null))
                        && language.equals(template.path("language").asString(null))) {
                    return map(template.path("status").asString(null), businessAccountId);
                }
            }
            return new Absent(businessAccountId);
        } catch (JacksonException malformed) {
            return new Unavailable();
        }
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
