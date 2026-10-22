// ABOUTME: The /webhooks/** filter chain (ADR 0023 §6): stateless, CSRF-exempt callback only, no login, ordered first.
// ABOUTME: The HMAC over the raw body is the only trust decision; a session cookie on this route proves nothing.
package io.github.stevdrey.dokene.integration.whatsapp.webhook;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.DefaultCsrfToken;

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
                // CSRF stays on for the chain and exempts only the callback, whose HMAC over the raw body is its
                // credential; it carries no cookie-based authority a forged cross-site request could ride on.
                // The default repository keeps the token in a new HttpSession, which put a JSESSIONID on every denied
                // non-callback request (issue 266). This one never stores a token, so every such request still fails
                // CSRF and nothing on this chain ever starts a session.
                .csrf(csrf -> csrf.ignoringRequestMatchers(WhatsAppWebhookController.PATH)
                        .csrfTokenRepository(new SessionFreeCsrfTokenRepository()))
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

    /** Issues a throwaway token and stores none, so no request can ever present a matching one. */
    static final class SessionFreeCsrfTokenRepository implements CsrfTokenRepository {
        @Override
        public CsrfToken generateToken(HttpServletRequest request) {
            return new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", UUID.randomUUID().toString());
        }

        @Override
        public void saveToken(CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
        }

        @Override
        public CsrfToken loadToken(HttpServletRequest request) {
            return null;
        }
    }
}
