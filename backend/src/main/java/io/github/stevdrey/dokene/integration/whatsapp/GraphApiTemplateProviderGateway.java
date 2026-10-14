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
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
@EnableConfigurationProperties(GraphApiProperties.class)
public class GraphApiTemplateProviderGateway implements TemplateProviderGateway {
    /** Hard cap on the bytes read from the provider; enforced while streaming, never after buffering. */
    private static final int MAX_BODY_BYTES = 262_144;

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
                Reply reply = secret.withValue(token -> send(uri, token));
                return interpret(reply, integration.businessAccountId(), providerTemplateName, language);
            }
        } catch (RuntimeException failure) {
            return new Unavailable();
        }
    }

    /** The status and, for a 200 within the size and time bounds, the body bytes. */
    private record Reply(int status, byte[] body) {
    }

    private Reply send(URI uri, char[] token) {
        try {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(properties.timeout())
                    .header("Authorization", "Bearer " + new String(token)).header("Accept", "application/json")
                    .GET().build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            InputStream stream = response.body();
            try {
                long declared = response.headers().firstValueAsLong("Content-Length").orElse(0);
                if (response.statusCode() != 200 || declared > MAX_BODY_BYTES) {
                    return new Reply(response.statusCode(), null);
                }
                return new Reply(200, readBounded(stream));
            } finally {
                stream.close(); // also cancels the exchange when the body was not fully read
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Provider request failed");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Provider request interrupted");
        }
    }

    /**
     * Reads at most {@code MAX_BODY_BYTES} within the configured timeout, so neither a huge or endless body nor a
     * slow drip can exhaust the heap or hold the caller. Returns null when either bound is exceeded.
     */
    private byte[] readBounded(InputStream stream) throws InterruptedException {
        CompletableFuture<byte[]> read = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                byte[] bytes = stream.readNBytes(MAX_BODY_BYTES + 1);
                read.complete(bytes.length > MAX_BODY_BYTES ? null : bytes);
            } catch (IOException | RuntimeException failure) {
                read.completeExceptionally(failure);
            }
        });
        try {
            return read.get(properties.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException failure) {
            return null;
        }
    }

    /** Only a well-formed 200 with an exact name and language match is evidence; everything else is not. */
    private Lookup interpret(Reply reply, String businessAccountId, String name, String language) {
        if (reply.status() != 200 || reply.body() == null) {
            return new Unavailable();
        }
        try {
            JsonNode data = json.readTree(new String(reply.body(), StandardCharsets.UTF_8)).path("data");
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
