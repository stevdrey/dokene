// ABOUTME: Answers Meta's subscription handshake only when the mode and the deployment verify token match (spec 0007).
// ABOUTME: Compares SHA-256 digests of both tokens with MessageDigest.isEqual, so the length of the token never leaks.
package io.github.stevdrey.dokene.integration.whatsapp.webhook.verify;

import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.Deployment;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.ResolvedSecret;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.SecretPurpose;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.SecretReference;
import io.github.stevdrey.dokene.integration.application.SecretUnavailableException;
import io.github.stevdrey.dokene.integration.whatsapp.webhook.verify.WebhookVerificationException.Reason;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WebhookChallengeVerifier {
    private static final Logger log = LoggerFactory.getLogger(WebhookChallengeVerifier.class);
    private static final String SUBSCRIBE = "subscribe";
    private static final int MAX_CHALLENGE_BYTES = 256;
    private static final String DIGEST = "SHA-256";

    private final IntegrationSecretResolver resolver;
    private final Optional<SecretReference> verifyTokenRef;
    private final WebhookTelemetry telemetry;

    /** An empty {@code verifyTokenRef} is the refusing state: every call refuses without touching the resolver. */
    public WebhookChallengeVerifier(IntegrationSecretResolver resolver, Optional<SecretReference> verifyTokenRef,
            WebhookTelemetry telemetry) {
        this.resolver = Objects.requireNonNull(resolver, "Secret resolver is required");
        this.verifyTokenRef = Objects.requireNonNull(verifyTokenRef, "Verify token reference is required");
        this.telemetry = Objects.requireNonNull(telemetry, "Telemetry is required");
    }

    /**
     * Checks {@code hub.mode}, {@code hub.challenge} and {@code hub.verify_token} in that order (AC-4) and returns
     * the challenge unchanged on success. Every argument may be null when the route did not receive it.
     */
    public String verify(String mode, String verifyToken, String challenge) {
        if (!SUBSCRIBE.equals(mode)) {
            throw refuse(Reason.WRONG_MODE);
        }
        if (!isPrintableAscii(challenge)) {
            throw refuse(Reason.INVALID_CHALLENGE);
        }
        if (verifyToken == null) {
            throw refuse(Reason.TOKEN_MISMATCH);
        }
        SecretReference reference = verifyTokenRef.orElseThrow(() -> refuse(Reason.SECRET_UNAVAILABLE));
        byte[] expected = digestOfExpectedToken(reference);
        byte[] presented = digest(verifyToken.getBytes(StandardCharsets.UTF_8));
        if (!MessageDigest.isEqual(expected, presented)) {
            throw refuse(Reason.TOKEN_MISMATCH);
        }
        return challenge;
    }

    private byte[] digestOfExpectedToken(SecretReference reference) {
        ResolvedSecret secret;
        try {
            secret = resolver.resolve(new Deployment(SecretPurpose.WEBHOOK_VERIFY_TOKEN), reference);
        } catch (SecretUnavailableException denied) {
            // Deliberately not chained: the refusal carries no scope or category outward.
            throw refuse(Reason.SECRET_UNAVAILABLE);
        }
        try (secret) {
            MessageDigest digest = SecretKeyBytes.withUtf8Key(secret, key -> {
                MessageDigest engine = MessageDigest.getInstance(DIGEST);
                engine.update(key);
                return engine;
            });
            return digest.digest();
        }
    }

    private static byte[] digest(byte[] bytes) {
        try {
            return MessageDigest.getInstance(DIGEST).digest(bytes);
        } catch (NoSuchAlgorithmException impossibleOnAStandardJdk) {
            throw new IllegalStateException(SecretKeyBytes.UNAVAILABLE);
        }
    }

    /** Non null, non empty, at most 256 bytes, every character in {@code 0x20} to {@code 0x7E}. */
    private static boolean isPrintableAscii(String challenge) {
        if (challenge == null || challenge.isEmpty() || challenge.length() > MAX_CHALLENGE_BYTES) {
            return false;
        }
        for (int index = 0; index < challenge.length(); index++) {
            char character = challenge.charAt(index);
            if (character < 0x20 || character > 0x7E) {
                return false;
            }
        }
        return true;
    }

    private WebhookVerificationException refuse(Reason reason) {
        log.warn("Webhook rejected reason={}", reason);
        telemetry.rejected(reason);
        return new WebhookVerificationException(reason);
    }
}
