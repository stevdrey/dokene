// ABOUTME: Proves the strict webhook parser (issue 139, ADR 0030) against the signed fixtures and hostile variants.
// ABOUTME: Unknown members refuse, unsupported items are only counted, and no error text or phone survives parsing.
package io.github.stevdrey.dokene.integration.whatsapp.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.SecretPurpose;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.SecretReference;
import io.github.stevdrey.dokene.integration.testsupport.RecordingSecretResolver;
import io.github.stevdrey.dokene.integration.testsupport.WebhookSigning;
import io.github.stevdrey.dokene.integration.whatsapp.webhook.ParsedWebhook.StatusUpdate;
import io.github.stevdrey.dokene.integration.whatsapp.webhook.WebhookPayloadException.Reason;
import io.github.stevdrey.dokene.integration.whatsapp.webhook.verify.VerifiedWebhookBody;
import io.github.stevdrey.dokene.integration.whatsapp.webhook.verify.WebhookSignatureVerifier;
import io.github.stevdrey.dokene.messaging.domain.DeliveryStatus;
import io.github.stevdrey.dokene.messaging.domain.FailureCategory;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class MetaWebhookPayloadParserTest {
    private static final String SECRET_REF = "fixture-app-secret";
    private static final String PHONE = "100000000000001";
    private static final JsonNode FIXTURES = load();

    private final WebhookSignatureVerifier verifier = new WebhookSignatureVerifier(new RecordingSecretResolver()
            .holding(SecretPurpose.APP_SECRET, SECRET_REF, FIXTURES.get("signingKey").stringValue()),
            Optional.of(new SecretReference(SECRET_REF)), reason -> { });
    private final MetaWebhookPayloadParser parser = new MetaWebhookPayloadParser();

    @Test
    void everySignedFixtureParsesToItsExpectedTypedStatus() {
        for (String name : List.of("delivered", "duplicateOfDelivered", "failed", "readBeforeDelivered",
                "deliveredAfterRead")) {
            JsonNode entry = FIXTURES.get("entries").get(name);
            byte[] body = entry.get("body").stringValue().getBytes(StandardCharsets.UTF_8);
            VerifiedWebhookBody verified = verifier.verify(body, List.of(entry.get("signature").stringValue()));
            JsonNode expected = entry.get("expected");

            ParsedWebhook parsed = parser.parse(verified);

            assertThat(parsed.unsupportedCount()).as(name).isZero();
            assertThat(parsed.updates()).as(name).singleElement().satisfies(update -> {
                assertThat(update.phoneNumberId()).isEqualTo(FIXTURES.get("phoneNumberId").stringValue());
                assertThat(update.providerMessageId()).isEqualTo(expected.get("providerMessageId").stringValue());
                assertThat(update.status()).isEqualTo(DeliveryStatus.valueOf(expected.get("status").stringValue()));
                assertThat(update.occurredAt()).isEqualTo(Instant.parse(expected.get("occurredAt").stringValue()));
                JsonNode failure = expected.get("failure");
                assertThat(update.failure()).isEqualTo(failure == null || failure.isNull() ? Optional.empty()
                        : Optional.of(FailureCategory.valueOf(failure.stringValue())));
            });
        }
    }

    /** The failed fixture carries canaries in every free text member; none of them may leave the parser. */
    @Test
    void errorTextsRecipientsAndConversationsNeverSurviveParsing() {
        JsonNode entry = FIXTURES.get("entries").get("failed");
        ParsedWebhook parsed = parser.parse(verified(entry.get("body").stringValue()));

        String rendered = parsed + " " + parsed.updates().getFirst();
        assertThat(rendered).doesNotContain("CANARY").doesNotContain("50600000001")
                .doesNotContain("wamid.").doesNotContain(PHONE);
        assertThat(parsed.updates().getFirst().failure()).contains(FailureCategory.INVALID_RECIPIENT);
    }

    @Test
    void aMemberOutsideTheAllowlistAtAnyLevelRefusesTheWholeBody() {
        String status = "{\"id\":\"wamid.X\",\"status\":\"sent\",\"timestamp\":\"1760000000\"}";
        List<String> hostile = List.of(
                "{\"tenantId\":\"x\",\"object\":\"whatsapp_business_account\",\"entry\":[]}",
                envelope("{\"id\":\"1\",\"tenantId\":\"x\",\"changes\":[]}"),
                envelope("{\"id\":\"1\",\"changes\":[{\"field\":\"messages\",\"tenantId\":\"x\",\"value\":{}}]}"),
                body("\"tenantId\":\"x\",", status),
                body("", "{\"id\":\"wamid.X\",\"status\":\"sent\",\"timestamp\":\"1760000000\","
                        + "\"tenantId\":\"x\"}"));

        for (String json : hostile) {
            assertThatThrownBy(() -> parser.parse(verified(json))).as(json)
                    .isInstanceOfSatisfying(WebhookPayloadException.class,
                            refusal -> assertThat(refusal.reason()).isEqualTo(Reason.UNEXPECTED_MEMBER));
        }
    }

    @Test
    void malformedOrMistypedBodiesAreRefusedWithoutEchoingThem() {
        List<String> invalid = List.of("[]", "{\"object\":\"page\",\"entry\":[]}",
                "{\"object\":\"whatsapp_business_account\"}",
                body("", "{\"id\":\"wamid.X\",\"status\":\"sent\",\"timestamp\":\"soon\"}"),
                body("", "{\"id\":\"wamid.X\",\"status\":\"sent\",\"timestamp\":1760000000}"),
                body("", "{\"status\":\"sent\",\"timestamp\":\"1760000000\"}"),
                body("", "{\"id\":\"" + "w".repeat(129) + "\",\"status\":\"sent\",\"timestamp\":\"1760000000\"}"),
                body("", "{\"id\":\" \",\"status\":\"sent\",\"timestamp\":\"1760000000\"}"),
                body("", "{\"id\":\"wamid.\\u0000\",\"status\":\"sent\",\"timestamp\":\"1760000000\"}"),
                "{\"object\":\"whatsapp_business_account\",\"object\":\"whatsapp_business_account\",\"entry\":[]}",
                envelope("{\"id\":\"1\",\"changes\":[{\"field\":\"messages\",\"value\":{\"messaging_product\":"
                        + "\"whatsapp\",\"metadata\":{\"phone_number_id\":\"12\"},\"statuses\":[]}}]}"));

        for (String json : invalid) {
            assertThatThrownBy(() -> parser.parse(verified(json))).as(json)
                    .isInstanceOf(WebhookPayloadException.class)
                    .satisfies(refusal -> assertThat(refusal.getMessage()).doesNotContain("wamid"))
                    .hasNoCause();
        }
        assertThatThrownBy(() -> parser.parse(verified("{not json"))).isInstanceOfSatisfying(
                WebhookPayloadException.class, refusal -> assertThat(refusal.reason()).isEqualTo(
                        Reason.MALFORMED_JSON));
    }

    @Test
    void otherFieldsInboundMessagesAndUntrackedStatusesAreCountedNotProcessed() {
        String json = envelope("{\"id\":\"1\",\"changes\":["
                + "{\"field\":\"account_update\",\"value\":{\"anything\":true}},"
                + "{\"field\":\"messages\",\"value\":{\"messaging_product\":\"whatsapp\",\"metadata\":"
                + "{\"display_phone_number\":\"50600000000\",\"phone_number_id\":\"" + PHONE + "\"},"
                + "\"contacts\":[{}],\"messages\":[{}],"
                + "\"statuses\":[{\"id\":\"wamid.X\",\"status\":\"played\",\"timestamp\":\"1760000000\"},"
                + "{\"id\":\"wamid.Y\",\"status\":\"READ\",\"timestamp\":\"1760000001\"}]}}]}");

        ParsedWebhook parsed = parser.parse(verified(json));

        assertThat(parsed.unsupportedCount()).isEqualTo(4);
        assertThat(parsed.updates()).extracting(StatusUpdate::status).containsExactly(DeliveryStatus.READ);
    }

    /** Error entries are not allowlisted: an unusable one is UNKNOWN and never loses the rest of the batch. */
    @Test
    void aFailedStatusWithoutAnUsableCodeIsUnknownAndOtherStatesCarryNoFailure() {
        String failed = "{\"id\":\"wamid.%s\",\"status\":\"failed\",\"timestamp\":\"1760000000\"%s}";
        ParsedWebhook parsed = parser.parse(verified(body("", String.join(",",
                failed.formatted("A", ""),
                failed.formatted("B", ",\"errors\":[{\"code\":\"131026\"}]"),
                failed.formatted("C", ",\"errors\":[\"not an object\"]"),
                failed.formatted("D", ",\"errors\":[null]"),
                failed.formatted("E", ",\"errors\":{\"code\":131026}"),
                failed.formatted("F", ",\"errors\":[]"),
                "{\"id\":\"wamid.G\",\"status\":\"delivered\",\"timestamp\":\"1760000000\","
                        + "\"errors\":[{\"code\":131026}]}"))));

        assertThat(parsed.updates()).extracting(StatusUpdate::failure).containsExactly(
                Optional.of(FailureCategory.UNKNOWN), Optional.of(FailureCategory.UNKNOWN),
                Optional.of(FailureCategory.UNKNOWN), Optional.of(FailureCategory.UNKNOWN),
                Optional.of(FailureCategory.UNKNOWN), Optional.of(FailureCategory.UNKNOWN), Optional.empty());
    }

    @Test
    void moreStatusesThanMetaEverBatchesAreRefused() {
        String statuses = IntStream.range(0, MetaWebhookPayloadParser.MAX_STATUSES + 1)
                .mapToObj(i -> "{\"id\":\"wamid." + i + "\",\"status\":\"sent\",\"timestamp\":\"1760000000\"}")
                .collect(Collectors.joining(","));

        assertThatThrownBy(() -> parser.parse(verified(body("", statuses))))
                .isInstanceOfSatisfying(WebhookPayloadException.class,
                        refusal -> assertThat(refusal.reason()).isEqualTo(Reason.TOO_MANY_STATUSES));
    }

    /** Untracked statuses count toward the bound too, and so do statuses split across several changes. */
    @Test
    void untrackedStatusesAndStatusesAcrossChangesCountTowardTheBound() {
        String untracked = IntStream.range(0, MetaWebhookPayloadParser.MAX_STATUSES + 1)
                .mapToObj(i -> "{\"id\":\"wamid." + i + "\",\"status\":\"warning\",\"timestamp\":\"1760000000\"}")
                .collect(Collectors.joining(","));
        String half = IntStream.range(0, MetaWebhookPayloadParser.MAX_STATUSES / 2 + 1)
                .mapToObj(i -> "{\"id\":\"wamid." + i + "\",\"status\":\"sent\",\"timestamp\":\"1760000000\"}")
                .collect(Collectors.joining(","));
        String change = "{\"field\":\"messages\",\"value\":{\"messaging_product\":\"whatsapp\",\"metadata\":"
                + "{\"phone_number_id\":\"" + PHONE + "\"},\"statuses\":[" + half + "]}}";
        String split = envelope("{\"id\":\"1\",\"changes\":[" + change + "," + change + "]}");

        for (String json : List.of(body("", untracked), split)) {
            assertThatThrownBy(() -> parser.parse(verified(json)))
                    .isInstanceOfSatisfying(WebhookPayloadException.class,
                            refusal -> assertThat(refusal.reason()).isEqualTo(Reason.TOO_MANY_STATUSES));
        }
    }

    private VerifiedWebhookBody verified(String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        return verifier.verify(bytes, List.of(WebhookSigning.sign(bytes,
                FIXTURES.get("signingKey").stringValue().toCharArray())));
    }

    private static String envelope(String entry) {
        return "{\"object\":\"whatsapp_business_account\",\"entry\":[" + entry + "]}";
    }

    private static String body(String extraValueMembers, String statuses) {
        return envelope("{\"id\":\"1\",\"changes\":[{\"field\":\"messages\",\"value\":{" + extraValueMembers
                + "\"messaging_product\":\"whatsapp\",\"metadata\":{\"display_phone_number\":\"50600000000\","
                + "\"phone_number_id\":\"" + PHONE + "\"},\"statuses\":[" + statuses + "]}}]}");
    }

    private static JsonNode load() {
        try (InputStream in = MetaWebhookPayloadParserTest.class
                .getResourceAsStream("/contracts/whatsapp/graph-webhook-events.json")) {
            return JsonMapper.builder().build().readTree(in);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
