// ABOUTME: Module-neutral transport strictness for every API: one strict JSON request reader and one strong numeric
// ABOUTME: ETag grammar (ADR 0023 §4.1, ADR 0024 §1), parsed and formatted here so every controller agrees on it.
package io.github.stevdrey.dokene.http;

import java.util.Optional;
import java.util.OptionalLong;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.LogicalType;

/** Callers map an empty answer to their own closed {@code INVALID_INPUT} refusal; nothing here echoes input. */
public final class StrictRequests {
    /**
     * Exactly one strong canonical numeric ETag; weak, list, wildcard and zero-padded forms are refused. Nineteen
     * digits admit every non-negative {@code long}; a value above {@link Long#MAX_VALUE} is refused when parsed.
     */
    private static final Pattern STRONG_NUMERIC_ETAG = Pattern.compile("^\"(0|[1-9][0-9]{0,18})\"$");

    /**
     * Unknown members, unknown enum values, missing required values and scalar coercions (a number or boolean for a
     * text member, a number or empty string for an enum) are all refused instead of ignored or coerced.
     */
    private static final JsonMapper STRICT_READER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            // ALLOW_COERCION_OF_SCALARS leaves number/boolean to String coercion on: refuse it explicitly.
            .withCoercionConfig(LogicalType.Textual, config -> config
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
            .withCoercionConfig(LogicalType.Enum, config -> config
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail))
            .build();

    private StrictRequests() {
    }

    public static JsonMapper reader() {
        return STRICT_READER;
    }

    /** The bound request, or empty for an absent, {@code null} or malformed body. */
    public static <T> Optional<T> read(JsonNode body, Class<T> type) {
        if (body == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(STRICT_READER.treeToValue(body, type));
        } catch (JacksonException malformed) {
            return Optional.empty();
        }
    }

    /**
     * The version named by one strong numeric ETag such as {@code "3"}, for any non-negative {@code long}, so every
     * value {@link #strongEtag} emits parses back; empty for any other header value.
     */
    public static OptionalLong strongNumericVersion(String header) {
        if (header == null || !STRONG_NUMERIC_ETAG.matcher(header).matches()) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(header.substring(1, header.length() - 1)));
        } catch (NumberFormatException aboveLongRange) {
            return OptionalLong.empty();
        }
    }

    /**
     * The strong ETag such as {@code "3"} for a version, the only form {@link #strongNumericVersion} accepts. Versions
     * are never negative; one would emit an ETag no client could send back, so it is refused here.
     */
    public static String strongEtag(long version) {
        if (version < 0) {
            throw new IllegalArgumentException("version must be non-negative");
        }
        return "\"" + version + "\"";
    }
}
