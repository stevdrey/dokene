// ABOUTME: Pins the paths the error-dispatch filter treats as Phase 3 routes (issue 293): every message, messaging,
// ABOUTME: template and integration route plus the submit route under a customer, and nothing from Phase 1 or 2.
package io.github.stevdrey.dokene.messaging.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class Phase3ErrorPageFilterTest {
    @Test
    void phase3RoutesAreMatched() {
        assertThat(Phase3ErrorPageFilter.isPhase3Path("/api/messages")).isTrue();
        assertThat(Phase3ErrorPageFilter.isPhase3Path("/api/messages/7a5c0000-0000-4000-8000-000000000001/sends"))
                .isTrue();
        assertThat(Phase3ErrorPageFilter.isPhase3Path("/api/messaging/locales")).isTrue();
        assertThat(Phase3ErrorPageFilter.isPhase3Path("/api/templates/GENERAL_FOLLOW_UP/approval-refresh")).isTrue();
        assertThat(Phase3ErrorPageFilter.isPhase3Path("/api/integrations/whatsapp/outbound")).isTrue();
        assertThat(Phase3ErrorPageFilter.isPhase3Path("/api/customers/7a5c0000-0000-4000-8000-0000000c0001/messages"))
                .isTrue();
    }

    @Test
    void otherRoutesAreNot() {
        assertThat(Phase3ErrorPageFilter.isPhase3Path("/api/customers")).isFalse();
        assertThat(Phase3ErrorPageFilter.isPhase3Path("/api/customers/7a5c0000-0000-4000-8000-0000000c0001")).isFalse();
        assertThat(Phase3ErrorPageFilter.isPhase3Path("/api/customers/x/purchases")).isFalse();
        assertThat(Phase3ErrorPageFilter.isPhase3Path("/api/follow-up-queue")).isFalse();
        assertThat(Phase3ErrorPageFilter.isPhase3Path("/webhooks/whatsapp")).isFalse();
        assertThat(Phase3ErrorPageFilter.isPhase3Path("/api/messagesx")).isFalse();
    }
}
