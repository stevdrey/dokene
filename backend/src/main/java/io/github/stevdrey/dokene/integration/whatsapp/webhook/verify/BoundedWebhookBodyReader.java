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

    /** {@code maxBodyBytes} is validated against the property's range by the configuration before it reaches here. */
    public BoundedWebhookBodyReader(int maxBodyBytes, WebhookTelemetry telemetry) {
        if (maxBodyBytes < 1) {
            throw new IllegalArgumentException("The body bound must be at least 1 byte");
        }
        this.maxBodyBytes = maxBodyBytes;
        this.telemetry = Objects.requireNonNull(telemetry, "Telemetry is required");
    }

    /**
     * Returns the whole body when it fits. A {@code declaredLength} above the bound refuses before a byte is read; a
     * negative one means unknown. {@code readNBytes(max + 1)} grows its buffer as it goes, so an oversized or
     * unbounded stream costs at most one byte past the bound and is then refused.
     */
    public byte[] read(InputStream body, long declaredLength) {
        Objects.requireNonNull(body, "Body stream is required");
        if (declaredLength > maxBodyBytes) {
            throw refuse(Reason.BODY_TOO_LARGE);
        }
        byte[] bytes;
        try {
            bytes = body.readNBytes(maxBodyBytes + 1);
        } catch (IOException transport) {
            throw new UncheckedIOException(UNREADABLE, transport);
        }
        if (bytes.length > maxBodyBytes) {
            throw refuse(Reason.BODY_TOO_LARGE);
        }
        return bytes;
    }

    private WebhookVerificationException refuse(Reason reason) {
        log.warn("Webhook rejected reason={}", reason);
        telemetry.rejected(reason);
        return new WebhookVerificationException(reason);
    }
}
