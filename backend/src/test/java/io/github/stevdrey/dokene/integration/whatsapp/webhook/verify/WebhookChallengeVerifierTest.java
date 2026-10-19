// ABOUTME: Proves spec 0007 AC-4, AC-5 and AC-8 for the handshake: ordered checks, one resolve, digest then compare.
// ABOUTME: Every test runs under a LogCapture that must contain neither the verify token nor the challenge.
package io.github.stevdrey.dokene.integration.whatsapp.webhook.verify;

import static io.github.stevdrey.dokene.integration.testsupport.SecretCanaries.containsAny;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.Deployment;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.SecretPurpose;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.SecretReference;
import io.github.stevdrey.dokene.integration.application.SecretUnavailableException.Category;
import io.github.stevdrey.dokene.integration.testsupport.LogCapture;
import io.github.stevdrey.dokene.integration.testsupport.RecordingSecretResolver;
import io.github.stevdrey.dokene.integration.testsupport.SecretCanaries;
import io.github.stevdrey.dokene.integration.whatsapp.webhook.verify.WebhookVerificationException.Reason;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WebhookChallengeVerifierTest {
    private final String verifyToken = SecretCanaries.value();
    private final String verifyTokenRef = SecretCanaries.ref();
    private final String challenge = "challenge-" + SecretCanaries.value();
    private final List<Reason> rejected = new ArrayList<>();
    private final RecordingSecretResolver resolver = new RecordingSecretResolver()
            .holding(SecretPurpose.WEBHOOK_VERIFY_TOKEN, verifyTokenRef, verifyToken);
    private final WebhookChallengeVerifier verifier = new WebhookChallengeVerifier(resolver,
            Optional.of(new SecretReference(verifyTokenRef)), rejected::add);

    private LogCapture logs;

    @BeforeEach
    void setUp() {
        logs = new LogCapture();
    }

    @AfterEach
    void tearDown() {
        boolean leaked = containsAny(logs.text(), List.of(verifyToken, verifyTokenRef, challenge));
        logs.close();
        assertThat(leaked).isFalse();
    }

    @Test
    void aMatchingTokenEchoesTheChallengeUnchangedAfterExactlyOneResolve() {
        assertThat(verifier.verify("subscribe", verifyToken, challenge)).isSameAs(challenge);

        assertThat(resolver.calls()).containsExactly(new RecordingSecretResolver.Call(
                new Deployment(SecretPurpose.WEBHOOK_VERIFY_TOKEN), new SecretReference(verifyTokenRef)));
        assertThat(resolver.allIssuedClosed()).isTrue();
        assertThat(rejected).isEmpty();
        assertThat(logs.warningsFrom(WebhookChallengeVerifier.class)).isEmpty();
    }

    @Test
    void aWrongModeIsRefusedFirstWithoutAResolverCall() {
        for (String mode : new String[] {null, "", "Subscribe", " subscribe", "subscribe ", "unsubscribe"}) {
            assertThat(reasonOf(() -> verifier.verify(mode, verifyToken, challenge))).as(String.valueOf(mode))
                    .isEqualTo(Reason.WRONG_MODE);
        }
        assertThat(resolver.calls()).isEmpty();
        assertThat(logs.warningsFrom(WebhookChallengeVerifier.class)).hasSize(6);
    }

    @Test
    void anInvalidChallengeIsRefusedBeforeTheTokenIsLookedAt() {
        List<String> invalid = new ArrayList<>();
        invalid.add(null);
        invalid.add("");
        invalid.add("x".repeat(257));
        invalid.add("abc\n");
        invalid.add("café");
        invalid.add("tab\there");

        for (String bad : invalid) {
            assertThat(reasonOf(() -> verifier.verify("subscribe", null, bad))).as(String.valueOf(bad))
                    .isEqualTo(Reason.INVALID_CHALLENGE);
        }
        assertThat(verifier.verify("subscribe", verifyToken, "x".repeat(256))).hasSize(256);
        assertThat(verifier.verify("subscribe", verifyToken, " ~")).isEqualTo(" ~");
        assertThat(resolver.calls()).hasSize(2);
    }

    @Test
    void aMissingTokenIsATokenMismatchWithoutAResolverCall() {
        assertThat(reasonOf(() -> verifier.verify("subscribe", null, challenge))).isEqualTo(Reason.TOKEN_MISMATCH);

        assertThat(resolver.calls()).isEmpty();
        assertThat(rejected).containsExactly(Reason.TOKEN_MISMATCH);
    }

    @Test
    void aWrongTokenIsATokenMismatchAfterOneResolveAndTheSecretIsClosed() {
        for (String wrong : new String[] {"", verifyToken + "x", verifyToken.substring(1), verifyToken.toUpperCase()}) {
            assertThat(reasonOf(() -> verifier.verify("subscribe", wrong, challenge))).isEqualTo(Reason.TOKEN_MISMATCH);
        }

        assertThat(resolver.calls()).hasSize(4);
        assertThat(resolver.allIssuedClosed()).isTrue();
        assertThat(logs.warningsFrom(WebhookChallengeVerifier.class)).hasSize(4);
    }

    @Test
    void aBlankReferenceRefusesAfterTheShapeChecksWithoutAResolverCall() {
        WebhookChallengeVerifier refusing = new WebhookChallengeVerifier(resolver, Optional.empty(), rejected::add);

        assertThat(reasonOf(() -> refusing.verify("x", verifyToken, challenge))).isEqualTo(Reason.WRONG_MODE);
        assertThat(reasonOf(() -> refusing.verify("subscribe", verifyToken, challenge)))
                .isEqualTo(Reason.SECRET_UNAVAILABLE);

        assertThat(resolver.calls()).isEmpty();
    }

    @Test
    void everyResolverDenialBecomesSecretUnavailableWithoutACause() {
        for (Category category : Category.values()) {
            RecordingSecretResolver denying = new RecordingSecretResolver().denying(category);
            WebhookChallengeVerifier denied = new WebhookChallengeVerifier(denying,
                    Optional.of(new SecretReference(verifyTokenRef)), rejected::add);

            WebhookVerificationException refusal = catchThrowableOfType(WebhookVerificationException.class,
                    () -> denied.verify("subscribe", verifyToken, challenge));

            assertThat(refusal.reason()).as(category.name()).isEqualTo(Reason.SECRET_UNAVAILABLE);
            assertThat(refusal.getCause()).isNull();
            assertThat(denying.calls()).hasSize(1);
        }
    }

    @Test
    void anyOtherResolverFailurePropagatesUnchanged() {
        RuntimeException failure = new IllegalStateException("resolver down");
        WebhookChallengeVerifier failing = new WebhookChallengeVerifier(
                new RecordingSecretResolver().throwing(failure), Optional.of(new SecretReference(verifyTokenRef)),
                rejected::add);

        assertThatThrownBy(() -> failing.verify("subscribe", verifyToken, challenge)).isSameAs(failure);
        assertThat(rejected).isEmpty();
    }

    private static Reason reasonOf(Runnable call) {
        return catchThrowableOfType(WebhookVerificationException.class, call::run).reason();
    }
}
