package io.github.stevdrey.dokene.integration.api;

import io.github.stevdrey.dokene.http.StrictRequests;
import io.github.stevdrey.dokene.integration.api.IntegrationApiContract.IntegrationRequest;
import io.github.stevdrey.dokene.integration.api.IntegrationApiContract.InvalidRequestException;
import io.github.stevdrey.dokene.integration.api.IntegrationApiContract.IntegrationResponse;
import io.github.stevdrey.dokene.integration.api.IntegrationApiContract.OutboundRequest;
import io.github.stevdrey.dokene.integration.application.WhatsAppIntegrationService;
import io.github.stevdrey.dokene.integration.application.WhatsAppIntegrationService.Create;
import io.github.stevdrey.dokene.integration.application.WhatsAppIntegrationService.IntegrationView;
import io.github.stevdrey.dokene.integration.application.WhatsAppIntegrationService.Precondition;
import io.github.stevdrey.dokene.integration.application.WhatsAppIntegrationService.Version;
import io.github.stevdrey.dokene.tenant.application.RequiredPermission;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

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
    public IntegrationResponse get(HttpServletResponse response) {
        return respond(integrations.get(), response);
    }

    @RequiredPermission(TenantPermission.INTEGRATION_MANAGE)
    @PutMapping
    public IntegrationResponse put(
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch,
            @RequestBody JsonNode body, HttpServletResponse response) {
        IntegrationRequest request = IntegrationApiContract.read(body);
        IntegrationView saved = integrations.upsert(precondition(ifMatch, ifNoneMatch), request.phoneNumberId(),
                request.businessAccountId(), request.displayPhoneNumber(), request.enabled(),
                request.secretRef());
        return respond(saved, response);
    }

    /** Bumps the credential revision after the secret was replaced in the store behind the same reference. */
    @RequiredPermission(TenantPermission.INTEGRATION_MANAGE)
    @PostMapping("/credential-rotations")
    public IntegrationResponse rotateCredential(
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch,
            HttpServletResponse response) {
        if (ifNoneMatch != null || !(precondition(ifMatch, null) instanceof Version(long expectedVersion))) {
            throw new InvalidRequestException();
        }
        return respond(integrations.rotateCredential(expectedVersion), response);
    }

    /**
     * Asks the provider adapter to prove that the tenant's credential reaches the stored phone number through the
     * stored business account, and records the proof (issue 210). Strong {@code If-Match}, no body.
     */
    @RequiredPermission(TenantPermission.INTEGRATION_MANAGE)
    @PostMapping("/ownership-verifications")
    public IntegrationResponse verifyOwnership(
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch,
            HttpServletResponse response) {
        if (ifNoneMatch != null || !(precondition(ifMatch, null) instanceof Version(long expectedVersion))) {
            throw new InvalidRequestException();
        }
        return respond(integrations.verifyOwnership(expectedVersion), response);
    }

    /**
     * The tenant outbound kill switch (spec 0008). Strong {@code If-Match} and a one member body; the switch is
     * independent of {@code enabled} and of the ownership proof. The global switch has no route.
     */
    @RequiredPermission(TenantPermission.INTEGRATION_MANAGE)
    @PutMapping("/outbound")
    public IntegrationResponse setOutbound(
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch,
            @RequestBody JsonNode body, HttpServletResponse response) {
        if (ifNoneMatch != null || !(precondition(ifMatch, null) instanceof Version(long expectedVersion))) {
            throw new InvalidRequestException();
        }
        OutboundRequest request = IntegrationApiContract.readOutbound(body);
        return respond(integrations.setOutboundEnabled(expectedVersion, request.enabled()), response);
    }

    /**
     * The ETag is the configuration concurrency token: the stored row's version, which every change to the ownership
     * proof and to the tenant kill switch also bumps. The row can still change behind a client between reads, so the
     * response is never cacheable and the handlers return the body directly (not a {@code ResponseEntity}) with headers
     * set on the servlet response, which keeps Spring from answering a matching {@code If-None-Match} GET with a
     * {@code 304}.
     */
    private IntegrationResponse respond(IntegrationView view, HttpServletResponse response) {
        var integration = view.integration();
        response.setHeader("ETag", StrictRequests.strongEtag(integration.version()));
        response.setHeader("Cache-Control", "no-store");
        return new IntegrationResponse(integration.enabled(), view.outboundEnabled(), integration.phoneNumberId(),
                integration.businessAccountId(), integration.displayPhoneNumber(), integration.secretRef(),
                view.webhookVerified(), integration.version());
    }

    /**
     * Every mutation states exactly one precondition (ADR 0023 §4.1): {@code If-Match} with one strong canonical
     * numeric ETag such as {@code "0"} to replace, or {@code If-None-Match: *} to create. Missing, both or malformed
     * is a 400.
     */
    private static Precondition precondition(String ifMatch, String ifNoneMatch) {
        if ((ifMatch == null) == (ifNoneMatch == null)) {
            throw new InvalidRequestException();
        }
        if (ifNoneMatch != null) {
            if (!"*".equals(ifNoneMatch)) {
                throw new InvalidRequestException();
            }
            return new Create();
        }
        return new Version(StrictRequests.strongNumericVersion(ifMatch).orElseThrow(InvalidRequestException::new));
    }
}
