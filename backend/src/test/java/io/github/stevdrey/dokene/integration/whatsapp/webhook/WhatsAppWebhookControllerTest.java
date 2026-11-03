// ABOUTME: Proves the issue 306 order of the webhook route with a stream that counts every byte read: cheap refusals
// ABOUTME: and saturation read nothing, a signed callback still records, and every outcome returns its permit.
package io.github.stevdrey.dokene.integration.whatsapp.webhook;

import static io.github.stevdrey.dokene.integration.testsupport.MetaCallbacks.report;
import static io.github.stevdrey.dokene.integration.testsupport.MetaCallbacks.statuses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.SecretPurpose;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.SecretReference;
import io.github.stevdrey.dokene.integration.testsupport.RecordingSecretResolver;
import io.github.stevdrey.dokene.integration.testsupport.WebhookSigning;
import io.github.stevdrey.dokene.integration.whatsapp.webhook.verify.BoundedWebhookBodyReader;
import io.github.stevdrey.dokene.integration.whatsapp.webhook.verify.WebhookAdmission;
import io.github.stevdrey.dokene.integration.whatsapp.webhook.verify.WebhookAdmission.Permit;
import io.github.stevdrey.dokene.integration.whatsapp.webhook.verify.WebhookChallengeVerifier;
import io.github.stevdrey.dokene.integration.whatsapp.webhook.verify.WebhookRejectionLog;
import io.github.stevdrey.dokene.integration.whatsapp.webhook.verify.WebhookSignatureVerifier;
import io.github.stevdrey.dokene.integration.whatsapp.webhook.verify.WebhookTelemetry;
import io.github.stevdrey.dokene.integration.whatsapp.webhook.verify.WebhookVerificationException.Reason;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

class WhatsAppWebhookControllerTest {
    private static final int MAX_BODY_BYTES = 4096;
    private static final int PERMITS = 2;
    private static final String SECRET_REF = "controller-app-secret";
    private static final String APP_SECRET = "controller-test-app-secret";
    private static final String VERIFY_TOKEN_REF = "controller-verify-token";

    private final RecordingTelemetry telemetry = new RecordingTelemetry();
    private final WebhookRejectionLog rejectionLog = new WebhookRejectionLog(Clock.systemUTC());
    private final RecordingSecretResolver resolver = new RecordingSecretResolver()
            .holding(SecretPurpose.APP_SECRET, SECRET_REF, APP_SECRET);
    private final WebhookAdmission admission = new WebhookAdmission(PERMITS);
    private final WebhookIngestionService ingestion = mock(WebhookIngestionService.class);
    private final byte[] signedBody = statuses("200000000000001", "100000000000001",
            report("wamid.CONTROLLER-1", "delivered", 1_700_000_000L, null));

