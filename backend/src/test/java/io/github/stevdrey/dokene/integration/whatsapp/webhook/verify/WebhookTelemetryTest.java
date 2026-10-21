// ABOUTME: Proves spec 0007 AC-7 and AC-8 (refusal shape and counter) and the issue 139 dropped event counter.
// ABOUTME: The no op telemetry is exercised too, so a context without a registry never fails a refusal.
package io.github.stevdrey.dokene.integration.whatsapp.webhook.verify;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.stevdrey.dokene.integration.whatsapp.webhook.verify.WebhookVerificationException.Reason;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class WebhookTelemetryTest {
    @Test
    void theReasonSetIsClosedAndEveryRefusalHasTheFixedShape() {
        assertThat(Reason.values()).containsExactly(Reason.MISSING_SIGNATURE, Reason.MALFORMED_SIGNATURE,
                Reason.SIGNATURE_MISMATCH, Reason.BODY_TOO_LARGE, Reason.SECRET_UNAVAILABLE, Reason.WRONG_MODE,
                Reason.INVALID_CHALLENGE, Reason.TOKEN_MISMATCH);

        for (Reason reason : Reason.values()) {
            WebhookVerificationException refusal = new WebhookVerificationException(reason);

            assertThat(refusal.reason()).isSameAs(reason);
            assertThat(refusal.getCause()).isNull();
            assertThat(refusal.getMessage()).isEqualTo("Webhook rejected: reason=" + reason.name());
            assertThat(refusal.toString()).isEqualTo(WebhookVerificationException.class.getName()
                    + ": Webhook rejected: reason=" + reason.name());
            assertThat(refusal.getStackTrace()).isNotEmpty();
        }
    }

    @Test
    void theMicrometerCounterIncrementsOncePerRefusalUnderTheReasonTag() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerWebhookTelemetry telemetry = new MicrometerWebhookTelemetry(registry);

        telemetry.rejected(Reason.SIGNATURE_MISMATCH);
        telemetry.rejected(Reason.SIGNATURE_MISMATCH);
        telemetry.rejected(Reason.BODY_TOO_LARGE);

        assertThat(registry.get("dokene.webhook.rejected").tag("reason", "SIGNATURE_MISMATCH").counter().count())
                .isEqualTo(2.0);
        assertThat(registry.get("dokene.webhook.rejected").tag("reason", "BODY_TOO_LARGE").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.find("dokene.webhook.rejected").tag("reason", "WRONG_MODE").counter()).isNull();
    }

    @Test
    void theDroppedCounterIsSeparateFromRefusalsAndTaggedOnlyWithTheReason() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerWebhookTelemetry telemetry = new MicrometerWebhookTelemetry(registry);

        telemetry.dropped(WebhookTelemetry.DropReason.DUPLICATE);
        telemetry.dropped(WebhookTelemetry.DropReason.UNKNOWN_PHONE_NUMBER);
        telemetry.dropped(WebhookTelemetry.DropReason.DUPLICATE);

        assertThat(registry.get("dokene.webhook.dropped").tag("reason", "DUPLICATE").counter().count())
                .isEqualTo(2.0);
        assertThat(registry.get("dokene.webhook.dropped").tag("reason", "UNKNOWN_PHONE_NUMBER").counter()
                .getId().getTags()).hasSize(1);
        assertThat(registry.find("dokene.webhook.rejected").counter()).isNull();
        new NoOpWebhookTelemetry().dropped(WebhookTelemetry.DropReason.UNMATCHED_MESSAGE);
    }

    @Test
    void theNoOpTelemetryAcceptsEveryReason() {
        NoOpWebhookTelemetry telemetry = new NoOpWebhookTelemetry();

        for (Reason reason : Reason.values()) {
            telemetry.rejected(reason);
        }
    }
}
