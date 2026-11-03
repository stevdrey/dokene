// ABOUTME: Public Meta WhatsApp callback route (ADR 0023 §3.4, issues 139, 306): cheap checks, admit, read bounded,
// ABOUTME: verify, parse, record, 200. Verification refusals are 403, schema refusals 400, saturation 503; no echo.
package io.github.stevdrey.dokene.integration.whatsapp.webhook;

import io.github.stevdrey.dokene.integration.whatsapp.webhook.verify.BoundedWebhookBodyReader;
import io.github.stevdrey.dokene.integration.whatsapp.webhook.verify.VerifiedWebhookBody;
import io.github.stevdrey.dokene.integration.whatsapp.webhook.verify.WebhookAdmission;
import io.github.stevdrey.dokene.integration.whatsapp.webhook.verify.WebhookAdmission.Permit;
import io.github.stevdrey.dokene.integration.whatsapp.webhook.verify.WebhookChallengeVerifier;
import io.github.stevdrey.dokene.integration.whatsapp.webhook.verify.WebhookRejectionLog;
import io.github.stevdrey.dokene.integration.whatsapp.webhook.verify.WebhookRejectionLog.Line;
import io.github.stevdrey.dokene.integration.whatsapp.webhook.verify.WebhookSignatureVerifier;
import io.github.stevdrey.dokene.integration.whatsapp.webhook.verify.WebhookSignatureVerifier.SignatureCheck;
import io.github.stevdrey.dokene.integration.whatsapp.webhook.verify.WebhookTelemetry;
import io.github.stevdrey.dokene.integration.whatsapp.webhook.verify.WebhookVerificationException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The order is fixed. Everything that needs no body comes first (issue 306): the signature header grammar, a configured
 * app secret and the declared length; then the request takes one of a bounded number of permits, held until the answer,
 * or is refused with 503 and nothing read. Only then is the body read through the bounded reader, the signature checked
 * on those raw bytes, and the payload parsed. The tenant is never read from the payload; ingestion resolves it from
 * the verified mapping. The 200 is sent only after ingestion returned, that is after every status is durably recorded
 * or dropped. A database or audit failure while recording propagates to the outage boundary (ADR 0029), and Meta
 * retries.
 */
@RestController
class WhatsAppWebhookController {
    static final String PATH = "/webhooks/whatsapp";
    static final String SIGNATURE_HEADER = "X-Hub-Signature-256";
    static final String RETRY_AFTER_SECONDS = "5";
    private static final Logger log = LoggerFactory.getLogger(WhatsAppWebhookController.class);

    private final BoundedWebhookBodyReader reader;
    private final WebhookSignatureVerifier signatures;
    private final WebhookChallengeVerifier challenges;
    private final MetaWebhookPayloadParser parser;
    private final WebhookIngestionService ingestion;
    private final WebhookAdmission admission;
    private final WebhookRejectionLog rejectionLog;
    private final WebhookTelemetry telemetry;

    WhatsAppWebhookController(BoundedWebhookBodyReader reader, WebhookSignatureVerifier signatures,
            WebhookChallengeVerifier challenges, MetaWebhookPayloadParser parser, WebhookIngestionService ingestion,
            WebhookAdmission admission, WebhookRejectionLog rejectionLog, WebhookTelemetry telemetry) {
        this.reader = Objects.requireNonNull(reader);
        this.signatures = Objects.requireNonNull(signatures);
        this.challenges = Objects.requireNonNull(challenges);
        this.parser = Objects.requireNonNull(parser);
        this.ingestion = Objects.requireNonNull(ingestion);
        this.admission = Objects.requireNonNull(admission);
        this.rejectionLog = Objects.requireNonNull(rejectionLog);
        this.telemetry = Objects.requireNonNull(telemetry);
    }

    @GetMapping(path = PATH, produces = MediaType.TEXT_PLAIN_VALUE)
    ResponseEntity<String> challenge(@RequestParam(name = "hub.mode", required = false) String mode,
            @RequestParam(name = "hub.verify_token", required = false) String verifyToken,
            @RequestParam(name = "hub.challenge", required = false) String challenge) {
        try {
            String echoed = challenges.verify(mode, verifyToken, challenge);
            return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(echoed);
        } catch (WebhookVerificationException refused) {
            // The verifier already logged and counted the reason; the outward answer never depends on it.
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
    }

    @PostMapping(path = PATH)
    ResponseEntity<Void> receive(HttpServletRequest request) {
        SignatureCheck check;
        try {
            List<String> signatureHeaders = Collections.list(request.getHeaders(SIGNATURE_HEADER));
            check = signatures.prepare(signatureHeaders);
            reader.checkDeclaredLength(request.getContentLengthLong());
        } catch (WebhookVerificationException refused) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        Optional<Permit> admitted = admission.tryAdmit();
        if (admitted.isEmpty()) {
            rejectionLog.warn(log, Line.OVERLOADED);
            telemetry.overloaded();
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS).build();
        }
        try (Permit _ = admitted.get()) {
            return receive(request, check);
        }
    }

    /** Runs under a permit, so at most the admission bound of bodies is in memory or being recorded at once. */
    private ResponseEntity<Void> receive(HttpServletRequest request, SignatureCheck check) {
        VerifiedWebhookBody body;
        try {
            byte[] raw = reader.read(request.getInputStream(), request.getContentLengthLong());
            body = check.verify(raw);
        } catch (WebhookVerificationException refused) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } catch (IOException | UncheckedIOException unreadable) {
            rejectionLog.warn(log, Line.UNREADABLE);
            return ResponseEntity.badRequest().build();
        }
        ParsedWebhook parsed;
        try {
            parsed = parser.parse(body);
        } catch (WebhookPayloadException invalid) {
            // Level and member are a closed enum and a sanitized key name, never a payload value.
            log.warn("Webhook payload rejected; reason={} level={} member={}", invalid.reason(),
                    invalid.level().map(Enum::name).orElse("-"), invalid.member().orElse("-"));
            return ResponseEntity.badRequest().build();
        }
        ingestion.ingest(parsed);
        return ResponseEntity.ok().build();
    }
}