    @Test
    void aSignedCallbackIsReadVerifiedAndRecordedUnderAPermitThatIsReturned() {
        CountingRequest request = new CountingRequest(signedBody, signedBody.length, sign(signedBody));

        assertThat(controller(Optional.of(new SecretReference(SECRET_REF))).receive(request).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(request.bytesRead).isEqualTo(signedBody.length);
        verify(ingestion).ingest(any());
        assertThat(allPermitsReturned()).isTrue();
    }

    @Test
    void aMissingDuplicatedOrMalformedHeaderIsRefusedWithoutReadingTheBody() {
        byte[] large = new byte[MAX_BODY_BYTES];
        List<CountingRequest> requests = List.of(new CountingRequest(large, large.length),
                new CountingRequest(large, large.length, sign(large), sign(large)),
                new CountingRequest(large, large.length, "sha256=zz"),
                new CountingRequest(large, -1, "sha1=" + "0".repeat(40)));

        for (CountingRequest request : requests) {
            assertThat(controller(Optional.of(new SecretReference(SECRET_REF))).receive(request).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(request.bytesRead).isZero();
        }
        assertThat(telemetry.rejected).containsExactly(Reason.MISSING_SIGNATURE, Reason.MALFORMED_SIGNATURE,
                Reason.MALFORMED_SIGNATURE, Reason.MALFORMED_SIGNATURE);
        assertThat(resolver.calls()).isEmpty();
        assertThat(allPermitsReturned()).isTrue();
    }

    @Test
    void anUnsetAppSecretIsRefusedWithoutReadingTheBody() {
        CountingRequest request = new CountingRequest(signedBody, signedBody.length, sign(signedBody));

        assertThat(controller(Optional.empty()).receive(request).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(request.bytesRead).isZero();
        assertThat(telemetry.rejected).containsExactly(Reason.SECRET_UNAVAILABLE);
    }

    @Test
    void aDeclaredLengthOverTheBoundIsRefusedWithoutReadingOrTakingAPermit() {
        byte[] oversized = new byte[MAX_BODY_BYTES + 1];
        CountingRequest request = new CountingRequest(oversized, oversized.length, sign(oversized));

        assertThat(controller(Optional.of(new SecretReference(SECRET_REF))).receive(request).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(request.bytesRead).isZero();
        assertThat(telemetry.rejected).containsExactly(Reason.BODY_TOO_LARGE);
        assertThat(resolver.calls()).isEmpty();
    }

    @Test
    void aLyingOrUnknownLengthStillCostsAtMostOneBytePastTheBoundAndNoHmac() {
        byte[] oversized = new byte[MAX_BODY_BYTES * 4];
        for (long declared : new long[] {-1, 1, MAX_BODY_BYTES}) {
            CountingRequest request = new CountingRequest(oversized, declared, sign(oversized));

            assertThat(controller(Optional.of(new SecretReference(SECRET_REF))).receive(request).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            // One byte past the declared length when it is known, past the bound when it is not.
            assertThat(request.bytesRead).isEqualTo(declared < 0 ? MAX_BODY_BYTES + 1 : declared + 1);
        }
        assertThat(resolver.calls()).isEmpty();
        assertThat(allPermitsReturned()).isTrue();
    }

    @Test
    void aSaturatedRouteAnswers503WithRetryAfterBeforeReadingAByte() {
        List<Permit> held = new ArrayList<>();
        for (int index = 0; index < PERMITS; index++) {
            held.add(admission.tryAdmit().orElseThrow());
        }
        CountingRequest request = new CountingRequest(signedBody, signedBody.length, sign(signedBody));

        ResponseEntity<Void> answer = controller(Optional.of(new SecretReference(SECRET_REF))).receive(request);

        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(answer.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                .isEqualTo(WhatsAppWebhookController.RETRY_AFTER_SECONDS);
        assertThat(answer.getBody()).isNull();
        assertThat(request.bytesRead).isZero();
        assertThat(telemetry.overloaded).isEqualTo(1);
        assertThat(telemetry.rejected).isEmpty();
        verify(ingestion, never()).ingest(any());

        held.forEach(Permit::close);
        CountingRequest retried = new CountingRequest(signedBody, signedBody.length, sign(signedBody));
        assertThat(controller(Optional.of(new SecretReference(SECRET_REF))).receive(retried).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void thePermitIsReturnedAfterAMismatchASchemaRefusalAndAFailingIngestion() {
        WhatsAppWebhookController controller = controller(Optional.of(new SecretReference(SECRET_REF)));

        assertThat(controller.receive(new CountingRequest(signedBody, signedBody.length,
                WebhookSigning.sign(signedBody, "wrong".toCharArray()))).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(allPermitsReturned()).isTrue();

        byte[] invalid = "{\"object\":\"whatsapp_business_account\",\"tenantId\":\"x\",\"entry\":[]}"
                .getBytes(StandardCharsets.UTF_8);
        assertThat(controller.receive(new CountingRequest(invalid, invalid.length, sign(invalid))).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(allPermitsReturned()).isTrue();

        RuntimeException outage = new IllegalStateException("database unavailable");
        doThrow(outage).when(ingestion).ingest(any());
        assertThatThrownBy(() -> controller.receive(new CountingRequest(signedBody, signedBody.length,
                sign(signedBody)))).isSameAs(outage);
        assertThat(allPermitsReturned()).isTrue();
    }

    private WhatsAppWebhookController controller(Optional<SecretReference> appSecretRef) {
        return new WhatsAppWebhookController(new BoundedWebhookBodyReader(MAX_BODY_BYTES, telemetry, rejectionLog),
                new WebhookSignatureVerifier(resolver, appSecretRef, telemetry, rejectionLog),
                new WebhookChallengeVerifier(resolver, Optional.of(new SecretReference(VERIFY_TOKEN_REF)), telemetry,
                        rejectionLog),
                new MetaWebhookPayloadParser(), ingestion, admission, rejectionLog, telemetry);
    }

    private boolean allPermitsReturned() {
        List<Permit> taken = new ArrayList<>();
        for (int index = 0; index < PERMITS; index++) {
            admission.tryAdmit().ifPresent(taken::add);
        }
        boolean all = taken.size() == PERMITS && admission.tryAdmit().isEmpty();
        taken.forEach(Permit::close);
        return all;
    }

    private static String sign(byte[] body) {
        return WebhookSigning.sign(body, APP_SECRET.toCharArray());
    }

    private static final class RecordingTelemetry implements WebhookTelemetry {
        private final List<Reason> rejected = new ArrayList<>();
        private int overloaded;

        @Override
        public void rejected(Reason reason) {
            rejected.add(reason);
        }

        @Override
        public void overloaded() {
            overloaded++;
        }
    }

    /** Declares a chosen {@code Content-Length} and counts every byte the route takes from the body stream. */
    private static final class CountingRequest extends MockHttpServletRequest {
        private final ByteArrayInputStream content;
        private final long declaredLength;
        private int bytesRead;

        CountingRequest(byte[] body, long declaredLength, String... signatures) {
            super("POST", WhatsAppWebhookController.PATH);
            this.content = new ByteArrayInputStream(body);
            this.declaredLength = declaredLength;
            for (String signature : signatures) {
                addHeader(WhatsAppWebhookController.SIGNATURE_HEADER, signature);
            }
        }

        @Override
        public long getContentLengthLong() {
            return declaredLength;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new ServletInputStream() {
                @Override
                public int read() {
                    int next = content.read();
                    if (next >= 0) {
                        bytesRead++;
                    }
                    return next;
                }

                @Override
                public int read(byte[] buffer, int offset, int length) {
                    int count = content.read(buffer, offset, length);
                    if (count > 0) {
                        bytesRead += count;
                    }
                    return count;
                }

                @Override
                public boolean isFinished() {
                    return content.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException();
                }
            };
        }
    }
}
