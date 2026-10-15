// ABOUTME: Startup failure for a bad dokene.integration.secrets.local entry; names the index and field only.
// ABOUTME: Never carries a cause, so no value, reference or tenant id can reach the failure analyzer or the log.
package io.github.stevdrey.dokene.integration.secrets;

public final class InvalidSecretConfigurationException extends IllegalStateException {
    private static final String PREFIX = "dokene.integration.secrets.local.entries[";

    private InvalidSecretConfigurationException(String message) {
        super(message);
    }

    public static InvalidSecretConfigurationException invalid(int index, String field) {
        return new InvalidSecretConfigurationException(PREFIX + index + "]." + field + " is invalid");
    }

    public static InvalidSecretConfigurationException duplicate(int index, String field) {
        return new InvalidSecretConfigurationException(PREFIX + index + "]." + field + " is a duplicate");
    }
}
