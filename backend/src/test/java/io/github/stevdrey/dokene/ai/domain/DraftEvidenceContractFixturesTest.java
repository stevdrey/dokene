// ABOUTME: Provider side of the draft evidence display contract (issue 194): the labels the frontend formats.
// ABOUTME: A renamed label, a new alias for a formatted field or a renamed status fails here and in the frontend test.
package io.github.stevdrey.dokene.ai.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.stevdrey.dokene.followup.domain.FollowUpStatus;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class DraftEvidenceContractFixturesTest {
    private static final List<String> PURCHASE_DATES = List.of("2026-01-10T14:00:00Z");
    private static final String FOLLOW_UP_STATUS = "OVERDUE";
    private static final DraftGroundingContext GROUNDING = new DraftGroundingContext(
            "Valentina", "notas", List.of("Café molido"), PURCHASE_DATES, FOLLOW_UP_STATUS, "2026-10-17");

    private final JsonNode fixture = load();

    @Test
    void everyFormattedLabelResolvesToItsExactSource() {
        formattedLabels().forEach((label, kind) -> {
            DraftGroundingContext.EvidenceSource source = GROUNDING.sourceForLabel(label);
            assertThat(source).as(label).isNotNull();
            assertThat(source.mode()).as(label).isEqualTo(DraftGroundingContext.MatchMode.EXACT);
            assertThat(kindOf(source)).as(label).isEqualTo(kind);
        });
    }

    @Test
    void everyAdvertisedLabelForAFormattedSourceIsListed() {
        Map<String, String> advertised = new LinkedHashMap<>();
        Arrays.stream(DraftGroundingContext.ALLOWED_LABELS_DESCRIPTION.split(","))
                .map(label -> label.strip().toLowerCase(java.util.Locale.ROOT))
                .forEach(label -> {
                    String kind = kindOf(GROUNDING.sourceForLabel(label));
                    if (kind != null) {
                        advertised.put(label, kind);
                    }
                });
        assertThat(formattedLabels()).containsAllEntriesOf(advertised);
    }

    @Test
    void followUpStatusesAreDomainValues() {
        var statuses = fixture.get("followUpStatuses").values().stream().map(JsonNode::asString).toList();
        assertThat(statuses).isNotEmpty();
        assertThat(Arrays.stream(FollowUpStatus.values()).map(Enum::name)).containsAll(statuses);
    }

    private static String kindOf(DraftGroundingContext.EvidenceSource source) {
        if (source == null) {
            return null;
        }
        if (source.values().equals(PURCHASE_DATES)) {
            return "PURCHASE_DATE";
        }
        if (source.values().equals(List.of(FOLLOW_UP_STATUS))) {
            return "FOLLOW_UP_STATUS";
        }
        return null;
    }

    private Map<String, String> formattedLabels() {
        var result = new LinkedHashMap<String, String>();
        fixture.get("formattedLabels").properties().forEach(entry -> result.put(entry.getKey(), entry.getValue().asString()));
        return result;
    }

    private JsonNode load() {
        try (InputStream in = getClass().getResourceAsStream("/contracts/ai/v1/draft-evidence.json")) {
            assertThat(in).isNotNull();
            return new ObjectMapper().readTree(in);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read draft evidence fixture", exception);
        }
    }
}
