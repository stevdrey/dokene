package io.github.stevdrey.dokene.tenant.security;

import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import io.github.stevdrey.dokene.messaging.application.OutboundSafetyControl;
import io.github.stevdrey.dokene.messaging.domain.ApprovalFingerprint.SenderRevision;
import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.template.application.TemplateMappingResolver;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the registry-backed template resolver and the integration sender for messaging tests that
 * exercise submit and approval without seeding a verified template or an integration. Only GENERAL_FOLLOW_UP is
 * mapped. Tests that change the mapping or the sender call {@link #reset()} first, because the Spring context (and
 * so these beans) is shared between test classes.
 */
@TestConfiguration(proxyBeanMethods = false)
public class MappedGeneralFollowUpGateConfiguration {
    @Bean
    @Primary
    StubTemplateResolver stubTemplateResolver() {
        return new StubTemplateResolver();
    }

    @Bean
    @Primary
    StubSafetyControl stubSafetyControl() {
        return new StubSafetyControl();
    }

    public static void reset(StubTemplateResolver templates, StubSafetyControl safety) {
        templates.version = 1;
        templates.language = "es";
        templates.parameterNames = List.of("customer_name", "message_body");
        templates.mapped = true;
        safety.configured = true;
        safety.credentialRevision = 1;
    }

    /** A sendable GENERAL_FOLLOW_UP mapping whose revision a test can edit. */
    public static final class StubTemplateResolver implements TemplateMappingResolver {
        volatile long version = 1;
        volatile String language = "es";
        volatile List<String> parameterNames = List.of("customer_name", "message_body");
        volatile boolean mapped = true;

        @Override
        public Resolution resolve(SemanticTemplateIntent intent) {
            if (intent != SemanticTemplateIntent.GENERAL_FOLLOW_UP || !mapped) {
                return new NotMapped();
            }
            return new Resolved(new ResolvedTemplateMapping(intent, "general_follow_up", language, parameterNames,
                    version));
        }
    }

    /** One stable sender per tenant; sending itself stays blocked, as in production until #144. */
    public static final class StubSafetyControl implements OutboundSafetyControl {
        volatile boolean configured = true;
        volatile long credentialRevision = 1;

        @Override
        public Optional<SenderRevision> configuredSender(TenantId tenantId) {
            if (!configured) {
                return Optional.empty();
            }
            UUID integrationId = UUID.nameUUIDFromBytes(tenantId.value().toString().getBytes(StandardCharsets.UTF_8));
            return Optional.of(new SenderRevision(integrationId, "100000000000001", "200000000000001",
                    credentialRevision));
        }

        @Override
        public Decision evaluate(TenantId tenantId) {
            return new Blocked(MessagingErrorCode.OUTBOUND_DISABLED_GLOBALLY);
        }
    }
}
