// ABOUTME: Supported values of dokene.integration.secrets.source; blank disables, anything unknown refuses startup.
// ABOUTME: The error never echoes the offending value, because a typo there could be a pasted secret.
package io.github.stevdrey.dokene.integration.secrets;

import java.util.Arrays;
import java.util.stream.Collectors;

public enum SecretSourceType {
    DISABLED(null),
    LOCAL("local");

    public static final String PROPERTY = "dokene.integration.secrets.source";

    private final String propertyValue;

    SecretSourceType(String propertyValue) {
        this.propertyValue = propertyValue;
    }

    /**
     * Matches case insensitively and without trimming, like {@code AiProviderType}. Unset or blank selects
     * {@link #DISABLED}; any other unrecognized value is a configuration error and never falls back.
     */
    public static SecretSourceType fromProperty(String value) {
        if (value == null || value.isBlank()) {
            return DISABLED;
        }
        return Arrays.stream(values())
                .filter(type -> type.propertyValue != null && type.propertyValue.equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Unsupported " + PROPERTY + " value. Supported values: "
                        + supportedValues() + " (leave unset or blank to disable secret resolution)."));
    }

    private static String supportedValues() {
        return Arrays.stream(values())
                .filter(type -> type.propertyValue != null)
                .map(type -> type.propertyValue)
                .collect(Collectors.joining(", "));
    }
}
