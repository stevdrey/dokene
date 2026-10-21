// ABOUTME: The bytes a Meta signature was verified over; only WebhookSignatureVerifier can construct one (spec 0007).
// ABOUTME: Owns its array and hands out copies, so the parser downstream can never receive unverified bytes.
package io.github.stevdrey.dokene.integration.whatsapp.webhook.verify;

import java.util.Objects;

public final class VerifiedWebhookBody {
    private final byte[] bytes;

    /** Takes ownership of {@code bytes}: the verifier passes the copy it hashed and never touches it again. */
    VerifiedWebhookBody(byte[] bytes) {
        this.bytes = Objects.requireNonNull(bytes, "Body bytes are required");
    }

    public int length() {
        return bytes.length;
    }

    /** Exactly the bytes that were hashed, as a fresh array the caller may keep or mutate. */
    public byte[] copy() {
        return bytes.clone();
    }

    @Override
    public String toString() {
        return "VerifiedWebhookBody[" + bytes.length + " bytes]";
    }
}
