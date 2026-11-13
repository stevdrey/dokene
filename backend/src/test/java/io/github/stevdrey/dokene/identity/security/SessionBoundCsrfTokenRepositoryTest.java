// ABOUTME: Unit tests of the CSRF token store of the BFF chain (issue 311): a token is saved only into a session
// ABOUTME: that already exists, so an anonymous request never gets a session just to hold a token it cannot read.
package io.github.stevdrey.dokene.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DeferredCsrfToken;

class SessionBoundCsrfTokenRepositoryTest {

    private final SessionBoundCsrfTokenRepository repository = new SessionBoundCsrfTokenRepository();
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    void savingWithoutASessionStoresNothingAndCreatesNoSession() {
        repository.saveToken(repository.generateToken(request), request, response);

        assertThat(request.getSession(false)).isNull();
        assertThat(repository.loadToken(request)).isNull();
    }

    @Test
    void savingIntoAnExistingSessionStoresTheToken() {
        request.getSession(true);
        CsrfToken token = repository.generateToken(request);

        repository.saveToken(token, request, response);

        assertThat(repository.loadToken(request).getToken()).isEqualTo(token.getToken());
    }

    @Test
    void savingNullRemovesTheStoredToken() {
        request.getSession(true);
        repository.saveToken(repository.generateToken(request), request, response);

        repository.saveToken(null, request, response);

        assertThat(repository.loadToken(request)).isNull();
    }

    @Test
    void savingNullWithoutASessionCreatesNoSession() {
        repository.saveToken(null, request, response);

        assertThat(request.getSession(false)).isNull();
    }

    /** The path the CSRF filter takes: a deferred token must go through the same guard as a direct save. */
    @Test
    void aDeferredTokenReadWithoutASessionCreatesNoSession() {
        DeferredCsrfToken deferred = repository.loadDeferredToken(request, response);

        assertThat(deferred.get().getToken()).isNotBlank();
        assertThat(deferred.isGenerated()).isTrue();
        assertThat(request.getSession(false)).isNull();
    }

    @Test
    void aDeferredTokenReadWithASessionIsStoredAndStable() {
        request.getSession(true);

        String first = repository.loadDeferredToken(request, response).get().getToken();
        String second = repository.loadDeferredToken(request, response).get().getToken();

        assertThat(second).isEqualTo(first);
    }
}
