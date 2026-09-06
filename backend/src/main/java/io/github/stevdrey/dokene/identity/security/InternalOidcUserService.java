package io.github.stevdrey.dokene.identity.security;

import io.github.stevdrey.dokene.identity.application.OidcIdentityMapping;
import io.github.stevdrey.dokene.identity.application.OidcIdentityResolver;
import java.net.URI;
import java.net.URISyntaxException;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/** Adapts a framework-validated OIDC user into Dokene's provider-neutral principal. */
public final class InternalOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

    private static final OAuth2Error INVALID_ID_TOKEN = new OAuth2Error("invalid_id_token");

    private final OAuth2UserService<OidcUserRequest, OidcUser> delegate;
    private final OidcIdentityResolver identityResolver;

    public InternalOidcUserService(OidcIdentityResolver identityResolver) {
        this(new OidcUserService(), identityResolver);
    }

    InternalOidcUserService(
            OAuth2UserService<OidcUserRequest, OidcUser> delegate,
            OidcIdentityResolver identityResolver
    ) {
        this.delegate = delegate;
        this.identityResolver = identityResolver;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
        OidcUser oidcUser = delegate.loadUser(userRequest);
        OidcIdToken idToken = oidcUser.getIdToken();
        String subject = oidcUser.getSubject();
        if (idToken == null || idToken.getIssuer() == null || subject == null) {
            throw new OAuth2AuthenticationException(INVALID_ID_TOKEN);
        }
        try {
            URI issuer = idToken.getIssuer().toURI();
            OidcIdentityMapping mapping = new OidcIdentityMapping(issuer, subject);
            return new InternalOidcUser(oidcUser, identityResolver.resolve(mapping));
        } catch (IllegalArgumentException | URISyntaxException exception) {
            throw new OAuth2AuthenticationException(INVALID_ID_TOKEN);
        }
    }
}
