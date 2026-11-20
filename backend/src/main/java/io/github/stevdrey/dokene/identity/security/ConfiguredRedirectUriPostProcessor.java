// ABOUTME: Rebuilds Boot's client registration repository with the login callback derived from DOKENE_PUBLIC_ORIGIN
// ABOUTME: (spec 0019, issue 318), so no consumer ever sees a redirect URI template that reads the request.
package io.github.stevdrey.dokene.identity.security;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;

/**
 * Boot's repository bean and its "client configured" condition stay as they are, so there is never a second
 * repository bean. The validator is resolved first, so a leftover redirect setting stops startup instead of being
 * silently replaced. Any other repository stops startup: its registrations would keep whatever redirect URI
 * template they were built with, unchecked. So does a repository of Boot's class that holds anything but the one
 * "dokene" registration: Boot's bean backs off for a hand written one, which the validated settings do not describe.
 */
final class ConfiguredRedirectUriPostProcessor implements BeanPostProcessor {

    private final ObjectProvider<RedirectOriginStartupValidator> validator;

    ConfiguredRedirectUriPostProcessor(ObjectProvider<RedirectOriginStartupValidator> validator) {
        this.validator = validator;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (!(bean instanceof ClientRegistrationRepository)) {
            return bean;
        }
        if (!(bean instanceof InMemoryClientRegistrationRepository repository)) {
            throw new IllegalStateException("The ClientRegistrationRepository bean \"" + beanName + "\" is not Spring "
                    + "Boot's own: the backend derives the login callback from DOKENE_PUBLIC_ORIGIN only on the "
                    + "repository Boot builds from spring.security.oauth2.client.registration; remove the custom bean");
        }
        String callbackTemplate = validator.getObject().registrationOrigin().loginCallbackTemplate();
        List<ClientRegistration> derived = new ArrayList<>();
        for (ClientRegistration registration : repository) {
            if (!registration.getRegistrationId().equals(RedirectOriginStartupValidator.REGISTRATION_ID)) {
                throw new IllegalStateException("The ClientRegistrationRepository bean \"" + beanName + "\" holds a "
                        + "registration other than \"dokene\", the only one the backend supports and has checked; "
                        + "remove the custom bean");
            }
            derived.add(ClientRegistration.withClientRegistration(registration).redirectUri(callbackTemplate).build());
        }
        return new InMemoryClientRegistrationRepository(derived);
    }
}
