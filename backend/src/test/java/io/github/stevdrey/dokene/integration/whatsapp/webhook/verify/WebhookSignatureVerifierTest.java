// ABOUTME: Proves spec 0007 AC-1, AC-2, AC-5, AC-7 and AC-8 for the signature path with synthetic signed bodies.
// ABOUTME: Every test runs under a LogCapture that must contain neither the secret, the header nor the body canary.
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
import io.github.stevdrey.dokene.integration.testsupport.WebhookSigning;
import io.github.stevdrey.dokene.integration.whatsapp.webhook.verify.WebhookVerificationException.Reason;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WebhookSignatureVerifierTest {
    private final String appSecret = SecretCanaries.value();
    private final String appSecretRef = SecretCanaries.ref();
    private final String bodyCanary = "body-" + SecretCanaries.value();
    private final byte[] body = WebhookSigning.body(bodyCanary);
    private final String signature = WebhookSigning.sign(body, appSecret.toCharArray());
    private final List<Reason> rejected = new ArrayList<>();
    private final RecordingSecretResolver resolver = new RecordingSecretResolver()
            .holding(SecretPurpose.APP_SECRET, appSecretRef, appSecret);
    private final WebhookSignatureVerifier verifier = new WebhookSignatureVerifier(resolver,
            Optional.of(new SecretReference(appSecretRef)), rejected::add);

    private LogCapture logs;

    @BeforeEach
    void setUp() {
        logs = new LogCapture();
    }

    @AfterEach
    void tearDown() {
        boolean leaked = containsAny(logs.text(), List.of(appSecret, appSecretRef, signature, bodyCanary));
        logs.close();
        assertThat(leaked).isFalse();
    }

    @Test
    void aSignedBodyVerifiesAndTheResultIsACopyOfTheHashedBytes() {
        byte[] input = body.clone();

        VerifiedWebhookBody verified = verifier.verify(input, List.of(signature));
        Arrays.fill(input, (byte) 'x');

        assertThat(verified.length()).isEqualTo(body.length);
        assertThat(verified.copy()).isEqualTo(body);
        assertThat(verified.toString()).isEqualTo("VerifiedWebhookBody[" + body.length + " bytes]");
        assertThat(resolver.calls()).containsExactly(new RecordingSecretResolver.Call(
                new Deployment(SecretPurpose.APP_SECRET), new SecretReference(appSecretRef)));
        assertThat(resolver.allIssuedClosed()).isTrue();
        assertThat(rejected).isEmpty();
        assertThat(logs.warningsFrom(WebhookSignatureVerifier.class)).isEmpty();
    }

    @Test
    void everyMutationOfASignedBodyIsASignatureMismatch() {
        Map<String, byte[]> mutations = WebhookSigning.mutationsOf(bodyCanary);

        for (Map.Entry<String, byte[]> mutation : mutations.entrySet()) {
            assertThat(reasonOf(() -> verifier.verify(mutation.getValue(), List.of(signature))))
                    .as(mutation.getKey()).isEqualTo(Reason.SIGNATURE_MISMATCH);
        }
        String otherSecret = WebhookSigning.sign(body, SecretCanaries.value().toCharArray());
        assertThat(reasonOf(() -> verifier.verify(body, List.of(otherSecret)))).isEqualTo(Reason.SIGNATURE_MISMATCH);

        int refusals = mutations.size() + 1;
        assertThat(resolver.calls()).hasSize(refusals);
        assertThat(resolver.allIssuedClosed()).isTrue();
        assertThat(rejected).hasSize(refusals).containsOnly(Reason.SIGNATURE_MISMATCH);
        assertThat(logs.warningsFrom(WebhookSignatureVerifier.class)).hasSize(refusals);
    }

    @Test
    void aMissingHeaderIsRefusedBeforeTheResolverIsTouched() {
        assertThat(reasonOf(() -> verifier.verify(body, null))).isEqualTo(Reason.MISSING_SIGNATURE);
        assertThat(reasonOf(() -> verifier.verify(body, List.of()))).isEqualTo(Reason.MISSING_SIGNATURE);

        assertThat(resolver.calls()).isEmpty();
        assertThat(rejected).containsExactly(Reason.MISSING_SIGNATURE, Reason.MISSING_SIGNATURE);
        assertThat(logs.warningsFrom(WebhookSignatureVerifier.class)).hasSize(2);
    }

    @Test
    void everyMalformedHeaderShapeIsRefusedBeforeTheResolverIsTouched() {
        String digits = signature.substring("sha256=".length());
        List<List<String>> malformed = List.of(
                List.of(signature, signature),
                Collections.singletonList(null),
                List.of(""),
                List.of("SHA256=" + digits),
                List.of(digits),
                List.of("sha256=" + digits.substring(1)),
                List.of("sha256=" + digits + "0"),
                List.of("sha256=" + digits.substring(1) + "٣"),
                List.of("sha256=" + digits.substring(1) + "g"),
                List.of(" " + signature));

        for (List<String> values : malformed) {
            assertThat(reasonOf(() -> verifier.verify(body, values))).as(String.valueOf(values))
                    .isEqualTo(Reason.MALFORMED_SIGNATURE);
        }

        assertThat(resolver.calls()).isEmpty();
        assertThat(rejected).hasSize(malformed.size()).containsOnly(Reason.MALFORMED_SIGNATURE);
        assertThat(logs.warningsFrom(WebhookSignatureVerifier.class)).hasSize(malformed.size());
    }

    @Test
    void uppercaseHexDigitsAreAccepted() {
        String uppercase = WebhookSigning.signUppercase(body, appSecret.toCharArray());

        assertThat(verifier.verify(body, List.of(uppercase)).copy()).isEqualTo(body);
    }

    @Test
    void aBlankReferenceRefusesAfterTheShapeChecksWithoutAResolverCall() {
        WebhookSignatureVerifier refusing = new WebhookSignatureVerifier(resolver, Optional.empty(), rejected::add);

        assertThat(reasonOf(() -> refusing.verify(body, List.of()))).isEqualTo(Reason.MISSING_SIGNATURE);
        assertThat(reasonOf(() -> refusing.verify(body, List.of(signature)))).isEqualTo(Reason.SECRET_UNAVAILABLE);

        assertThat(resolver.calls()).isEmpty();
        assertThat(logs.warningsFrom(WebhookSignatureVerifier.class)).hasSize(2);
    }

    @Test
    void everyResolverDenialBecomesSecretUnavailableWithoutACause() {
        for (Category category : Category.values()) {
            RecordingSecretResolver denying = new RecordingSecretResolver().denying(category);
            WebhookSignatureVerifier denied = new WebhookSignatureVerifier(denying,
                    Optional.of(new SecretReference(appSecretRef)), rejected::add);

            WebhookVerificationException refusal = catchThrowableOfType(WebhookVerificationException.class,
                    () -> denied.verify(body, List.of(signature)));

            assertThat(refusal.reason()).as(category.name()).isEqualTo(Reason.SECRET_UNAVAILABLE);
            assertThat(refusal.getCause()).isNull();
            assertThat(denying.calls()).hasSize(1);
        }
        assertThat(logs.warningsFrom(WebhookSignatureVerifier.class)).hasSize(Category.values().length);
    }

    @Test
    void anUnknownReferenceIsAlsoSecretUnavailable() {
        WebhookSignatureVerifier unknown = new WebhookSignatureVerifier(resolver,
                Optional.of(new SecretReference(SecretCanaries.ref())), rejected::add);

        assertThat(reasonOf(() -> unknown.verify(body, List.of(signature)))).isEqualTo(Reason.SECRET_UNAVAILABLE);
    }

    @Test
    void anyOtherResolverFailurePropagatesUnchanged() {
        RuntimeException failure = new IllegalStateException("resolver down");
        WebhookSignatureVerifier failing = new WebhookSignatureVerifier(
                new RecordingSecretResolver().throwing(failure), Optional.of(new SecretReference(appSecretRef)),
                rejected::add);

        assertThatThrownBy(() -> failing.verify(body, List.of(signature))).isSameAs(failure);
        assertThat(rejected).isEmpty();
    }

    private static Reason reasonOf(Runnable call) {
        return catchThrowableOfType(WebhookVerificationException.class, call::run).reason();
    }
}
