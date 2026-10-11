// ABOUTME: Unit tests for request binding (spec 0005 AC-3, AC-5): unknown fields are refused, enabled may be absent.
// ABOUTME: Spring Boot disables FAIL_ON_UNKNOWN_PROPERTIES globally, so the records reject unknown fields themselves.
package io.github.stevdrey.dokene.integration.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class IntegrationWriteRequestTest {
    /** Mirrors the Boot default: unknown properties are tolerated by the mapper, so the record has to refuse them. */
    private final ObjectMapper json = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    @Test
    void bindsTheSixFieldsAndLeavesOmittedOnesNull() {
        IntegrationWriteRequest request = json.readValue("""
                {"phoneNumberId":"100000000000000","enabled":true}
                """, IntegrationWriteRequest.class);

        assertThat(request.phoneNumberId()).isEqualTo("100000000000000");
        assertThat(request.businessAccountId()).isNull();
        assertThat(request.secretRef()).isNull();
        assertThat(request.enabled()).isTrue();
        assertThat(json.readValue("{}", IntegrationWriteRequest.class).enabled()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"outboundEnabled", "webhookVerified", "provider", "version", "updatedAt", "token"})
    void refusesAnyFieldOutsideTheSix(String field) {
        String body = "{\"enabled\":true,\"" + field + "\":true}";

        assertThatThrownBy(() -> json.readValue(body, IntegrationWriteRequest.class))
                .isInstanceOf(tools.jackson.databind.DatabindException.class);
    }

    @Test
    void theSwitchBodyBindsOnlyEnabled() {
        assertThat(json.readValue("{\"enabled\":false}", OutboundSwitchRequest.class).enabled()).isFalse();
        assertThat(json.readValue("{}", OutboundSwitchRequest.class).enabled()).isNull();
        assertThatThrownBy(() -> json.readValue("{\"enabled\":true,\"secretRef\":\"x\"}", OutboundSwitchRequest.class))
                .isInstanceOf(tools.jackson.databind.DatabindException.class);
    }
}
