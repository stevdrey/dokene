// ABOUTME: Refuses startup when the live client registration repository does not carry the login callback derived
// ABOUTME: from DOKENE_PUBLIC_ORIGIN (issue 318), which is what a repository the post processor never saw would hold.
package io.github.stevdrey.dokene.identity.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

/**
 * Spring builds a bean that a post processor depends on before the post processors are registered, and only logs an
 * INFO line about it. Such a repository would keep Boot's {@code {baseUrl}} callback template, which reads the
 * request, while every settings check passes. So the result is checked on the repository the application will use.
 */
final class DerivedRedirectUriCheck implements SmartInitializingSingleton {

    private final ObjectProvider<ClientRegistrationRepository> repositories;
    private final RedirectOriginStartupValidator validator;

    DerivedRedirectUriCheck(ObjectProvider<ClientRegistrationRepository> repositories,
            RedirectOriginStartupValidator validator) {
        this.repositories = repositories;
        this.validator = validator;
    }

    @Override
    public void afterSingletonsInstantiated() {
        ClientRegistrationRepository repository = repositories.getIfAvailable();
        if (repository == null) {
            return;
        }
        String callbackTemplate = validator.registrationOrigin().loginCallbackTemplate();
        if (!(repository instanceof Iterable<?> registrations)) {
            throw refused();
        }
        for (Object registration : registrations) {
            if (!(registration instanceof ClientRegistration client)
                    || !callbackTemplate.equals(client.getRedirectUri())) {
                throw refused();
            }
        }
    }

    private static IllegalStateException refused() {
        return new IllegalStateException("The client registration repository does not carry the login callback "
                + "derived from DOKENE_PUBLIC_ORIGIN: it was built before the backend could rewrite it, so its "
                + "redirect URI would follow the request; remove whatever requires a ClientRegistrationRepository "
                + "that early, such as a BeanPostProcessor that depends on one");
    }
}
