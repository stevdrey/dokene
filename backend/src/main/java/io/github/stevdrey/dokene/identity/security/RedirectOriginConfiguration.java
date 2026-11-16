// ABOUTME: Wires the configured public origin (spec 0019, issue 318): its settings bean, the startup validator, and
// ABOUTME: the post processor that derives the login callback on Boot's own client registration repository.
package io.github.stevdrey.dokene.identity.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = Type.SERVLET)
class RedirectOriginConfiguration {

    /** Required: application.yml holds the one default, loopback on the port this instance listens on. */
    static final String BACK_CHANNEL_LOGOUT_URI = "${dokene.security.oidc.back-channel-logout-uri}";

    // Not lazy: the filter chains that read these beans are built at the first request, so under
    // spring.main.lazy-initialization a refused setting would otherwise surface only after the server is up.
    @Bean
    @Lazy(false)
    PublicOriginSettings publicOriginSettings(
            @Value("${dokene.security.public-origin:}") String configured,
            ObjectProvider<OAuth2ClientProperties> clientProperties
    ) {
        return new PublicOriginSettings(configured, clientProperties.getIfAvailable() != null);
    }

    @Bean
    @Lazy(false)
    RedirectOriginStartupValidator redirectOriginStartupValidator(
            PublicOriginSettings settings,
            ObjectProvider<OAuth2ClientProperties> clientProperties,
            ServerProperties serverProperties,
            @Value(BACK_CHANNEL_LOGOUT_URI) String backChannelLogoutUri
    ) {
        return new RedirectOriginStartupValidator(settings, clientProperties.getIfAvailable(),
                serverProperties.getForwardHeadersStrategy(), serverProperties.getServlet().getContextPath(),
                serverProperties.getSsl() != null && serverProperties.getSsl().isEnabled(), backChannelLogoutUri);
    }

    @Bean
    static ConfiguredRedirectUriPostProcessor configuredRedirectUriPostProcessor(
            ObjectProvider<RedirectOriginStartupValidator> validator
    ) {
        return new ConfiguredRedirectUriPostProcessor(validator);
    }
}
