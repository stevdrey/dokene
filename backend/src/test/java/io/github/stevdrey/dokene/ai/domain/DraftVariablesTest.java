package io.github.stevdrey.dokene.ai.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DraftVariablesTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void emptyDraftVariablesBehavesCorrectly() {
        DraftVariables empty = DraftVariables.empty();
        assertThat(empty.isEmpty()).isTrue();
        assertThat(empty.size()).isZero();
        assertThat(empty.entries()).isEmpty();
        assertThat(empty.asMap()).isEmpty();
    }

    @Test
    void createsFromMapAndConvertsBackPreservingOrder() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("productName", "Espresso Blend");
        map.put("discountCode", "WELCOME10");

        DraftVariables vars = DraftVariables.of(map);
        assertThat(vars.isEmpty()).isFalse();
        assertThat(vars.size()).isEqualTo(2);
        assertThat(vars.asMap()).containsExactlyEntriesOf(map);
    }

    @Test
    void enforcesMaxEntriesLimit() {
        List<DraftVariableEntry> entries = new ArrayList<>();
        for (int i = 0; i < DraftVariables.MAX_ENTRIES; i++) {
            entries.add(new DraftVariableEntry("key_" + i, "val_" + i));
        }
        DraftVariables valid = DraftVariables.ofEntries(entries);
        assertThat(valid.size()).isEqualTo(DraftVariables.MAX_ENTRIES);

        entries.add(new DraftVariableEntry("key_20", "val_20"));
        assertThatThrownBy(() -> DraftVariables.ofEntries(entries))
                .isInstanceOf(RecommendationValidationException.class)
                .hasMessageContaining("Cannot exceed " + DraftVariables.MAX_ENTRIES + " draft variables");

        // Verify that entries with duplicate keys are capped before deduplication
        List<DraftVariableEntry> duplicateEntries = new ArrayList<>();
        for (int i = 0; i <= DraftVariables.MAX_ENTRIES; i++) {
            duplicateEntries.add(new DraftVariableEntry("sameKey", "val_" + i));
        }
        assertThatThrownBy(() -> DraftVariables.ofEntries(duplicateEntries))
                .isInstanceOf(RecommendationValidationException.class)
                .hasMessageContaining("Cannot exceed " + DraftVariables.MAX_ENTRIES + " draft variables");
    }

    @Test
    void deduplicatesDuplicateKeysWithLastWriteWins() {
        List<DraftVariableEntry> entries = List.of(
                new DraftVariableEntry("customer", "Alice"),
                new DraftVariableEntry("item", "Coffee"),
                new DraftVariableEntry("item", "Tea")
        );
        DraftVariables vars = DraftVariables.ofEntries(entries);
        assertThat(vars.size()).isEqualTo(2);
        assertThat(vars.asMap()).containsExactly(
                Map.entry("customer", "Alice"),
                Map.entry("item", "Tea")
        );
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "key with space", " customer ", "customer ", " customer", "item-name", "item.name", "item$1", "!invalid"})
    void rejectsInvalidKeys(String invalidKey) {
        assertThatThrownBy(() -> new DraftVariableEntry(invalidKey, "validValue"))
                .isInstanceOf(RecommendationValidationException.class)
                .hasMessageContaining("Variable key");
    }

    @Test
    void rejectsOversizedKey() {
        String longKey = "a".repeat(DraftVariableEntry.MAX_KEY_LENGTH + 1);
        assertThatThrownBy(() -> new DraftVariableEntry(longKey, "val"))
                .isInstanceOf(RecommendationValidationException.class)
                .hasMessageContaining("Variable key");
    }

    @Test
    void rejectsNullValue() {
        assertThatThrownBy(() -> new DraftVariableEntry("validKey", null))
                .isInstanceOf(RecommendationValidationException.class)
                .hasMessageContaining("Variable value is required");
    }

    @Test
    void rejectsOversizedValue() {
        String longValue = "x".repeat(DraftVariableEntry.MAX_VALUE_LENGTH + 1);
        assertThatThrownBy(() -> new DraftVariableEntry("validKey", longValue))
                .isInstanceOf(RecommendationValidationException.class)
                .hasMessageContaining("exceeds maximum length of " + DraftVariableEntry.MAX_VALUE_LENGTH);
    }

    @Test
    void acceptsValueWithSupplementaryUnicodeUpToMaxLength() {
        // Emoji \uD83D\uDE00 has 1 code point but 2 UTF-16 code units (length = 1000)
        String maxEmojis = "\uD83D\uDE00".repeat(DraftVariableEntry.MAX_VALUE_LENGTH);
        DraftVariableEntry entry = new DraftVariableEntry("emojiKey", maxEmojis);
        assertThat(entry.value()).isEqualTo(maxEmojis);

        // 501 emojis exceed MAX_VALUE_LENGTH code points
        String oversizedEmojis = "\uD83D\uDE00".repeat(DraftVariableEntry.MAX_VALUE_LENGTH + 1);
        assertThatThrownBy(() -> new DraftVariableEntry("emojiKey", oversizedEmojis))
                .isInstanceOf(RecommendationValidationException.class)
                .hasMessageContaining("exceeds maximum length of " + DraftVariableEntry.MAX_VALUE_LENGTH);
    }

    @Test
    void serializesAndDeserializesWithJackson() throws Exception {
        DraftVariables vars = DraftVariables.of(Map.of("item", "Beans", "qty", "2"));
        String json = objectMapper.writeValueAsString(vars);

        DraftVariables deserialized = objectMapper.readValue(json, DraftVariables.class);
        assertThat(deserialized.asMap()).containsExactlyInAnyOrderEntriesOf(vars.asMap());
    }
}
