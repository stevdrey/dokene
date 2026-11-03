// ABOUTME: Checks Meta's X-Hub-Signature-256 over the exact request bytes with the deployment app secret (spec 0007).
// ABOUTME: prepare checks header grammar and reference before the body is read; verify then resolves and HMACs once.
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
    private final WebhookRejectionLog rejectionLog;

    /** An empty {@code appSecretRef} is the refusing state: every call refuses without touching the resolver. */
    public WebhookSignatureVerifier(IntegrationSecretResolver resolver, Optional<SecretReference> appSecretRef,
            WebhookTelemetry telemetry, WebhookRejectionLog rejectionLog) {
        this.resolver = Objects.requireNonNull(resolver, "Secret resolver is required");
        this.appSecretRef = Objects.requireNonNull(appSecretRef, "App secret reference is required");
        this.telemetry = Objects.requireNonNull(telemetry, "Telemetry is required");
        this.rejectionLog = Objects.requireNonNull(rejectionLog, "Rejection log is required");
    }

    /**
     * Verifies {@code body} against every {@code X-Hub-Signature-256} value the request carried, in one step. The
     * route uses {@link #prepare} instead so that a request failing the cheap checks is refused before it is read.
     */
    public VerifiedWebhookBody verify(byte[] body, List<String> signatureHeaderValues) {
        Objects.requireNonNull(body, "Body is required");
        SignatureCheck check = prepare(signatureHeaderValues);
        // Copied here, so a caller mutating its array after the call cannot change what was verified.
        return check.verify(body.clone());
    }

    /**
     * Checks everything that needs no body (issue 306): the header grammar of AC-2, then that an app secret reference
     * is configured. Neither step reads the body or calls the resolver.
     */
    public SignatureCheck prepare(List<String> signatureHeaderValues) {
        byte[] expected = decode(signatureHeaderValues);
        SecretReference reference = appSecretRef.orElseThrow(() -> refuse(Reason.SECRET_UNAVAILABLE));
        return new SignatureCheck(expected, reference);
    }

    /** A request whose header and reference passed; only {@link #verify(byte[])} can turn it into a verified body. */
    public final class SignatureCheck {
        private final byte[] expected;
        private final SecretReference reference;

        private SignatureCheck(byte[] expected, SecretReference reference) {
            this.expected = expected;
            this.reference = reference;
        }

        /**
         * One resolve and one HMAC over {@code body}, which this call takes ownership of: the array is hashed and
         * becomes the verified body without a copy (issue 306), so the caller must not touch it afterwards. The route
         * passes the array the reader just allocated and keeps no other reference to it.
         */
        public VerifiedWebhookBody verify(byte[] body) {
            Objects.requireNonNull(body, "Body is required");
            byte[] actual = hmac(reference, body);
            if (!MessageDigest.isEqual(actual, expected)) {
                throw refuse(Reason.SIGNATURE_MISMATCH);
            }
            return new VerifiedWebhookBody(body);
        }
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
        rejectionLog.rejected(log, reason);
        telemetry.rejected(reason);
        return new WebhookVerificationException(reason);
    }
}
