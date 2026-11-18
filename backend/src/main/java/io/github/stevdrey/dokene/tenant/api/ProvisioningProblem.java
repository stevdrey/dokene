// ABOUTME: The problem+json body of a refused workspace provisioning request (spec 0021), in the shape ADR 0023 uses:
// ABOUTME: exactly type, title, status and code, with no detail, ids or free text.
package io.github.stevdrey.dokene.tenant.api;

import java.util.Objects;
import org.springframework.http.HttpStatus;

public record ProvisioningProblem(String type, String title, int status, ProvisioningProblemCode code) {

    public ProvisioningProblem {
        Objects.requireNonNull(code, "Problem code is required");
    }

    public static ProvisioningProblem forbidden(ProvisioningProblemCode code) {
        return new ProvisioningProblem(
                "about:blank", HttpStatus.FORBIDDEN.getReasonPhrase(), HttpStatus.FORBIDDEN.value(), code);
    }
}
