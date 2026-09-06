package io.github.stevdrey.dokene.tenant.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

class TenantSecurityConfigurationTest {

    @Test
    void trimsConfiguredCorsOriginsAndDropsBlankEntries() {
        CorsConfigurationSource source = new TenantSecurityConfiguration().corsConfigurationSource(List.of(
                "http://frontend-a.example.test",
                " http://frontend-b.example.test ",
                " "
        ));

        CorsConfiguration configuration = source.getCorsConfiguration(new MockHttpServletRequest("GET", "/api/session"));

        assertThat(configuration).isNotNull();
        assertThat(configuration.getAllowedOrigins()).containsExactly(
                "http://frontend-a.example.test",
                "http://frontend-b.example.test"
        );
    }
}
