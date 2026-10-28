// ABOUTME: Registers Phase3ErrorPageFilter for the servlet ERROR dispatch only, right after Spring Security's filter,
// ABOUTME: so the error dispatch keeps every security decision it had and only its body changes (issue 293).
package io.github.stevdrey.dokene.messaging.api;

import jakarta.servlet.DispatcherType;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
public class Phase3ErrorPageConfiguration {
    @Bean
    FilterRegistrationBean<Phase3ErrorPageFilter> phase3ErrorPageFilter(JsonMapper json) {
        FilterRegistrationBean<Phase3ErrorPageFilter> registration =
                new FilterRegistrationBean<>(new Phase3ErrorPageFilter(json));
        registration.setDispatcherTypes(DispatcherType.ERROR);
        registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER + 1);
        return registration;
    }
}
