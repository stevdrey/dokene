package io.github.stevdrey.dokene.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.security.AuthenticatedTenantIdentity;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.web.csrf.CsrfToken;

class SessionControllerTest {

    @Test
    void exposesOnlyInternalSessionStateAndCsrfToken() {
        IdentityId identityId = new IdentityId(UUID.randomUUID());
        AuthenticatedTenantIdentity identity = () -> identityId;
        CsrfToken csrfToken = mock();
        when(csrfToken.getToken()).thenReturn("csrf-value");

        SessionController.SessionResponse response = new SessionController().current(identity, csrfToken);

        assertThat(response.authenticated()).isTrue();
        assertThat(response.identityId()).isEqualTo(identityId.value());
        assertThat(response.csrfToken()).isEqualTo("csrf-value");
    }
}
