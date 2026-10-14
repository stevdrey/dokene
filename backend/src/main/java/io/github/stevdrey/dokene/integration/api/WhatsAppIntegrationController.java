package io.github.stevdrey.dokene.integration.api;

import io.github.stevdrey.dokene.integration.application.WhatsAppIntegrationService;
import io.github.stevdrey.dokene.integration.domain.WhatsAppIntegration;
import io.github.stevdrey.dokene.tenant.application.RequiredPermission;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import java.util.Map;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Non-secret WhatsApp integration configuration (ADR 0023 §3.3). Credentials are referenced, never carried. */
@RestController
@RequestMapping("/api/integrations/whatsapp")
public class WhatsAppIntegrationController {
    private final WhatsAppIntegrationService integrations;

    public WhatsAppIntegrationController(WhatsAppIntegrationService integrations) {
        this.integrations = integrations;
    }

    @RequiredPermission(TenantPermission.INTEGRATION_READ)
    @GetMapping
    public ResponseEntity<IntegrationResponse> get() {
        return respond(integrations.get());
    }

    @RequiredPermission(TenantPermission.INTEGRATION_MANAGE)
    @PutMapping
    public ResponseEntity<IntegrationResponse> put(
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody Map<String, Object> body) {
        IntegrationRequest request = IntegrationRequest.from(body);
        WhatsAppIntegration saved = integrations.upsert(parseVersion(ifMatch), request.phoneNumberId(),
                request.businessAccountId(), request.displayPhoneNumber(), request.enabled(),
                request.secretRef());
        return respond(saved);
    }

    private ResponseEntity<IntegrationResponse> respond(WhatsAppIntegration integration) {
        return ResponseEntity.ok().eTag("\"" + integration.version() + "\"").body(new IntegrationResponse(
                integration.enabled(), integration.phoneNumberId(), integration.businessAccountId(),
                integration.displayPhoneNumber(), integration.secretRef(), integration.version()));
    }

    private static Long parseVersion(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String unquoted = value.strip();
        if (unquoted.length() >= 2 && unquoted.startsWith("\"") && unquoted.endsWith("\"")) {
            unquoted = unquoted.substring(1, unquoted.length() - 1);
        }
        try {
            long version = Long.parseLong(unquoted);
            if (version < 0) {
                throw new IllegalArgumentException("If-Match is invalid");
            }
            return version;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("If-Match is invalid");
        }
    }

    public record IntegrationRequest(String phoneNumberId, String businessAccountId, String displayPhoneNumber,
            Boolean enabled, String secretRef) {
        private static final Set<String> MEMBERS = Set.of("phoneNumberId", "businessAccountId", "displayPhoneNumber",
                "enabled", "secretRef");

        /** Unknown members (for example a raw token) and wrongly typed values are rejected, never ignored. */
        static IntegrationRequest from(Map<String, Object> body) {
            if (body == null || !MEMBERS.containsAll(body.keySet()) || !(body.get("enabled") instanceof Boolean)) {
                throw new IllegalArgumentException("Request is invalid");
            }
            return new IntegrationRequest(text(body, "phoneNumberId"), text(body, "businessAccountId"),
                    text(body, "displayPhoneNumber"), (Boolean) body.get("enabled"), text(body, "secretRef"));
        }

        private static String text(Map<String, Object> body, String member) {
            Object value = body.get(member);
            if (value != null && !(value instanceof String)) {
                throw new IllegalArgumentException("Request is invalid");
            }
            return (String) value;
        }
    }

    public record IntegrationResponse(boolean enabled, String phoneNumberId, String businessAccountId,
            String displayPhoneNumber, String secretRef, long version) { }
}
