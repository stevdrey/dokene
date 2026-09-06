package io.github.stevdrey.dokene.identity.security;

import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.security.AuthenticatedTenantIdentity;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/** Session principal that retains validated OIDC data while exposing only an internal identity to tenant code. */
public final class InternalOidcUser implements OidcUser, AuthenticatedTenantIdentity {

    private final OidcUser delegate;
    private final IdentityId identityId;

    InternalOidcUser(OidcUser delegate, IdentityId identityId) {
        this.delegate = Objects.requireNonNull(delegate, "OIDC user is required");
        this.identityId = Objects.requireNonNull(identityId, "Identity ID is required");
    }

    @Override
    public IdentityId identityId() {
        return identityId;
    }

    @Override
    public Map<String, Object> getClaims() {
        return delegate.getClaims();
    }

    @Override
    public OidcUserInfo getUserInfo() {
        return delegate.getUserInfo();
    }

    @Override
    public OidcIdToken getIdToken() {
        return delegate.getIdToken();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return delegate.getAuthorities();
    }

    @Override
    public Map<String, Object> getAttributes() {
        return delegate.getAttributes();
    }

    @Override
    public String getName() {
        return identityId.value().toString();
    }
}
