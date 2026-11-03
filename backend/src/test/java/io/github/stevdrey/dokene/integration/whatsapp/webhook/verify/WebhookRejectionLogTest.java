// ABOUTME: Proves the webhook rejection log throttle of issue 306: one line per message per interval, the held-back
// ABOUTME: count on the next line, independent messages, and the refusing class's logger kept on every line.
package io.github.stevdrey.dokene.integration.whatsapp.webhook.verify;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.spi.ILoggingEvent;
import io.github.stevdrey.dokene.integration.testsupport.LogCapture;
import io.github.stevdrey.dokene.integration.whatsapp.webhook.verify.WebhookVerificationException.Reason;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class WebhookRejectionLogTest {
    private static final Logger log = LoggerFactory.getLogger(WebhookRejectionLogTest.class);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-03T10:00:00Z"));
    private final WebhookRejectionLog rejectionLog = new WebhookRejectionLog(clock);
    private LogCapture logs;

    @BeforeEach
    void setUp() {
        logs = new LogCapture();
    }

    @AfterEach
    void tearDown() {
        logs.close();
    }

    @Test
    void aFloodOfOneReasonWritesOneLineThenReportsWhatItHeldBack() {
        for (int index = 0; index < 1000; index++) {
            rejectionLog.rejected(log, Reason.SIGNATURE_MISMATCH);
        }
        assertThat(lines()).containsExactly("Webhook rejected reason=SIGNATURE_MISMATCH");

        clock.advance(WebhookRejectionLog.INTERVAL.minusSeconds(1));
        rejectionLog.rejected(log, Reason.SIGNATURE_MISMATCH);
        assertThat(lines()).hasSize(1);

        clock.advance(Duration.ofSeconds(1));
        rejectionLog.rejected(log, Reason.SIGNATURE_MISMATCH);
        assertThat(lines()).containsExactly("Webhook rejected reason=SIGNATURE_MISMATCH",
                "Webhook rejected reason=SIGNATURE_MISMATCH suppressed=1000");

        clock.advance(WebhookRejectionLog.INTERVAL);
        rejectionLog.rejected(log, Reason.SIGNATURE_MISMATCH);
        assertThat(lines()).last().isEqualTo("Webhook rejected reason=SIGNATURE_MISMATCH");
    }

    @Test
    void eachMessageIsThrottledOnItsOwn() {
        for (int index = 0; index < 3; index++) {
            rejectionLog.rejected(log, Reason.MISSING_SIGNATURE);
            rejectionLog.rejected(log, Reason.BODY_TOO_LARGE);
            rejectionLog.warn(log, "Webhook body could not be read");
        }

        assertThat(lines()).containsExactly("Webhook rejected reason=MISSING_SIGNATURE",
                "Webhook rejected reason=BODY_TOO_LARGE", "Webhook body could not be read");
    }

    private List<String> lines() {
        return logs.warningsFrom(WebhookRejectionLogTest.class).stream().map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        MutableClock(Instant start) {
            this.current = start;
        }

        void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
