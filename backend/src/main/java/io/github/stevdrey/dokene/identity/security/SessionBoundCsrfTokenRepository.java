// ABOUTME: CSRF token store of the BFF chain (issue 311, audit M2): the session store, except that it never creates
// ABOUTME: a session to hold a token, so anonymous requests cannot grow session storage through the CSRF filter.
package io.github.stevdrey.dokene.identity.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

/**
 * A browser without a session can only read its token from the authenticated {@code GET /api/session}, so a token saved
 * for it would never be used. Its unsafe request is still compared with a fresh random token and refused.
 * {@code loadDeferredToken} is deliberately not overridden: the interface default saves through {@link #saveToken}, and
 * delegating it would skip the session check.
 */
public final class SessionBoundCsrfTokenRepository implements CsrfTokenRepository {

    private final HttpSessionCsrfTokenRepository delegate = new HttpSessionCsrfTokenRepository();

    @Override
    public CsrfToken generateToken(HttpServletRequest request) {
        return delegate.generateToken(request);
    }

    @Override
    public void saveToken(CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
        if (token != null && request.getSession(false) == null) {
            return;
        }
        delegate.saveToken(token, request, response);
    }

    @Override
    public CsrfToken loadToken(HttpServletRequest request) {
        return delegate.loadToken(request);
    }
}
