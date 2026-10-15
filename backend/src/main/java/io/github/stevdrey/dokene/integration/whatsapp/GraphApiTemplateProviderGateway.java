// ABOUTME: Reads a template's approval status from Meta's Graph API with the tenant's own credential (ADR 0025).
// ABOUTME: Fails closed: any problem is Unavailable with no provider text; the secret never leaves this class.
package io.github.stevdrey.dokene.integration.whatsapp;

import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.ResolvedSecret;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.SecretPurpose;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.SecretReference;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.Tenant;
import io.github.stevdrey.dokene.integration.application.WhatsAppIntegrationRepository;
import io.github.stevdrey.dokene.integration.domain.WhatsAppIntegration;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
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
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
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
    /** Most pages one lookup may read; a longer list is unavailable rather than assumed to lack the template. */
    private static final int MAX_PAGES = 5;
    /** Graph cursors are opaque base64-like tokens; anything else is not sent back. */
    private static final Pattern CURSOR = Pattern.compile("^[A-Za-z0-9_=+/-]{1,512}$");

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
            long deadline = System.nanoTime() + properties.timeout().toNanos();
            try (ResolvedSecret secret = resolver.resolve(
                    new Tenant(graph.tenantId(), SecretPurpose.WHATSAPP_ACCESS_TOKEN),
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
            Reply reply = fetchPage(pageUri(graph, name, language, cursor), token, deadline);
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
            if (cursor == null || !CURSOR.matcher(cursor).matches()) {
                return new Unavailable();
            }
        }
        return new Unavailable();
    }

    private URI pageUri(GraphSender graph, String name, String language, String cursor) {
        String uri = properties.baseUrl().toString().replaceAll("/+$", "") + "/" + graph.businessAccountId()
                + "/message_templates?name=" + URLEncoder.encode(name, StandardCharsets.UTF_8) + "&language="
                + URLEncoder.encode(language, StandardCharsets.UTF_8);
        return URI.create(cursor == null ? uri : uri + "&after=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8));
    }

    /** The status and, for a 200 within the size and time bounds, the body bytes. */
    private record Reply(int status, byte[] body) {
    }

    private Reply fetchPage(URI uri, char[] token, long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            return new Reply(0, null);
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofNanos(remaining))
                    .header("Authorization", "Bearer " + new String(token)).header("Accept", "application/json")
                    .GET().build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            InputStream stream = response.body();
            try {
                long declared = response.headers().firstValueAsLong("Content-Length").orElse(0);
                if (response.statusCode() != 200 || declared > MAX_BODY_BYTES) {
                    return new Reply(response.statusCode(), null);
                }
                return new Reply(200, readBounded(stream, deadline));
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
     * Reads at most {@code MAX_BODY_BYTES} before {@code deadline} (nanoTime), so neither a huge or endless body nor a
     * slow drip can exhaust the heap or hold the caller. Returns null when either bound is exceeded.
     */
    private byte[] readBounded(InputStream stream, long deadline) throws InterruptedException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            return null;
        }
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
            return read.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException | ExecutionException failure) {
            return null;
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
