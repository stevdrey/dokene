// ABOUTME: Pins a digest of every rule DraftSafetyValidator applies, so a rule change cannot ship without a new
// ABOUTME: FIXED_TEXT_RULES_VERSION (and the reset of approved template evidence that goes with it, V32).
package io.github.stevdrey.dokene.ai.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class FixedTextRulesVersionTest {
    /**
     * The digest of the rules each version was released with. Template approvals record the version of the rules that
     * checked their fixed text and are trusted only under that same version (ADR 0025, issue 268).
     */
    private static final Map<Integer, String> RELEASED = Map.of(
            1, "d292ca8d90d75c15062557a5cd868705e29d33811a3392c706ec626dd65a1e14");

    @Test
    void theRulesVersionChangesWhenTheRulesDo() throws Exception {
        assertThat(rulesDigest())
                .as("""
                        DraftSafetyValidator's rules changed. Content checked under the old rules must not stay trusted:
                        bump FIXED_TEXT_RULES_VERSION, add a migration that returns APPROVED template evidence to
                        UNVERIFIED (as V32 does), and pin the new digest here under the new version.""")
                .isEqualTo(RELEASED.get(DraftSafetyValidator.FIXED_TEXT_RULES_VERSION));
    }

    /** Every static final field of the validator and its nested classes, by name, except the version itself. */
    private static String rulesDigest() throws Exception {
        Map<String, String> rules = new TreeMap<>();
        List<Class<?>> classes = new ArrayList<>(List.of(DraftSafetyValidator.class));
        classes.addAll(Arrays.asList(DraftSafetyValidator.class.getDeclaredClasses()));
        for (Class<?> type : classes) {
            for (Field field : type.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (!Modifier.isStatic(modifiers) || !Modifier.isFinal(modifiers)
                        || field.getName().equals("FIXED_TEXT_RULES_VERSION")) {
                    continue;
                }
                field.setAccessible(true);
                rules.put(type.getSimpleName() + "." + field.getName(), describe(field.get(null)));
            }
        }
        String canonical = rules.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("\n"));
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    /** A stable text for a rule value: patterns by source and flags, collections and maps in a fixed order. */
    private static String describe(Object value) {
        if (value instanceof Pattern pattern) {
            return "/" + pattern.pattern() + "/" + pattern.flags();
        }
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream().map(entry -> describe(entry.getKey()) + ":" + describe(entry.getValue()))
                    .sorted().collect(Collectors.joining(",", "{", "}"));
        }
        if (value instanceof List<?> list) {
            return list.stream().map(FixedTextRulesVersionTest::describe).collect(Collectors.joining(",", "[", "]"));
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(FixedTextRulesVersionTest::describe).sorted(Comparator.naturalOrder())
                    .collect(Collectors.joining(",", "{", "}"));
        }
        if (value != null && value.getClass().isRecord()) {
            return Arrays.stream(value.getClass().getRecordComponents()).map(component -> {
                try {
                    return describe(component.getAccessor().invoke(value));
                } catch (ReflectiveOperationException exception) {
                    throw new IllegalStateException(exception);
                }
            }).collect(Collectors.joining(",", value.getClass().getSimpleName() + "(", ")"));
        }
        if (value instanceof java.util.Optional<?> optional) {
            return optional.map(FixedTextRulesVersionTest::describe).orElse("none");
        }
        return String.valueOf(value);
    }
}
