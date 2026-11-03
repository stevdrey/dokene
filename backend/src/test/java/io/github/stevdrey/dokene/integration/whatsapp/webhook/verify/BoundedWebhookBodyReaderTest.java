// ABOUTME: Proves spec 0007 AC-3 and AC-7 for the bounded reader with a counting stream that reports every byte read.
// ABOUTME: Covers the early refusal, the unknown length read past the bound, the exact bound, and the transport wrap.
package io.github.stevdrey.dokene.integration.whatsapp.webhook.verify;

import static io.github.stevdrey.dokene.integration.testsupport.SecretCanaries.containsAny;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.stevdrey.dokene.integration.testsupport.LogCapture;
import io.github.stevdrey.dokene.integration.testsupport.SecretCanaries;
import io.github.stevdrey.dokene.integration.whatsapp.webhook.verify.WebhookVerificationException.Reason;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BoundedWebhookBodyReaderTest {
    private static final int BOUND = 16;

    private final List<Reason> rejected = new ArrayList<>();
    private final BoundedWebhookBodyReader reader = new BoundedWebhookBodyReader(BOUND, rejected::add, freshLog());
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
    void aDeclaredLengthOverTheBoundIsRefusedBeforeAnyByteIsRead() {
        CountingStream stream = new CountingStream(new byte[BOUND + 1]);

        assertThat(reasonOf(() -> reader.read(stream, BOUND + 1L))).isEqualTo(Reason.BODY_TOO_LARGE);

        assertThat(stream.bytesRead).isZero();
        assertThat(stream.closed).isFalse();
        assertThat(rejected).containsExactly(Reason.BODY_TOO_LARGE);
        assertThat(logs.warningsFrom(BoundedWebhookBodyReader.class)).hasSize(1);
    }

    @Test
    void anUnknownLengthStreamIsRefusedOnceItPassesTheBound() {
        CountingStream stream = new CountingStream(new byte[BOUND * 4]);

        assertThat(reasonOf(() -> reader.read(stream, -1))).isEqualTo(Reason.BODY_TOO_LARGE);

        assertThat(stream.bytesRead).isEqualTo(BOUND + 1);
        assertThat(stream.closed).isFalse();
    }

    @Test
    void aLyingDeclaredLengthCostsAtMostOneBytePastWhatItDeclared() {
        CountingStream stream = new CountingStream(new byte[BOUND * 4]);

        assertThat(reasonOf(() -> reader.read(stream, 1))).isEqualTo(Reason.BODY_TOO_LARGE);

        assertThat(stream.bytesRead).isEqualTo(2);
    }

    /** covers: issue 306, a known length is read into one array of that size; a shorter stream is returned as is. */
    @Test
    void aKnownLengthIsReadIntoAnArrayOfThatSize() {
        CountingStream shorter = new CountingStream(new byte[4]);

        assertThat(reader.read(new CountingStream(new byte[BOUND - 1]), BOUND - 1)).hasSize(BOUND - 1);
        assertThat(reader.read(shorter, 8)).hasSize(4);
        assertThat(rejected).isEmpty();
    }

    @Test
    void aBodyOfExactlyTheBoundAndAnEmptyBodyAreReturnedWhole() {
        byte[] exact = new byte[BOUND];
        for (int index = 0; index < exact.length; index++) {
            exact[index] = (byte) index;
        }
        CountingStream exactStream = new CountingStream(exact);
        CountingStream emptyStream = new CountingStream(new byte[0]);

        assertThat(reader.read(exactStream, BOUND)).isEqualTo(exact);
        assertThat(reader.read(emptyStream, 0)).isEmpty();
        assertThat(reader.read(new CountingStream(exact), -1)).isEqualTo(exact);

        assertThat(exactStream.closed).isFalse();
        assertThat(emptyStream.closed).isFalse();
        assertThat(rejected).isEmpty();
        assertThat(logs.warningsFrom(BoundedWebhookBodyReader.class)).isEmpty();
    }

    @Test
    void aTransportFailureBecomesUncheckedIoExceptionWithTheFixedMessage() {
        IOException transport = new IOException("connection reset by peer 10.0.0.1");
        InputStream failing = new InputStream() {
            @Override
            public int read() throws IOException {
                throw transport;
            }
        };

        assertThatThrownBy(() -> reader.read(failing, -1))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessage("Webhook body could not be read")
                .hasCause(transport);
        assertThat(rejected).isEmpty();
        assertThat(logs.text()).doesNotContain("10.0.0.1");
    }

    /** covers: AC-8, a body canary never reaches the log, whether the body is returned or refused as too large. */
    @Test
    void aBodyCanaryNeverReachesTheLogOnSuccessOrRefusal() {
        String canary = SecretCanaries.value();
        byte[] fitting = canary.substring(0, BOUND).getBytes(StandardCharsets.US_ASCII);
        byte[] oversized = (canary + canary).getBytes(StandardCharsets.US_ASCII);

        assertThat(reader.read(new CountingStream(fitting), fitting.length)).isEqualTo(fitting);
        assertThat(reasonOf(() -> reader.read(new CountingStream(oversized), -1))).isEqualTo(Reason.BODY_TOO_LARGE);
        assertThat(reasonOf(() -> reader.read(new CountingStream(oversized), oversized.length)))
                .isEqualTo(Reason.BODY_TOO_LARGE);

        assertThat(rejected).containsExactly(Reason.BODY_TOO_LARGE, Reason.BODY_TOO_LARGE);
        assertThat(logs.warningsFrom(BoundedWebhookBodyReader.class)).hasSize(1);
        assertThat(containsAny(logs.text(), List.of(canary, canary.substring(0, BOUND)))).isFalse();
    }

    @Test
    void theDeclaredLengthCheckAloneRefusesOnlyALengthOverTheBound() {
        reader.checkDeclaredLength(-1);
        reader.checkDeclaredLength(0);
        reader.checkDeclaredLength(BOUND);
        assertThat(rejected).isEmpty();

        assertThat(reasonOf(() -> reader.checkDeclaredLength(BOUND + 1L))).isEqualTo(Reason.BODY_TOO_LARGE);
        assertThat(reasonOf(() -> reader.checkDeclaredLength(Long.MAX_VALUE))).isEqualTo(Reason.BODY_TOO_LARGE);
        assertThat(rejected).containsExactly(Reason.BODY_TOO_LARGE, Reason.BODY_TOO_LARGE);
    }

    @Test
    void theBoundMustBeAtLeastOneByte() {
        assertThatThrownBy(() -> new BoundedWebhookBodyReader(0, rejected::add, freshLog()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** A new throttle per component, so each refusal reason logs its first line in every test. */
    private static WebhookRejectionLog freshLog() {
        return new WebhookRejectionLog(Clock.systemUTC());
    }

    private static Reason reasonOf(Runnable call) {
        return catchThrowableOfType(WebhookVerificationException.class, call::run).reason();
    }

    private static final class CountingStream extends ByteArrayInputStream {
        private int bytesRead;
        private boolean closed;

        private CountingStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public int read() {
            int value = super.read();
            if (value >= 0) {
                bytesRead++;
            }
            return value;
        }

        @Override
        public int read(byte[] target, int offset, int length) {
            int count = super.read(target, offset, length);
            if (count > 0) {
                bytesRead += count;
            }
            return count;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
