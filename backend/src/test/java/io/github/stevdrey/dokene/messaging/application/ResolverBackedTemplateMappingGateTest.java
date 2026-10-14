package io.github.stevdrey.dokene.messaging.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import io.github.stevdrey.dokene.template.application.TemplateMappingResolver;
import io.github.stevdrey.dokene.template.application.TemplateMappingResolver.ResolvedTemplateMapping;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResolverBackedTemplateMappingGateTest {
    private final TemplateMappingResolver resolver = mock(TemplateMappingResolver.class);
    private final ResolverBackedTemplateMappingGate gate = new ResolverBackedTemplateMappingGate(resolver);

    @Test
    void onlyAResolvedMappingIsMapped() {
        var intent = SemanticTemplateIntent.REPEAT_PURCHASE;
        when(resolver.resolve(intent)).thenReturn(new TemplateMappingResolver.Resolved(
                new ResolvedTemplateMapping(intent, "repeat_purchase_v1", "es", List.of("customer_name"), 0)));
        assertThat(gate.check(intent)).isEqualTo(TemplateMappingStatus.MAPPED);

        when(resolver.resolve(intent)).thenReturn(new TemplateMappingResolver.Disabled());
        assertThat(gate.check(intent)).isEqualTo(TemplateMappingStatus.NOT_MAPPED);
        when(resolver.resolve(intent)).thenReturn(new TemplateMappingResolver.NotMapped());
        assertThat(gate.check(intent)).isEqualTo(TemplateMappingStatus.NOT_MAPPED);
    }
}
