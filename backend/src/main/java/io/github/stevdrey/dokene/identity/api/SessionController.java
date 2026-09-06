package io.github.stevdrey.dokene.identity.api;

import io.github.stevdrey.dokene.tenant.security.AuthenticatedTenantIdentity;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/session")
public class SessionController {

    @GetMapping
    SessionResponse current(@AuthenticationPrincipal AuthenticatedTenantIdentity identity, CsrfToken csrfToken) {
        return new SessionResponse(true, identity.identityId().value(), csrfToken.getToken());
    }

    record SessionResponse(boolean authenticated, UUID identityId, String csrfToken) {
    }
}
