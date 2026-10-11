// ABOUTME: Body of PUT /api/integrations/whatsapp/outbound: exactly { "enabled": boolean } (spec 0005 AC-5).
// ABOUTME: Any other field is refused at binding, since Spring Boot disables FAIL_ON_UNKNOWN_PROPERTIES globally.
package io.github.stevdrey.dokene.integration.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;

public record OutboundSwitchRequest(Boolean enabled) {

    @JsonAnySetter
    void unknownField(String name, Object value) {
        throw new IllegalArgumentException("Unknown field");
    }
}
