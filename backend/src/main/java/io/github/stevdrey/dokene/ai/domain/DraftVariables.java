package io.github.stevdrey.dokene.ai.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable, bounded container of draft message template variables.
 * Enforces strict limits: at most 20 variables, valid identifier keys, and bounded value lengths.
 */
public record DraftVariables(@JsonValue List<DraftVariableEntry> entries) {
    public static final int MAX_ENTRIES = 20;
    private static final DraftVariables EMPTY = new DraftVariables(List.of());

    public DraftVariables {
        if (entries == null) {
            entries = List.of();
        } else {
            if (entries.size() > MAX_ENTRIES) {
                throw new RecommendationValidationException("draftVariables",
                        "Cannot exceed " + MAX_ENTRIES + " draft variables, got: " + entries.size());
            }
            Set<String> seenKeys = new HashSet<>();
            for (DraftVariableEntry entry : entries) {
                if (entry == null) {
                    throw new RecommendationValidationException("draftVariables", "Draft variable entry cannot be null");
                }
                if (!seenKeys.add(entry.key())) {
                    throw new RecommendationValidationException("draftVariables", "Duplicate variable key: " + entry.key());
                }
            }
            entries = List.copyOf(entries);
        }
    }

    public static DraftVariables empty() {
        return EMPTY;
    }

    @JsonCreator
    public static DraftVariables ofEntries(List<DraftVariableEntry> entries) {
        return new DraftVariables(entries);
    }

    public static DraftVariables of(Map<String, String> variables) {
        if (variables == null || variables.isEmpty()) {
            return EMPTY;
        }
        List<DraftVariableEntry> list = new ArrayList<>(variables.size());
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            list.add(new DraftVariableEntry(entry.getKey(), entry.getValue()));
        }
        return new DraftVariables(list);
    }

    public Map<String, String> asMap() {
        Map<String, String> map = new LinkedHashMap<>();
        for (DraftVariableEntry entry : entries) {
            map.put(entry.key(), entry.value());
        }
        return Collections.unmodifiableMap(map);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }
}
