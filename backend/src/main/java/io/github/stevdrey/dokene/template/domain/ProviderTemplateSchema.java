// ABOUTME: Provider-neutral view of an approved template's parameter schema, parsed by the integration adapter.
// ABOUTME: Compared with the stored parameter names; only an exact match yields usable APPROVED evidence.
package io.github.stevdrey.dokene.template.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public sealed interface ProviderTemplateSchema
        permits ProviderTemplateSchema.BodyParameters, ProviderTemplateSchema.Unsupported,
        ProviderTemplateSchema.Unreadable {
    /** Scanning bounds: provider metadata is untrusted, so neither count nor token size is open-ended. */
    int MAX_PLACEHOLDERS = 32;
    int MAX_TOKEN_LENGTH = 64;
    Pattern POSITIONAL_TOKEN = Pattern.compile("^[1-9][0-9]?$");

    /**
     * The only schema Dokene can send to: text parameters in the body, declared in {@code format}. Holds the raw
     * placeholder tokens in order of appearance and the template's fixed text; {@link #toString()} omits both.
     */
    record BodyParameters(TemplateParameterFormat format, List<String> placeholders, TemplateContent content)
            implements ProviderTemplateSchema {
        public BodyParameters {
            Objects.requireNonNull(format, "Parameter format is required");
            Objects.requireNonNull(content, "Template content is required");
            placeholders = List.copyOf(Objects.requireNonNull(placeholders, "Placeholders are required"));
            if (placeholders.size() > MAX_PLACEHOLDERS) {
                throw new IllegalArgumentException("Too many placeholders");
            }
        }

        @Override
        public String toString() {
            return "BodyParameters[format=" + format + ", placeholders=" + placeholders.size() + "]";
        }
    }

    /** The template needs a component or parameter kind Dokene does not send (media, buttons with values, ...). */
    record Unsupported() implements ProviderTemplateSchema {
    }

    /** The component metadata is missing or malformed, so nothing can be concluded from it. */
    record Unreadable() implements ProviderTemplateSchema {
    }

    /** The outcome of a comparison: APPROVED carries the format to send with, and every other status carries none. */
    record Verdict(TemplateApprovalStatus status, TemplateParameterFormat format) {
        public Verdict {
            Objects.requireNonNull(status, "Status is required");
            if (status != TemplateApprovalStatus.APPROVED && !status.derivedFromSchema()) {
                throw new IllegalArgumentException("Status is not a schema verdict");
            }
            if ((status == TemplateApprovalStatus.APPROVED) != (format != null)) {
                throw new IllegalArgumentException("Only an approved verdict carries a parameter format");
            }
        }
    }

    /**
     * The double-brace placeholder tokens of {@code text}, in order. Empty when the braces are malformed: an unmatched
     * opening or closing pair, an empty token, a brace inside a token, or more or longer tokens than the bounds allow.
     */
    static Optional<List<String>> placeholders(String text) {
        Objects.requireNonNull(text, "Text is required");
        List<String> tokens = new ArrayList<>();
        int from = 0;
        while (true) {
            int open = text.indexOf("{{", from);
            int close = text.indexOf("}}", from);
            if (open < 0) {
                return close < 0 ? Optional.of(List.copyOf(tokens)) : Optional.empty();
            }
            if (close >= 0 && close < open) {
                return Optional.empty();
            }
            int end = text.indexOf("}}", open + 2);
            if (end < 0) {
                return Optional.empty();
            }
            String token = text.substring(open + 2, end);
            if (token.isEmpty() || token.length() > MAX_TOKEN_LENGTH || token.indexOf('{') >= 0
                    || token.indexOf('}') >= 0 || tokens.size() == MAX_PLACEHOLDERS) {
                return Optional.empty();
            }
            tokens.add(token);
            from = end + 2;
        }
    }

    /**
     * Positional placeholders must be exactly the indexes 1 to {@code n}, with {@code n} equal to the number of
     * names; named placeholders must be exactly the stored names. Repeated placeholders are not supported. A
     * template without placeholders is approved as {@code POSITIONAL} whatever it declares, since no parameter is
     * sent.
     */
    default Verdict check(List<String> parameterNames) {
        Objects.requireNonNull(parameterNames, "Parameter names are required");
        return switch (this) {
            case Unreadable unreadable -> new Verdict(TemplateApprovalStatus.SCHEMA_UNREADABLE, null);
            case Unsupported unsupported -> new Verdict(TemplateApprovalStatus.SCHEMA_UNSUPPORTED, null);
            case BodyParameters body -> {
                Set<String> distinct = new HashSet<>(body.placeholders());
                if (distinct.size() != body.placeholders().size()) {
                    yield new Verdict(TemplateApprovalStatus.SCHEMA_UNSUPPORTED, null);
                }
                boolean matches = switch (body.format()) {
                    case POSITIONAL -> positionalMatches(distinct, parameterNames.size());
                    // Stored names already satisfy TemplateMapping.PARAMETER_NAME, so set equality is the whole rule.
                    case NAMED -> distinct.equals(new HashSet<>(parameterNames));
                };
                // Without placeholders no parameter is sent, so the declared format cannot matter: record POSITIONAL.
                TemplateParameterFormat format = distinct.isEmpty() ? TemplateParameterFormat.POSITIONAL
                        : body.format();
                yield matches ? new Verdict(TemplateApprovalStatus.APPROVED, format)
                        : new Verdict(TemplateApprovalStatus.SCHEMA_MISMATCH, null);
            }
        };
    }

    private static boolean positionalMatches(Set<String> tokens, int expected) {
        if (tokens.size() != expected) {
            return false;
        }
        Set<Integer> indexes = new HashSet<>();
        for (String token : tokens) {
            if (!POSITIONAL_TOKEN.matcher(token).matches()) {
                return false;
            }
            indexes.add(Integer.parseInt(token));
        }
        for (int i = 1; i <= expected; i++) {
            if (!indexes.contains(i)) {
                return false;
            }
        }
        return true;
    }
}
