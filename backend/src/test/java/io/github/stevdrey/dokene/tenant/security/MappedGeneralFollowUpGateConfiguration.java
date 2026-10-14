package io.github.stevdrey.dokene.tenant.security;

import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import io.github.stevdrey.dokene.messaging.application.TemplateMappingGate;
import io.github.stevdrey.dokene.messaging.application.TemplateMappingStatus;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the registry-backed gate for messaging tests that exercise submit without seeding a verified template. */
@TestConfiguration(proxyBeanMethods = false)
public class MappedGeneralFollowUpGateConfiguration {
    @Bean
    @Primary
    TemplateMappingGate mappedGeneralFollowUpGate() {
        return intent -> intent == SemanticTemplateIntent.GENERAL_FOLLOW_UP ? TemplateMappingStatus.MAPPED
                : TemplateMappingStatus.NOT_MAPPED;
    }
}
