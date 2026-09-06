package io.github.stevdrey.dokene.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.stevdrey.dokene.identity.application.OidcIdentityMapping;
import io.github.stevdrey.dokene.identity.application.OidcIdentityResolver;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

class InternalOidcUserServiceTest {

    private final OAuth2UserService<OidcUserRequest, OidcUser> delegate = mock();
    private final OidcIdentityResolver identityResolver = mock();
    private final InternalOidcUserService service = new InternalOidcUserService(delegate, identityResolver);

    @Test
    void adaptsValidatedIssuerAndSubjectToInternalIdentity() {
        OidcUserRequest request = mock();
        OidcUser user = userWithClaims(Map.of(
                "iss", "https://identity.example.test/issuer",
                "sub", "provider-subject"
        ));
        IdentityId identityId = new IdentityId(UUID.randomUUID());
        when(delegate.loadUser(request)).thenReturn(user);
        when(identityResolver.resolve(org.mockito.ArgumentMatchers.any())).thenReturn(identityId);

        OidcUser result = service.loadUser(request);

        assertThat(result).isInstanceOf(InternalOidcUser.class);
        assertThat(((InternalOidcUser) result).identityId()).isEqualTo(identityId);
        assertThat(result.getName()).isEqualTo(identityId.value().toString());
        ArgumentCaptor<OidcIdentityMapping> mapping = ArgumentCaptor.forClass(OidcIdentityMapping.class);
        verify(identityResolver).resolve(mapping.capture());
        assertThat(mapping.getValue().issuer().toString()).isEqualTo("https://identity.example.test/issuer");
        assertThat(mapping.getValue().subject()).isEqualTo("provider-subject");
    }

    @Test
    void rejectsValidatedUserWithoutAnIssuerBeforePersistence() {
        OidcUserRequest request = mock();
        OidcUser user = userWithClaims(Map.of("sub", "provider-subject"));
        when(delegate.loadUser(request)).thenReturn(user);

        assertThatThrownBy(() -> service.loadUser(request))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class,
                        exception -> assertThat(exception.getError().getErrorCode()).isEqualTo("invalid_id_token"));
    }

    @Test
    void rejectsValidatedUserWithoutAnIdTokenBeforePersistence() {
        OidcUserRequest request = mock();
        OidcUser user = mock();
        when(user.getSubject()).thenReturn("provider-subject");
        when(delegate.loadUser(request)).thenReturn(user);

        assertThatThrownBy(() -> service.loadUser(request))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class,
                        exception -> assertThat(exception.getError().getErrorCode()).isEqualTo("invalid_id_token"));
    }

    @Test
    void doesNotMaskUnexpectedResolverFailuresAsInvalidTokens() {
        OidcUserRequest request = mock();
        OidcUser user = userWithClaims(Map.of(
                "iss", "https://identity.example.test/issuer",
                "sub", "provider-subject"
        ));
        when(delegate.loadUser(request)).thenReturn(user);
        when(identityResolver.resolve(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new NullPointerException("unexpected defect"));

        assertThatThrownBy(() -> service.loadUser(request))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("unexpected defect");
    }

    private OidcUser userWithClaims(Map<String, Object> claims) {
        OidcIdToken token = new OidcIdToken("not-logged", Instant.now(), Instant.now().plusSeconds(60), claims);
        OidcUser user = mock();
        when(user.getIdToken()).thenReturn(token);
        when(user.getSubject()).thenReturn((String) claims.get("sub"));
        return user;
    }
}
