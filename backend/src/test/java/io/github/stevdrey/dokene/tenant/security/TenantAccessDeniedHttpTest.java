package io.github.stevdrey.dokene.tenant.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.stevdrey.dokene.tenant.application.AuthorizationAuditListener;
import io.github.stevdrey.dokene.tenant.application.DefaultTenantAuthorizationService;
import io.github.stevdrey.dokene.tenant.application.ScopedValueTenantContextProvider;
import io.github.stevdrey.dokene.tenant.application.TenantAuthorizationService;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = TenantAccessDeniedHttpTest.DeniedController.class)
@Import({DefaultTenantAuthorizationService.class, TenantAccessDeniedHttpTest.TestSecurityConfiguration.class})
class TenantAccessDeniedHttpTest {

    @TestConfiguration
    static class TestSecurityConfiguration {

        @Bean
        TenantContextProvider tenantContextProvider() {
            return new ScopedValueTenantContextProvider();
        }

        @Bean
        AuthorizationAuditListener authorizationAuditListener() {
            return AuthorizationAuditListener.noop();
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
            return http
                    .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                    .build();
        }
    }

    @RestController
    static class DeniedController {

        private final TenantAuthorizationService authorizationService;

        DeniedController(TenantAuthorizationService authorizationService) {
            this.authorizationService = authorizationService;
        }

        @GetMapping("/test/authorization/denied")
        void denied() {
            authorizationService.requirePermission(TenantPermission.CUSTOMER_READ);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void mapsTenantAccessDeniedExceptionToForbidden() throws Exception {
        mockMvc.perform(get("/test/authorization/denied"))
                .andExpect(status().isForbidden());
    }
}
