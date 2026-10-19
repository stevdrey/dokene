// ABOUTME: Checks Meta's X-Hub-Signature-256 over the exact request bytes with the deployment app secret (spec 0007).
// ABOUTME: Header grammar first, then the reference, then one resolve and one HMAC; the only maker of VerifiedWebhookBody.
package io.github.stevdrey.dokene.integration.whatsapp.webhook.verify;

import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.Deployment;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.ResolvedSecret;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.SecretPurpose;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.SecretReference;
import io.github.stevdrey.dokene.integration.application.SecretUnavailableException;
import io.github.stevdrey.dokene.integration.whatsapp.webhook.verify.WebhookVerificationException.Reason;
import java.security.MessageDigest;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WebhookSignatureVerifier {
    private static final Logger log = LoggerFactory.getLogger(WebhookSignatureVerifier.class);
    private static final String PREFIX = "sha256=";
    private static final int DIGEST_BYTES = 32;
    private static final String ALGORITHM = "HmacSHA256";

    private final IntegrationSecretResolver resolver;
    private final Optional<SecretReference> appSecretRef;
    private final WebhookTelemetry telemetry;

    /** An empty {@code appSecretRef} is the refusing state: every call refuses without touching the resolver. */
    public WebhookSignatureVerifier(IntegrationSecretResolver resolver, Optional<SecretReference> appSecretRef,
            WebhookTelemetry telemetry) {
        this.resolver = Objects.requireNonNull(resolver, "Secret resolver is required");
        this.appSecretRef = Objects.requireNonNull(appSecretRef, "App secret reference is required");
        this.telemetry = Objects.requireNonNull(telemetry, "Telemetry is required");
    }

    /**
     * Verifies {@code body} against every {@code X-Hub-Signature-256} value the request carried. The input array is
     * copied before anything is hashed and the copy becomes the verified body, so a caller mutating its array after
     * the call cannot change what was verified.
     */
    public VerifiedWebhookBody verify(byte[] body, List<String> signatureHeaderValues) {
        Objects.requireNonNull(body, "Body is required");
        byte[] expected = decode(signatureHeaderValues);
        SecretReference reference = appSecretRef.orElseThrow(() -> refuse(Reason.SECRET_UNAVAILABLE));
        byte[] copy = body.clone();
        byte[] actual = hmac(reference, copy);
        if (!MessageDigest.isEqual(actual, expected)) {
            throw refuse(Reason.SIGNATURE_MISMATCH);
        }
        return new VerifiedWebhookBody(copy);
    }

    private byte[] hmac(SecretReference reference, byte[] copy) {
        ResolvedSecret secret;
        try {
            secret = resolver.resolve(new Deployment(SecretPurpose.APP_SECRET), reference);
        } catch (SecretUnavailableException denied) {
            // Deliberately not chained: the refusal carries no scope or category outward.
            throw refuse(Reason.SECRET_UNAVAILABLE);
        }
        try (secret) {
            Mac mac = SecretKeyBytes.withUtf8Key(secret, key -> {
                Mac engine = Mac.getInstance(ALGORITHM);
                engine.init(new SecretKeySpec(key, ALGORITHM));
                return engine;
            });
            return mac.doFinal(copy);
        }
    }

    /** The header grammar of AC-2, checked before the reference and before any resolver call. */
    private byte[] decode(List<String> values) {
        if (values == null || values.isEmpty()) {
            throw refuse(Reason.MISSING_SIGNATURE);
        }
        if (values.size() != 1) {
            throw refuse(Reason.MALFORMED_SIGNATURE);
        }
        String value = values.getFirst();
        if (value == null || value.length() != PREFIX.length() + DIGEST_BYTES * 2 || !value.startsWith(PREFIX)) {
            throw refuse(Reason.MALFORMED_SIGNATURE);
        }
        byte[] decoded = new byte[DIGEST_BYTES];
        for (int index = 0; index < DIGEST_BYTES; index++) {
            int high = hex(value.charAt(PREFIX.length() + index * 2));
            int low = hex(value.charAt(PREFIX.length() + index * 2 + 1));
            if (high < 0 || low < 0) {
                throw refuse(Reason.MALFORMED_SIGNATURE);
            }
            decoded[index] = (byte) (high << 4 | low);
        }
        return decoded;
    }

    /** ASCII hex only, either case; {@code Character.digit} would admit other scripts' digits. */
    private static int hex(char character) {
        if (character >= '0' && character <= '9') {
            return character - '0';
        }
        if (character >= 'a' && character <= 'f') {
            return character - 'a' + 10;
        }
        if (character >= 'A' && character <= 'F') {
            return character - 'A' + 10;
        }
        return -1;
    }

    private WebhookVerificationException refuse(Reason reason) {
        log.warn("Webhook rejected reason={}", reason);
        telemetry.rejected(reason);
        return new WebhookVerificationException(reason);
    }
}
