// ABOUTME: Public Meta WhatsApp callback route (ADR 0023 §3.4, issue 139): read bounded, verify HMAC, parse, record, 200.
// ABOUTME: Every verification refusal is one 403 and every schema refusal one 400; bodies never echo anything back.
package io.github.stevdrey.dokene.integration.whatsapp.webhook;

import io.github.stevdrey.dokene.integration.whatsapp.webhook.verify.BoundedWebhookBodyReader;
import io.github.stevdrey.dokene.integration.whatsapp.webhook.verify.VerifiedWebhookBody;
import io.github.stevdrey.dokene.integration.whatsapp.webhook.verify.WebhookChallengeVerifier;
import io.github.stevdrey.dokene.integration.whatsapp.webhook.verify.WebhookSignatureVerifier;
import io.github.stevdrey.dokene.integration.whatsapp.webhook.verify.WebhookVerificationException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The order is fixed: the body is read through the bounded reader, the signature is checked on those raw bytes, and
 * only then is anything parsed. The tenant is never read from the payload; ingestion resolves it from the verified
 * mapping. The 200 is sent only after ingestion returned, that is after every status is durably recorded or dropped.
 * A database or audit failure while recording propagates to the outage boundary (ADR 0029) and Meta retries.
 */
@RestController
class WhatsAppWebhookController {
    static final String PATH = "/webhooks/whatsapp";
    static final String SIGNATURE_HEADER = "X-Hub-Signature-256";
    private static final Logger log = LoggerFactory.getLogger(WhatsAppWebhookController.class);

    private final BoundedWebhookBodyReader reader;
    private final WebhookSignatureVerifier signatures;
    private final WebhookChallengeVerifier challenges;
    private final MetaWebhookPayloadParser parser;
    private final WebhookIngestionService ingestion;

    WhatsAppWebhookController(BoundedWebhookBodyReader reader, WebhookSignatureVerifier signatures,
            WebhookChallengeVerifier challenges, MetaWebhookPayloadParser parser, WebhookIngestionService ingestion) {
        this.reader = Objects.requireNonNull(reader);
        this.signatures = Objects.requireNonNull(signatures);
        this.challenges = Objects.requireNonNull(challenges);
        this.parser = Objects.requireNonNull(parser);
        this.ingestion = Objects.requireNonNull(ingestion);
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
        VerifiedWebhookBody body;
        try {
            byte[] raw = reader.read(request.getInputStream(), request.getContentLengthLong());
            List<String> signatureHeaders = Collections.list(request.getHeaders(SIGNATURE_HEADER));
            body = signatures.verify(raw, signatureHeaders);
        } catch (WebhookVerificationException refused) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } catch (IOException | UncheckedIOException unreadable) {
            log.warn("Webhook body could not be read");
            return ResponseEntity.badRequest().build();
        }
        ParsedWebhook parsed;
        try {
            parsed = parser.parse(body);
        } catch (WebhookPayloadException invalid) {
            log.warn("Webhook payload rejected; reason={}", invalid.reason());
            return ResponseEntity.badRequest().build();
        }
        ingestion.ingest(parsed);
        return ResponseEntity.ok().build();
    }
}
