package io.github.stevdrey.dokene.tenant.persistence.jpa;

import com.zaxxer.hikari.HikariDataSource;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration(proxyBeanMethods = false)
class TenantPersistenceConfiguration {

    @Bean
    @ConfigurationProperties(prefix = "spring.datasource.hikari")
    HikariDataSource hikariDataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean
    @Primary
    TenantAwareDataSource dataSource(HikariDataSource hikariDataSource, TenantContextProvider tenantContextProvider) {
        return new TenantAwareDataSource(hikariDataSource, tenantContextProvider);
    }
}
