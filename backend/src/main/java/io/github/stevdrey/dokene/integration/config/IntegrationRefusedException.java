// ABOUTME: The only refusal the integration module throws; carries one closed error code.
// ABOUTME: The message is the code name only, so no identifier, number or reference can leak through it.
package io.github.stevdrey.dokene.integration.config;

import java.util.Objects;
import java.util.OptionalLong;

public class IntegrationRefusedException extends RuntimeException {
    private final IntegrationErrorCode code;
    private final OptionalLong currentVersion;

    public IntegrationRefusedException(IntegrationErrorCode code) {
        this(code, OptionalLong.empty());
    }

    /** A STALE_VERSION refusal names the current version so the response can carry the current ETag (AC-3). */
    public IntegrationRefusedException(IntegrationErrorCode code, long currentVersion) {
        this(code, OptionalLong.of(currentVersion));
    }

    private IntegrationRefusedException(IntegrationErrorCode code, OptionalLong currentVersion) {
        super(Objects.requireNonNull(code, "Error code is required").name());
        if (currentVersion.isPresent() && code != IntegrationErrorCode.STALE_VERSION) {
            throw new IllegalArgumentException("Only a stale version refusal carries the current version");
        }
        this.code = code;
        this.currentVersion = currentVersion;
    }

    public IntegrationErrorCode code() {
        return code;
    }

    public OptionalLong currentVersion() {
        return currentVersion;
    }
}
