// ABOUTME: Supported values of dokene.messaging.provider: blank selects none, local the simulated adapter.
// ABOUTME: Any other value is a configuration error that fails startup; feature 7 adds WHATSAPP.
package io.github.stevdrey.dokene.integration;

import java.util.Arrays;
import java.util.stream.Collectors;

public enum MessagingProviderType {
    DISABLED(null),
    LOCAL("local");

    public static final String PROPERTY = "dokene.messaging.provider";

    private final String propertyValue;

    MessagingProviderType(String propertyValue) {
        this.propertyValue = propertyValue;
    }

    /** Matched case insensitively like ConditionalOnProperty; blank is DISABLED, unknown throws. */
    public static MessagingProviderType fromProperty(String value) {
        if (value == null || value.isBlank()) {
            return DISABLED;
        }
        return Arrays.stream(values())
                .filter(type -> type.propertyValue != null && type.propertyValue.equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Unsupported " + PROPERTY + " value '" + value + "'. Supported values: "
                                + supportedValues() + " (leave unset or blank to select no provider)."));
    }

    private static String supportedValues() {
        return Arrays.stream(values())
                .filter(type -> type.propertyValue != null)
                .map(type -> type.propertyValue)
                .collect(Collectors.joining(", "));
    }
}
