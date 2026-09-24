package io.github.stevdrey.dokene.ai.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.regex.Pattern;

/**
 * A single key-value entry for drafted template inputs with bounded length and valid identifier key.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record DraftVariableEntry(String key, String value) {
    public static final int MAX_KEY_LENGTH = 50;
    public static final int MAX_VALUE_LENGTH = 500;
    private static final Pattern KEY_PATTERN = Pattern.compile("^[a-zA-Z0-9_]{1,50}$");

    public DraftVariableEntry {
        if (key == null || key.isBlank()) {
            throw new RecommendationValidationException("draftVariables.key", "Variable key is required");
        }
        if (key.length() > MAX_KEY_LENGTH || !KEY_PATTERN.matcher(key).matches()) {
            throw new RecommendationValidationException("draftVariables.key",
                    "Variable key must match ^[a-zA-Z0-9_]{1,50}$, got: " + key);
        }
        if (value == null) {
            throw new RecommendationValidationException("draftVariables.value", "Variable value is required");
        }
        if (value.codePointCount(0, value.length()) > MAX_VALUE_LENGTH) {
            throw new RecommendationValidationException("draftVariables.value",
                    "Variable value exceeds maximum length of " + MAX_VALUE_LENGTH + " characters");
        }
    }
}
