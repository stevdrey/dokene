// ABOUTME: HTTP surface of spec 0005: read, replace and kill switch for the tenant WhatsApp integration.
// ABOUTME: Thin by design: parses If-Match and the body, calls IntegrationConfigurationService, answers with an ETag.
package io.github.stevdrey.dokene.integration.api;

import io.github.stevdrey.dokene.integration.config.IntegrationConfigurationService;
import io.github.stevdrey.dokene.integration.config.IntegrationConfigurationUpdate;
import io.github.stevdrey.dokene.integration.config.IntegrationErrorCode;
import io.github.stevdrey.dokene.integration.config.IntegrationRefusedException;
import io.github.stevdrey.dokene.integration.config.TenantMessagingIntegration;
import io.github.stevdrey.dokene.tenant.application.RequiredPermission;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import io.github.stevdrey.dokene.web.StrongEtag;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/integrations/whatsapp")
public class IntegrationController {
    private final IntegrationConfigurationService integrations;

    public IntegrationController(IntegrationConfigurationService integrations) {
        this.integrations = Objects.requireNonNull(integrations, "Integration service is required");
    }

    @RequiredPermission(TenantPermission.INTEGRATION_READ)
    @GetMapping
    public ResponseEntity<IntegrationResponse> read() {
        return respond(integrations.read());
    }

    @RequiredPermission(TenantPermission.INTEGRATION_MANAGE)
    @PutMapping
    public ResponseEntity<IntegrationResponse> update(@RequestHeader("If-Match") String ifMatch,
            @RequestBody IntegrationWriteRequest request) {
        long expectedVersion = version(ifMatch);
        if (request == null || request.enabled() == null) {
            throw invalidInput();
        }
        return respond(integrations.update(new IntegrationConfigurationUpdate(request.phoneNumberId(),
                request.businessAccountId(), request.displayPhoneNumber(), request.displayPhoneRegion(),
                request.secretRef(), request.enabled(), expectedVersion)));
    }

    @RequiredPermission(TenantPermission.INTEGRATION_MANAGE)
    @PutMapping("/outbound")
    public ResponseEntity<IntegrationResponse> killSwitch(@RequestHeader("If-Match") String ifMatch,
            @RequestBody OutboundSwitchRequest request) {
        long expectedVersion = version(ifMatch);
        if (request == null || request.enabled() == null) {
            throw invalidInput();
        }
        return respond(integrations.setOutbound(request.enabled(), expectedVersion));
    }

    private static ResponseEntity<IntegrationResponse> respond(TenantMessagingIntegration row) {
        return ResponseEntity.ok().eTag(StrongEtag.format(row.version())).body(IntegrationResponse.from(row));
    }

    private static long version(String ifMatch) {
        return StrongEtag.parse(ifMatch).orElseThrow(IntegrationController::invalidInput);
    }

    private static IntegrationRefusedException invalidInput() {
        return new IntegrationRefusedException(IntegrationErrorCode.INVALID_INPUT);
    }
}
