// ABOUTME: The /webhooks/** filter chain (ADR 0023 §6): stateless, no CSRF, no login, ordered before the session chain.
// ABOUTME: The HMAC over the raw body is the only trust decision; a session cookie on this route proves nothing.
package io.github.stevdrey.dokene.integration.whatsapp.webhook;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * Matches only the webhook paths, so every other route keeps the BFF chain with its session, CSRF and tenant filter.
 * The chain loads no security context from the session and stores none, and denies anything the controller does not
 * map; there is no route under {@code /webhooks/**} other than the WhatsApp callback.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = Type.SERVLET)
class WebhookSecurityConfiguration {
    static final String WEBHOOK_PATHS = "/webhooks/**";

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain webhookSecurityFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher(WEBHOOK_PATHS)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(WhatsAppWebhookController.PATH).permitAll()
                        .anyRequest().denyAll())
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context.disable())
                .requestCache(cache -> cache.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.FORBIDDEN))
                        .accessDeniedHandler((request, response, denied) ->
                                response.sendError(HttpStatus.FORBIDDEN.value())));
        return http.build();
    }
}
