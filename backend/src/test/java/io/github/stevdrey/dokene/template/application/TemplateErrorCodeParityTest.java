// ABOUTME: Pins the template-owned error codes to the messaging contract's codes and statuses (ADR 0025).
// ABOUTME: template may not depend on messaging, so this test is what keeps the shared problem+json aligned.
package io.github.stevdrey.dokene.template.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.stevdrey.dokene.messaging.api.MessagingApiContract.MessagingProblem;
import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.template.api.TemplateApiContract.TemplateProblem;
import org.junit.jupiter.api.Test;

class TemplateErrorCodeParityTest {
    @Test
    void everyTemplateCodeIsAMessagingCodeWithTheSameStatusAndProblemBody() {
        for (TemplateErrorCode code : TemplateErrorCode.values()) {
            MessagingErrorCode shared = MessagingErrorCode.valueOf(code.name());
            MessagingProblem expected = MessagingProblem.of(shared);
            TemplateProblem actual = TemplateProblem.of(code);
            assertThat(actual.status()).isEqualTo(expected.status());
            assertThat(actual.title()).isEqualTo(expected.title());
            assertThat(actual.type()).isEqualTo(expected.type());
            assertThat(actual.code().name()).isEqualTo(expected.code().name());
        }
    }
}
