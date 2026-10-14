package io.github.stevdrey.dokene.integration.api;

import io.github.stevdrey.dokene.integration.api.IntegrationApiContract.IntegrationRequest;
import io.github.stevdrey.dokene.integration.api.IntegrationApiContract.IntegrationResponse;
import io.github.stevdrey.dokene.integration.application.WhatsAppIntegrationService;
import io.github.stevdrey.dokene.integration.application.WhatsAppIntegrationService.Create;
import io.github.stevdrey.dokene.integration.application.WhatsAppIntegrationService.Precondition;
import io.github.stevdrey.dokene.integration.application.WhatsAppIntegrationService.Version;
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

    /** Fail-closed until the tenant kill switch (#144) and webhook verification (#139) persist their state. */
    private static final boolean OUTBOUND_ENABLED = false;
    private static final boolean WEBHOOK_VERIFIED = false;

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
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch,
            @RequestBody Map<String, Object> body) {
        IntegrationRequest request = IntegrationRequest.from(body);
        WhatsAppIntegration saved = integrations.upsert(precondition(ifMatch, ifNoneMatch), request.phoneNumberId(),
                request.businessAccountId(), request.displayPhoneNumber(), request.enabled(),
                request.secretRef());
        return respond(saved);
    }

    private ResponseEntity<IntegrationResponse> respond(WhatsAppIntegration integration) {
        return ResponseEntity.ok().eTag("\"" + integration.version() + "\"").body(new IntegrationResponse(
                integration.enabled(), OUTBOUND_ENABLED, integration.phoneNumberId(), integration.businessAccountId(),
                integration.displayPhoneNumber(), integration.secretRef(), WEBHOOK_VERIFIED, integration.version()));
    }

    /**
     * Every mutation states exactly one precondition (ADR 0023 §4.1): {@code If-Match} with one strong canonical
     * numeric ETag such as {@code "0"} to replace, or {@code If-None-Match: *} to create. Missing, both or malformed
     * is a 400.
     */
    private static Precondition precondition(String ifMatch, String ifNoneMatch) {
        if ((ifMatch == null) == (ifNoneMatch == null)) {
            throw new IllegalArgumentException("Exactly one precondition is required");
        }
        if (ifNoneMatch != null) {
            if (!"*".equals(ifNoneMatch)) {
                throw new IllegalArgumentException("If-None-Match is invalid");
            }
            return new Create();
        }
        if (!STRONG_NUMERIC_ETAG.matcher(ifMatch).matches()) {
            throw new IllegalArgumentException("If-Match is invalid");
        }
        return new Version(Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1)));
    }
}
