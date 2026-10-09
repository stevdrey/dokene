// ABOUTME: Unit tests for the template gate stand in: nothing is mapped unless the property lists the intent.
// ABOUTME: Covers AC-12 of spec 0001 at the unit level.
package io.github.stevdrey.dokene.messaging.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class StubTemplateMappingGateTest {

    @ParameterizedTest
    @EnumSource(SemanticTemplateIntent.class)
    void nothingIsMappedWhenThePropertyIsAbsentOrEmpty(SemanticTemplateIntent intent) {
        assertThat(new StubTemplateMappingGate(new MessagingTemplateGateStubProperties(null)).check(intent))
                .isEqualTo(TemplateMappingStatus.NOT_MAPPED);
        assertThat(new StubTemplateMappingGate(new MessagingTemplateGateStubProperties(Set.of())).check(intent))
                .isEqualTo(TemplateMappingStatus.NOT_MAPPED);
    }

    @Test
    void onlyListedIntentsAreMapped() {
        StubTemplateMappingGate gate = new StubTemplateMappingGate(new MessagingTemplateGateStubProperties(
                Set.of(SemanticTemplateIntent.GENERAL_FOLLOW_UP, SemanticTemplateIntent.SEASONAL_EVENT)));

        assertThat(gate.check(SemanticTemplateIntent.GENERAL_FOLLOW_UP)).isEqualTo(TemplateMappingStatus.MAPPED);
        assertThat(gate.check(SemanticTemplateIntent.SEASONAL_EVENT)).isEqualTo(TemplateMappingStatus.MAPPED);
        assertThat(gate.check(SemanticTemplateIntent.REPEAT_PURCHASE)).isEqualTo(TemplateMappingStatus.NOT_MAPPED);
    }
}
