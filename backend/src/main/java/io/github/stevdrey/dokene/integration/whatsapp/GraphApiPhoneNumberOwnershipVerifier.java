// ABOUTME: Proves phone number ownership by listing the business account's numbers with the tenant's own credential
// ABOUTME: (issue 210). Proven only when Meta lists the id; any doubt is Unavailable and no provider text escapes.
package io.github.stevdrey.dokene.integration.whatsapp;

import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.ResolvedSecret;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.SecretReference;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.Tenant;
import io.github.stevdrey.dokene.integration.application.PhoneNumberOwnershipVerifier;
import io.github.stevdrey.dokene.integration.domain.WhatsAppIntegration;
import io.github.stevdrey.dokene.integration.whatsapp.GraphApiHttp.Reply;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class GraphApiPhoneNumberOwnershipVerifier implements PhoneNumberOwnershipVerifier {
    /** Most pages one proof may read; a longer list is unavailable rather than assumed to lack the number. */
    private static final int MAX_PAGES = 5;
    private static final int PAGE_SIZE = 100;

    private final ObjectProvider<IntegrationSecretResolver> secrets;
    private final TenantContextProvider contexts;
    private final GraphApiHttp http;
    private final JsonMapper json = JsonMapper.builder().build();

    public GraphApiPhoneNumberOwnershipVerifier(ObjectProvider<IntegrationSecretResolver> secrets,
            TenantContextProvider contexts, GraphApiHttp http) {
        this.secrets = Objects.requireNonNull(secrets, "Secrets are required");
        this.contexts = Objects.requireNonNull(contexts, "Tenant contexts are required");
        this.http = Objects.requireNonNull(http, "Graph API HTTP is required");
    }

    @Override
    public Outcome verify(Claim claim) {
        try {
            IntegrationSecretResolver resolver = secrets.getIfAvailable();
            // The credential is resolved in the claimed tenant's scope only, and only for the tenant now in context.
            if (resolver == null || !claim.tenantId().equals(contexts.requireCurrent().tenantId())
                    // Same shape the domain and the database enforce; anything else is never put in a URL.
                    || !WhatsAppIntegration.isMetaId(claim.phoneNumberId())
                    || !WhatsAppIntegration.isMetaId(claim.businessAccountId())) {
                return new Unavailable();
            }
            long deadline = http.deadline();
            try (ResolvedSecret secret = resolver.resolve(new Tenant(claim.tenantId()),
                    new SecretReference(claim.secretRef()))) {
                return secret.withValue(token -> search(claim, token, deadline));
            }
        } catch (RuntimeException failure) {
            return new Unavailable();
        }
    }

    /**
     * Pages through the business account's numbers on the same base URL. A refusal to list them at all (the account is
     * unknown to, or not shared with, this credential) is {@link NotProven}; so is a list that really ended without the
     * id. Running out of pages, time or a usable cursor is {@link Unavailable}.
     */
    private Outcome search(Claim claim, char[] token, long deadline) {
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            Reply reply = http.get(pageUri(claim.businessAccountId(), cursor), token, deadline);
            if (page == 0 && refused(reply.status())) {
                return new NotProven();
            }
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
            for (JsonNode number : data) {
                if (claim.phoneNumberId().equals(number.path("id").asString(null))) {
                    return new Proven();
                }
            }
            JsonNode next = root.path("paging").path("next");
            if (next.isMissingNode() || next.isNull()) {
                return new NotProven();
            }
            cursor = root.path("paging").path("cursors").path("after").asString(null);
            if (cursor == null || !GraphApiHttp.CURSOR.matcher(cursor).matches()) {
                return new Unavailable();
            }
        }
        return new Unavailable();
    }

    /**
     * Graph answers an object the token cannot see with 400/404, and a token without permission on it with 403. A 401
     * means the token itself is invalid or expired: a credential problem, not evidence about ownership, so it is left
     * to the unavailable path and never counts against the rejection budget.
     */
    private static boolean refused(int status) {
        return status == 400 || status == 403 || status == 404;
    }

    private URI pageUri(String businessAccountId, String cursor) {
        String path = businessAccountId + "/phone_numbers?fields=id&limit=" + PAGE_SIZE;
        return http.uri(cursor == null ? path : path + "&after=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8));
    }
}
