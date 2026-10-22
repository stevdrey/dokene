// ABOUTME: Pins the template module's view of a fixed-text template to the messaging rules it mirrors (issue 268).
// ABOUTME: template may not depend on messaging, so this test keeps refresh-time and submit-time limits aligned.
package io.github.stevdrey.dokene.messaging.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.stevdrey.dokene.ai.domain.MessageDraft;
import io.github.stevdrey.dokene.messaging.domain.OutboundMessage;
import io.github.stevdrey.dokene.template.domain.TemplateMapping;
import org.junit.jupiter.api.Test;

class FixedTemplateBodyParityTest {
    @Test
    void theRefreshUsesTheBodyParameterNameAndLimitThatSubmitEnforces() {
        assertThat(TemplateMapping.MESSAGE_BODY_PARAMETER)
                .isEqualTo(ApprovalBindingAssembler.TemplateParameter.MESSAGE_BODY.parameterName());
        assertThat(MessageDraft.MAX_BODY_LENGTH).isEqualTo(OutboundMessage.MAX_BODY_LENGTH);
    }
}
