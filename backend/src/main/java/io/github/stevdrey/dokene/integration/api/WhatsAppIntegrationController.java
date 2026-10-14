package io.github.stevdrey.dokene.integration.api;

import io.github.stevdrey.dokene.integration.api.IntegrationApiContract.IntegrationRequest;
import io.github.stevdrey.dokene.integration.api.IntegrationApiContract.IntegrationResponse;
import io.github.stevdrey.dokene.integration.application.WhatsAppIntegrationService;
import io.github.stevdrey.dokene.integration.domain.WhatsAppIntegration;
import io.github.stevdrey.dokene.tenant.application.RequiredPermission;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import java.util.Map;
import java.util.regex.Pattern;
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
    private static final Pattern STRONG_NUMERIC_ETAG = Pattern.compile("^\"(0|[1-9][0-9]{0,17})\"$");

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

    /** Exactly one strong, canonical numeric ETag such as {@code "0"} (ADR 0023 §4.1); anything else is a 400. */
    private static Long parseVersion(String value) {
        if (value == null) {
            return null;
        }
        if (!STRONG_NUMERIC_ETAG.matcher(value).matches()) {
            throw new IllegalArgumentException("If-Match is invalid");
        }
        return Long.parseLong(value.substring(1, value.length() - 1));
    }
}
