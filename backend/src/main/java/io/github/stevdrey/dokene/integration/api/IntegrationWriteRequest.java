// ABOUTME: Body of PUT /api/integrations/whatsapp: exactly the six configurable fields (spec 0005 AC-3).
// ABOUTME: Any other field is refused at binding, since Spring Boot disables FAIL_ON_UNKNOWN_PROPERTIES globally.
package io.github.stevdrey.dokene.integration.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;

public record IntegrationWriteRequest(String phoneNumberId, String businessAccountId, String displayPhoneNumber,
        String displayPhoneRegion, String secretRef, Boolean enabled) {

    @JsonAnySetter
    void unknownField(String name, Object value) {
        throw new IllegalArgumentException("Unknown field");
    }
}
