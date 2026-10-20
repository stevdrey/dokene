// ABOUTME: Proves the checked in WhatsApp fixture files agree with themselves, with the domain types and with their README (spec 0009).
// ABOUTME: Offline and deterministic (AC-10): no server, no Spring context, no clock; every signature recomputes through WebhookSigning.
package io.github.stevdrey.dokene.integration.whatsapp;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import io.github.stevdrey.dokene.integration.testsupport.SecretCanaries;
import io.github.stevdrey.dokene.integration.testsupport.WebhookSigning;
import io.github.stevdrey.dokene.messaging.domain.DeliveryStatus;
import io.github.stevdrey.dokene.messaging.domain.DeliveryStatusReport;
import io.github.stevdrey.dokene.messaging.domain.FailureCategory;
import io.github.stevdrey.dokene.messaging.domain.MessageStatus;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class WhatsAppProviderFixturesTest {
    private static final String FIXTURES = "/contracts/whatsapp/";
    private static final String README = "README.md";
    private static final String WEBHOOK_SECTION = "## Webhook fixtures";
    private static final String SIGNING_KEY = "SYNTHETIC-APP-SECRET-NOT-REAL-0001";
    private static final String PHONE_NUMBER_ID = "100000000000001";
    private static final String BUSINESS_ACCOUNT_ID = "200000000000001";
    private static final String DISPLAY_PHONE_NUMBER = "50600000000";
    private static final String RECIPIENT = "50600000001";
    private static final Set<String> RECIPIENT_FORMS = Set.of(RECIPIENT, "+" + RECIPIENT);
    private static final Set<String> RECIPIENT_FIELDS = Set.of("recipient_id", "wa_id", "input");
    private static final String SYNTHETIC_MESSAGE_ID_PREFIX = "wamid.SYNTHETIC";
    private static final String REAL_MESSAGE_ID_PREFIX = "wamid.HBg";
    private static final List<String> WEBHOOK_IDS = List.of("delivered", "failed", "duplicateOfDelivered",
            "readBeforeDelivered", "deliveredAfterRead");
    private static final Set<String> HANDLINGS = Set.of("applied", "deduplicated", "ignoredTerminal");
    private static final String FAILED_ERROR = "entry[0].changes[0].value.statuses[0].errors[0]";
    private static final Set<String> CANARY_FIELDS = Set.of(FAILED_ERROR + ".title", FAILED_ERROR + ".message",
            FAILED_ERROR + ".error_data.details");
    private static final Pattern SIGNATURE = Pattern.compile("sha256=[0-9a-f]{64}");
    /** A fixture id is the backticked first column of a README table row. */
    private static final Pattern README_ID = Pattern.compile("^\\| `([A-Za-z0-9]+)` \\|", Pattern.MULTILINE);
    /** The webhook table of spec 0009: handling, after, statusBefore and failure per entry. */
    private static final Map<String, Expectation> WEBHOOK_TABLE = Map.of(
            "delivered", new Expectation("applied", null, MessageStatus.SENT, null),
            "duplicateOfDelivered", new Expectation("deduplicated", "delivered", MessageStatus.DELIVERED, null),
            "failed", new Expectation("applied", null, MessageStatus.SENT, FailureCategory.INVALID_RECIPIENT),
            "readBeforeDelivered", new Expectation("applied", null, MessageStatus.SENT, null),
            "deliveredAfterRead", new Expectation("ignoredTerminal", "readBeforeDelivered", MessageStatus.READ, null));

    /** Strict on duplicate keys so a repeated fixture id fails to parse instead of silently keeping the last value. */
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private final JsonNode webhooks = load("graph-webhook-events.json");
    private final JsonNode sends = load("graph-send-responses.json");

    private record Expectation(String handling, String after, MessageStatus statusBefore, FailureCategory failure) {
    }

    /** covers: AC-8 */
    @Test
    void theMapperRefusesADuplicatedKey() {
        assertThatThrownBy(() -> JSON.readTree("{\"accepted\":1,\"accepted\":2}"))
                .hasMessageContaining("Duplicate");
    }

    /** covers: AC-1 */
    @Test
    void theWebhookFileCarriesThePublishedKeyAndExactlyTheFiveEntries() {
        assertThat(webhooks.propertyNames()).containsExactly("signingKey", "phoneNumberId", "entries");
        assertThat(webhooks.path("signingKey").asString()).isEqualTo(SIGNING_KEY);
        assertThat(webhooks.path("phoneNumberId").asString()).isEqualTo(PHONE_NUMBER_ID);
        assertThat(webhooks.path("entries").propertyNames()).containsExactlyInAnyOrderElementsOf(WEBHOOK_IDS);
    }

    /** covers: AC-1, AC-2, AC-3, AC-4 */
    @TestFactory
    Stream<DynamicTest> everyWebhookEntryIsSignedParsesAsAStatusesCallbackAndMatchesItsExpectation() {
        return WEBHOOK_IDS.stream().map(id -> dynamicTest("webhook." + id, () -> {
            JsonNode entry = webhooks.path("entries").path(id);
            assertThat(entry.propertyNames()).containsExactly("signature", "body", "expected");
            assertThat(entry.path("body").isString()).as("body is one JSON string of the exact callback bytes").isTrue();
            String body = entry.path("body").asString();
            String signature = entry.path("signature").asString();
            String recomputed = WebhookSigning.sign(body.getBytes(UTF_8), SIGNING_KEY.toCharArray());
            assertThat(signature).as("signature of %s does not recompute; the body signs to %s", id, recomputed)
                    .isEqualTo(recomputed);
            assertThat(signature).matches(SIGNATURE);

            JsonNode status = statusOf(id, body);
            JsonNode expected = entry.path("expected");
            assertThat(expected.propertyNames()).containsExactly("providerMessageId", "status", "occurredAt",
                    "failure", "handling", "after", "statusBefore");
            String providerMessageId = expected.path("providerMessageId").asString();
            assertThat(providerMessageId).isEqualTo(status.path("id").asString());
            DeliveryStatus deliveryStatus = DeliveryStatus.valueOf(expected.path("status").asString());
            assertThat(deliveryStatus.name()).isEqualTo(status.path("status").asString().toUpperCase(Locale.ROOT));
            Instant occurredAt = Instant.parse(expected.path("occurredAt").asString());
            assertThat(occurredAt).isEqualTo(Instant.ofEpochSecond(Long.parseLong(status.path("timestamp").asString())));
            Optional<FailureCategory> failure = expected.path("failure").isNull()
                    ? Optional.empty()
                    : Optional.of(FailureCategory.valueOf(expected.path("failure").asString()));
            assertThat(new DeliveryStatusReport(providerMessageId, deliveryStatus, occurredAt, failure)).isNotNull();
            assertThat(expected.path("handling").asString()).isIn(HANDLINGS);
            assertThat(expected.path("after").isNull() || WEBHOOK_IDS.contains(expected.path("after").asString()))
                    .as("after names an entry or is null").isTrue();
            MessageStatus.valueOf(expected.path("statusBefore").asString());
        }));
    }

    /** covers: AC-5 */
    @TestFactory
    Stream<DynamicTest> theWebhookEntriesRelateToEachOtherAsTheTableSays() {
        JsonNode entries = webhooks.path("entries");
        return Stream.of(
                dynamicTest("a. duplicateOfDelivered is byte identical to delivered, signature included", () -> {
                    assertThat(entries.path("duplicateOfDelivered").path("body").asString().getBytes(UTF_8))
                            .isEqualTo(entries.path("delivered").path("body").asString().getBytes(UTF_8));
                    assertThat(entries.path("duplicateOfDelivered").path("signature"))
                            .isEqualTo(entries.path("delivered").path("signature"));
                }),
                dynamicTest("b. the ordering pair shares one provider message id", () -> assertThat(
                        messageId(entries, "readBeforeDelivered")).isEqualTo(messageId(entries, "deliveredAfterRead"))),
                dynamicTest("c. delivered, failed and the pair use three distinct provider message ids", () -> assertThat(
                        Set.of(messageId(entries, "delivered"), messageId(entries, "failed"),
                                messageId(entries, "readBeforeDelivered"))).hasSize(3)),
                dynamicTest("d. the late delivered report carries an earlier timestamp than the read report", () -> assertThat(
                        occurredAt(entries, "deliveredAfterRead")).isBefore(occurredAt(entries, "readBeforeDelivered"))),
                dynamicTest("e. handling, after, statusBefore and failure match the spec table", () -> WEBHOOK_IDS
                        .forEach(id -> {
                            Expectation row = WEBHOOK_TABLE.get(id);
                            JsonNode expected = entries.path(id).path("expected");
                            assertThat(expected.path("handling").asString()).as("%s.handling", id).isEqualTo(row.handling());
                            assertThat(expected.path("after").isNull() ? null : expected.path("after").asString())
                                    .as("%s.after", id).isEqualTo(row.after());
                            assertThat(expected.path("statusBefore").asString()).as("%s.statusBefore", id)
                                    .isEqualTo(row.statusBefore().name());
                            assertThat(expected.path("failure").isNull() ? null : expected.path("failure").asString())
                                    .as("%s.failure", id).isEqualTo(row.failure() == null ? null : row.failure().name());
                        })));
    }

    /** covers: AC-6 */
    @TestFactory
    Stream<DynamicTest> theFixedCanariesSitOnlyInTheNamedFieldsOfTheFailedEvent() {
        JsonNode entries = webhooks.path("entries");
        var tests = new ArrayList<DynamicTest>();
        tests.add(dynamicTest("fixedNeedles lists the three literals", () -> assertThat(SecretCanaries.fixedNeedles())
                .containsExactly(SecretCanaries.TOKEN, SecretCanaries.PHONE, SecretCanaries.BODY)));
        tests.add(dynamicTest("failed.errors[0].title carries the token canary", () -> assertThat(
                failedError().path("title").asString()).contains(SecretCanaries.TOKEN)));
        tests.add(dynamicTest("failed.errors[0].message carries the phone canary", () -> assertThat(
                failedError().path("message").asString()).contains(SecretCanaries.PHONE)));
        tests.add(dynamicTest("failed.errors[0].error_data.details carries the body canary", () -> assertThat(
                failedError().path("error_data").path("details").asString()).contains(SecretCanaries.BODY)));
        tests.add(dynamicTest("failed carries canaries in no other field", () -> {
            var strays = new ArrayList<String>();
            strings(parse(entries.path("failed").path("body").asString()), "", (path, value) -> {
                if (!CANARY_FIELDS.contains(path) && SecretCanaries.containsAny(value, SecretCanaries.fixedNeedles())) {
                    strays.add(path);
                }
            });
            assertThat(strays).as("failed body fields carrying a canary outside the three named ones").isEmpty();
        }));
        WEBHOOK_IDS.stream().filter(id -> !id.equals("failed")).forEach(id -> tests.add(dynamicTest(
                "webhook." + id + " carries no canary", () -> assertThat(SecretCanaries.containsAny(
                        entries.path(id).path("body").asString(), SecretCanaries.fixedNeedles())).isFalse())));
        return tests.stream();
    }

    /** covers: AC-8 */
    @Test
    void everySendEntryIsAStatusAndAnObjectBody() {
        assertThat(sends.size()).isEqualTo(21);
        sends.properties().forEach(entry -> {
            assertThat(entry.getValue().propertyNames()).as(entry.getKey()).containsExactly("status", "body");
            JsonNode status = entry.getValue().path("status");
            assertThat(status.isIntegralNumber()).as("%s.status is an integer", entry.getKey()).isTrue();
            assertThat(status.intValue()).as("%s.status", entry.getKey()).isBetween(200, 599);
            assertThat(entry.getValue().path("body").isObject()).as("%s.body is an object", entry.getKey()).isTrue();
        });
    }

    /** covers: AC-7 */
    @Test
    void theCanarySendEntryEchoesTheThreeLiteralsInItsNamedFields() {
        JsonNode entry = sends.path("tokenExpiredWithCanaries");
        assertThat(entry.path("status").intValue()).isEqualTo(401);
        JsonNode error = entry.path("body").path("error");
        assertThat(error.path("message").asString())
                .isEqualTo("Error validating access token CANARY-TOKEN for CANARY-PHONE: Session has expired");
        assertThat(error.path("code").intValue()).isEqualTo(190);
        assertThat(error.path("type").asString()).isEqualTo("OAuthException");
        assertThat(error.path("error_data").path("details").asString())
                .isEqualTo("Authorization: Bearer CANARY-TOKEN; text=CANARY-BODY");
        assertThat(error.path("fbtrace_id").asString()).isEqualTo("SYNTHETIC-TRACE");
    }

    /** covers: AC-8, AC-9 */
    @TestFactory
    Stream<DynamicTest> eachReadmeTableNamesEveryFixtureIdOfItsOwnFileAndNothingElse() {
        String readme = readme();
        int split = readme.indexOf(WEBHOOK_SECTION);
        assertThat(split).as("the README has a %s section", WEBHOOK_SECTION).isPositive();
        return Stream.of(
                dynamicTest("send table ids equal graph-send-responses.json", () -> assertThat(
                        documentedIds(readme.substring(0, split)))
                        .containsExactlyInAnyOrderElementsOf(sends.propertyNames())),
                dynamicTest("webhook table ids equal graph-webhook-events.json", () -> assertThat(
                        documentedIds(readme.substring(split)))
                        .containsExactlyInAnyOrderElementsOf(webhooks.path("entries").propertyNames())));
    }

    private static Set<String> documentedIds(String section) {
        var documented = new LinkedHashSet<String>();
        Matcher rows = README_ID.matcher(section);
        while (rows.find()) {
            documented.add(rows.group(1));
        }
        return documented;
    }

    /** covers: AC-11 */
    @TestFactory
    Stream<DynamicTest> everythingInBothFilesIsSynthetic() {
        var bodies = new LinkedHashMap<String, JsonNode>();
        sends.properties().forEach(entry -> bodies.put("send." + entry.getKey(), entry.getValue().path("body")));
        webhooks.path("entries").properties().forEach(entry -> bodies.put("webhook." + entry.getKey(),
                parse(entry.getValue().path("body").asString())));
        var tests = new ArrayList<DynamicTest>();
        tests.add(dynamicTest("the signing key and every string id carry SYNTHETIC or CANARY", () -> {
            assertThat(SIGNING_KEY).contains("SYNTHETIC");
            bodies.forEach((name, body) -> strings(body, "", (path, value) -> {
                if (path.endsWith("fbtrace_id") || path.endsWith("conversation.id")) {
                    assertThat(value).as("%s %s", name, path).containsAnyOf("SYNTHETIC", "CANARY");
                }
            }));
        }));
        bodies.forEach((name, body) -> tests.add(dynamicTest(name + " is synthetic", () -> {
            assertThat(body.toString()).doesNotContain(REAL_MESSAGE_ID_PREFIX);
            strings(body, "", (path, value) -> {
                if (value.startsWith("wamid.")) {
                    assertThat(value).as("%s %s", name, path).startsWith(SYNTHETIC_MESSAGE_ID_PREFIX);
                }
                String field = path.substring(path.lastIndexOf('.') + 1);
                if (RECIPIENT_FIELDS.contains(field)) {
                    assertThat(value).as("%s %s", name, path).isIn(RECIPIENT_FORMS);
                }
            });
        })));
        return tests.stream();
    }

    /** The one statuses element of a callback body, after the AC-3 shape checks. */
    private JsonNode statusOf(String id, String body) {
        JsonNode root = parse(body);
        assertThat(root.path("object").asString()).isEqualTo("whatsapp_business_account");
        assertThat(root.path("entry").size()).isEqualTo(1);
        JsonNode entry = root.path("entry").path(0);
        assertThat(entry.path("id").asString()).isEqualTo(BUSINESS_ACCOUNT_ID);
        assertThat(entry.path("changes").size()).isEqualTo(1);
        JsonNode change = entry.path("changes").path(0);
        assertThat(change.path("field").asString()).isEqualTo("messages");
        JsonNode value = change.path("value");
        assertThat(value.path("messaging_product").asString()).isEqualTo("whatsapp");
        assertThat(value.path("metadata").propertyNames()).containsExactly("display_phone_number", "phone_number_id");
        assertThat(value.path("metadata").path("display_phone_number").asString()).isEqualTo(DISPLAY_PHONE_NUMBER);
        assertThat(value.path("metadata").path("phone_number_id").asString())
                .isEqualTo(webhooks.path("phoneNumberId").asString());
        assertThat(value.path("statuses").size()).isEqualTo(1);
        JsonNode status = value.path("statuses").path(0);
        var members = new ArrayList<>(List.of("id", "status", "timestamp", "recipient_id"));
        if (id.equals("delivered") || id.equals("duplicateOfDelivered")) {
            members.add("conversation");
            assertThat(status.path("conversation").toString())
                    .isEqualTo("{\"id\":\"SYNTHETIC-CONVERSATION-0001\",\"origin\":{\"type\":\"utility\"}}");
        }
        if (id.equals("failed")) {
            members.add("errors");
            assertThat(status.path("errors").size()).isEqualTo(1);
            JsonNode error = status.path("errors").path(0);
            assertThat(error.propertyNames()).containsExactly("code", "title", "message", "error_data");
            assertThat(error.path("code").intValue()).isEqualTo(131026);
            assertThat(error.path("title").asString()).isEqualTo("Message undeliverable CANARY-TOKEN");
            assertThat(error.path("message").asString()).isEqualTo("Recipient CANARY-PHONE cannot receive messages");
            assertThat(error.path("error_data").propertyNames()).containsExactly("details");
            assertThat(error.path("error_data").path("details").asString()).isEqualTo("CANARY-BODY");
        }
        assertThat(status.propertyNames()).containsExactlyInAnyOrderElementsOf(members);
        assertThat(status.path("timestamp").isString()).as("timestamp is a string of epoch seconds").isTrue();
        assertThat(status.path("recipient_id").asString()).isEqualTo(RECIPIENT);
        return status;
    }

    /** {@code errors[0]} of the failed callback, located without the AC-3 shape assertions so each canary test fails on its own. */
    private JsonNode failedError() {
        return parse(webhooks.path("entries").path("failed").path("body").asString()).path("entry").path(0)
                .path("changes").path(0).path("value").path("statuses").path(0).path("errors").path(0);
    }

    private static String messageId(JsonNode entries, String id) {
        return entries.path(id).path("expected").path("providerMessageId").asString();
    }

    private static Instant occurredAt(JsonNode entries, String id) {
        return Instant.parse(entries.path(id).path("expected").path("occurredAt").asString());
    }

    private static void strings(JsonNode node, String path, BiConsumer<String, String> visitor) {
        if (node.isString()) {
            visitor.accept(path, node.asString());
        } else if (node.isObject()) {
            node.properties().forEach(entry -> strings(entry.getValue(), path.isEmpty() ? entry.getKey()
                    : path + "." + entry.getKey(), visitor));
        } else if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                strings(node.get(index), path + "[" + index + "]", visitor);
            }
        }
    }

    private static JsonNode parse(String body) {
        return JSON.readTree(body);
    }

    private static JsonNode load(String file) {
        try (InputStream in = WhatsAppProviderFixturesTest.class.getResourceAsStream(FIXTURES + file)) {
            assertThat(in).as(file).isNotNull();
            return JSON.readTree(in);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static String readme() {
        try (InputStream in = WhatsAppProviderFixturesTest.class.getResourceAsStream(FIXTURES + README)) {
            assertThat(in).as(README).isNotNull();
            return new String(in.readAllBytes(), UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
