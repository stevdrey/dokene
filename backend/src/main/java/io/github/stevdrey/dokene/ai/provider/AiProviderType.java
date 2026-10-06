package io.github.stevdrey.dokene.ai.provider;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Supported values of {@code dokene.ai.provider}. Unset or blank selects {@link #DISABLED}; any
 * unrecognized value is a configuration error and must never fall back to the disabled provider.
 */
public enum AiProviderType {

    DISABLED(null),
    FAKE("fake"),
    OPENAI("openai");

    public static final String PROPERTY = "dokene.ai.provider";

    private final String propertyValue;

    AiProviderType(String propertyValue) {
        this.propertyValue = propertyValue;
    }

    /**
     * Resolves the configured property value. The value is matched case-insensitively and without
     * trimming, consistent with {@code @ConditionalOnProperty}, which selects the concrete beans.
     *
     * @throws IllegalStateException if the value is non-blank and not a supported provider name
     */
    public static AiProviderType fromProperty(String value) {
        if (value == null || value.isBlank()) {
            return DISABLED;
        }
        return Arrays.stream(values())
                .filter(type -> type.propertyValue != null && type.propertyValue.equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Unsupported " + PROPERTY + " value '" + value + "'. Supported values: "
                                + supportedValues() + " (leave unset or blank to disable AI)."));
    }

    private static String supportedValues() {
        return Arrays.stream(values())
                .filter(type -> type.propertyValue != null)
                .map(type -> type.propertyValue)
                .collect(Collectors.joining(", "));
    }
}
