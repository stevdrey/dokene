// ABOUTME: Reads a webhook request body up to a configured bound and refuses anything larger (spec 0007 AC-3).
// ABOUTME: Trusts Content-Length only to refuse early, never to accept, and leaves the stream for the container to close.
package io.github.stevdrey.dokene.integration.whatsapp.webhook.verify;

import io.github.stevdrey.dokene.integration.whatsapp.webhook.verify.WebhookVerificationException.Reason;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BoundedWebhookBodyReader {
    private static final Logger log = LoggerFactory.getLogger(BoundedWebhookBodyReader.class);
    static final String UNREADABLE = "Webhook body could not be read";

    private final int maxBodyBytes;
    private final WebhookTelemetry telemetry;
    private final WebhookRejectionLog rejectionLog;

    /** {@code maxBodyBytes} is validated against the property's range by the configuration before it reaches here. */
    public BoundedWebhookBodyReader(int maxBodyBytes, WebhookTelemetry telemetry, WebhookRejectionLog rejectionLog) {
        if (maxBodyBytes < 1) {
            throw new IllegalArgumentException("The body bound must be at least 1 byte");
        }
        this.maxBodyBytes = maxBodyBytes;
        this.telemetry = Objects.requireNonNull(telemetry, "Telemetry is required");
        this.rejectionLog = Objects.requireNonNull(rejectionLog, "Rejection log is required");
    }

    /**
     * Refuses a {@code declaredLength} above the bound without touching the stream, so the route can refuse before it
     * admits the request (issue 306). A negative length means unknown and passes; {@link #read} still bounds it.
     */
    public void checkDeclaredLength(long declaredLength) {
        if (declaredLength > maxBodyBytes) {
            throw refuse(Reason.BODY_TOO_LARGE);
        }
    }

    /**
     * Returns the whole body when it fits. This is the standalone entry point, so it checks the declared length itself
     * even when the route already did before admission: it sizes its buffer from that length. A known length is read
     * into one array of exactly that size, and one more byte refuses the body. A negative length means unknown, and
     * {@code readNBytes(max + 1)} grows its buffer as it goes. Either way a stream longer than it claims, or than the
     * bound, costs at most one byte past the smaller of the two and is then refused.
     */
    public byte[] read(InputStream body, long declaredLength) {
        Objects.requireNonNull(body, "Body stream is required");
        checkDeclaredLength(declaredLength);
        byte[] bytes;
        boolean overflow;
        try {
            if (declaredLength >= 0) {
                bytes = body.readNBytes((int) declaredLength);
                overflow = body.read() != -1;
            } else {
                bytes = body.readNBytes(maxBodyBytes + 1);
                overflow = bytes.length > maxBodyBytes;
            }
        } catch (IOException transport) {
            throw new UncheckedIOException(UNREADABLE, transport);
        }
        if (overflow) {
            throw refuse(Reason.BODY_TOO_LARGE);
        }
        return bytes;
    }

    private WebhookVerificationException refuse(Reason reason) {
        rejectionLog.rejected(log, reason);
        telemetry.rejected(reason);
        return new WebhookVerificationException(reason);
    }
}
