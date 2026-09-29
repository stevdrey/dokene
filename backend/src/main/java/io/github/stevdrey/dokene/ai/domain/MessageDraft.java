package io.github.stevdrey.dokene.ai.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Bounded, editable advisory follow-up message draft.
 * Represents generated message content and variables, not an executable command.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record MessageDraft(
        @JsonProperty("action") SemanticAction action,
        @JsonProperty("templateIntent") SemanticTemplateIntent templateIntent,
        @JsonProperty("body") String body,
        @JsonProperty("draftVariables") DraftVariables draftVariables,
        @JsonProperty("locale") String locale,
        @JsonProperty("evidence") List<String> evidence,
        @JsonProperty("warnings") List<String> warnings,
        @JsonProperty("rationale") String rationale,
        @JsonProperty("confidence") RecommendationConfidence confidence) implements DraftOutcome {

    public static final int MAX_BODY_LENGTH = 1000;
    public static final int MAX_METADATA_ITEMS = 10;
    public static final int MAX_METADATA_ITEM_LENGTH = 200;
    public static final int MAX_LOCALE_LENGTH = 16;
    public static final String DEFAULT_LOCALE = "es-419";
    public static final Pattern LOCALE_PATTERN = Pattern.compile("^[a-z]{2}(-[A-Za-z0-9]+)?$");

    public MessageDraft {
        if (action == null) {
            throw new RecommendationValidationException("action", "Action is required");
        }
        if (templateIntent == null) {
            throw new RecommendationValidationException("templateIntent", "Template intent is required");
        }
        if (body == null || body.isBlank()) {
            throw new RecommendationValidationException("body", "Message draft body cannot be blank");
        }
        if (body.codePointCount(0, body.length()) > MAX_BODY_LENGTH) {
            throw new RecommendationValidationException("body",
                    "Message draft body exceeds maximum length of " + MAX_BODY_LENGTH + " characters");
        }
        body = body.strip();

        draftVariables = draftVariables != null ? draftVariables : DraftVariables.empty();

        if (locale == null || locale.isBlank()) {
            locale = DEFAULT_LOCALE;
        } else {
            locale = locale.strip();
            if (locale.length() > MAX_LOCALE_LENGTH || !LOCALE_PATTERN.matcher(locale).matches()) {
                throw new RecommendationValidationException("locale",
                        "Invalid locale format: " + locale);
            }
        }

        evidence = validateMetadataList("evidence", evidence);
        warnings = validateMetadataList("warnings", warnings);

        if (!RecommendationRationale.isNonBlank(rationale)) {
            throw new RecommendationValidationException("rationale", "Rationale is required");
        }
        if (rationale.codePointCount(0, rationale.length()) > MAX_RATIONALE_LENGTH) {
            throw new RecommendationValidationException("rationale",
                    "Rationale exceeds maximum length of " + MAX_RATIONALE_LENGTH + " characters");
        }
        rationale = RecommendationRationale.trim(rationale);

        if (confidence == null) {
            throw new RecommendationValidationException("confidence", "Confidence is required");
        }
    }

    private static List<String> validateMetadataList(String fieldName, List<String> items) {
        if (items == null) {
            return List.of();
        }
        if (items.size() > MAX_METADATA_ITEMS) {
            throw new RecommendationValidationException(fieldName,
                    fieldName + " cannot exceed " + MAX_METADATA_ITEMS + " items, got: " + items.size());
        }
        for (String item : items) {
            if (item == null || item.isBlank()) {
                throw new RecommendationValidationException(fieldName,
                        fieldName + " items cannot be null or blank");
            }
            if (item.codePointCount(0, item.length()) > MAX_METADATA_ITEM_LENGTH) {
                throw new RecommendationValidationException(fieldName,
                        fieldName + " item exceeds maximum length of " + MAX_METADATA_ITEM_LENGTH + " characters");
            }
        }
        return List.copyOf(items);
    }
}
