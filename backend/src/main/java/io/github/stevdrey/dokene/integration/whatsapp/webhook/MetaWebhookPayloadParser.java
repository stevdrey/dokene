// ABOUTME: Strict parser for a verified Meta webhook body (issue 139, ADR 0030): every member is allowlisted per level,
// ABOUTME: anything else is a 400 before tenant resolution; only typed status facts leave, never texts or phones.
package io.github.stevdrey.dokene.integration.whatsapp.webhook;

import io.github.stevdrey.dokene.integration.whatsapp.GraphApiSendErrors;
import io.github.stevdrey.dokene.integration.whatsapp.webhook.ParsedWebhook.StatusUpdate;
import io.github.stevdrey.dokene.integration.whatsapp.webhook.WebhookPayloadException.Reason;
import io.github.stevdrey.dokene.integration.whatsapp.webhook.verify.VerifiedWebhookBody;
import io.github.stevdrey.dokene.messaging.domain.DeliveryStatus;
import io.github.stevdrey.dokene.messaging.domain.FailureCategory;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads only bytes the signature verifier released. A member outside the allowlist at the envelope, entry, change,
 * value or status level (a supplied {@code tenantId}, for one) refuses the whole body: Meta never sends it, so it is
 * not Meta. Change fields other than {@code messages}, inbound {@code messages}/{@code contacts}/{@code errors} and
 * status values other than the four delivery states are recognised but not processed, and only counted.
 *
 * <p>Error titles, messages and details, recipient phones, conversation and pricing members are never read into the
 * result: only the first error code is, and it becomes a closed {@link FailureCategory}.
 */
@Component
public class MetaWebhookPayloadParser {
    /** Meta documents batches of up to 1000 updates; anything larger is not Meta. */
    static final int MAX_STATUSES = 1000;
    private static final Pattern PHONE_NUMBER_ID = Pattern.compile("^[0-9]{5,32}$");
    private static final Pattern EPOCH_SECONDS = Pattern.compile("^[0-9]{1,12}$");
    private static final Set<String> ENVELOPE = Set.of("object", "entry");
    private static final Set<String> ENTRY = Set.of("id", "changes", "time");
    private static final Set<String> CHANGE = Set.of("field", "value");
    private static final Set<String> VALUE = Set.of("messaging_product", "metadata", "statuses", "messages",
            "contacts", "errors");
    private static final Set<String> METADATA = Set.of("display_phone_number", "phone_number_id");
    private static final Set<String> STATUS = Set.of("id", "status", "timestamp", "recipient_id", "recipient_type",
            "conversation", "pricing", "errors", "biz_opaque_callback_data");
    private static final Map<String, DeliveryStatus> DELIVERY_STATES = Map.of("sent", DeliveryStatus.SENT,
            "delivered", DeliveryStatus.DELIVERED, "read", DeliveryStatus.READ, "failed", DeliveryStatus.FAILED);

    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    public ParsedWebhook parse(VerifiedWebhookBody body) {
        JsonNode root;
        try {
            root = mapper.readTree(body.copy());
        } catch (JacksonException exception) {
            throw new WebhookPayloadException(Reason.MALFORMED_JSON);
        }
        if (root == null || !root.isObject()) {
            throw new WebhookPayloadException(Reason.SCHEMA);
        }
        allowOnly(root, ENVELOPE);
        if (!"whatsapp_business_account".equals(text(root, "object"))) {
            throw new WebhookPayloadException(Reason.SCHEMA);
        }
        Batch batch = new Batch();
        for (JsonNode entry : array(root, "entry", true)) {
            requireObject(entry);
            allowOnly(entry, ENTRY);
            for (JsonNode change : array(entry, "changes", true)) {
                requireObject(change);
                allowOnly(change, CHANGE);
                String field = text(change, "field");
                if (field == null || !change.has("value")) {
                    throw new WebhookPayloadException(Reason.SCHEMA);
                }
                if (!"messages".equals(field)) {
                    batch.unsupported++;
                    continue;
                }
                value(change.get("value"), batch);
            }
        }
        return new ParsedWebhook(batch.updates, batch.unsupported);
    }

    /** Adds the value's delivery statuses to the batch and counts every other item it carried. */
    private void value(JsonNode value, Batch batch) {
        requireObject(value);
        allowOnly(value, VALUE);
        for (String inbound : List.of("messages", "contacts", "errors")) {
            if (value.has(inbound)) {
                batch.unsupported += array(value, inbound, false).size();
            }
        }
        if (!value.has("statuses")) {
            return;
        }
        if (!"whatsapp".equals(text(value, "messaging_product"))) {
            throw new WebhookPayloadException(Reason.SCHEMA);
        }
        JsonNode metadata = value.get("metadata");
        requireObject(metadata);
        allowOnly(metadata, METADATA);
        String phoneNumberId = text(metadata, "phone_number_id");
        if (phoneNumberId == null || !PHONE_NUMBER_ID.matcher(phoneNumberId).matches()) {
            throw new WebhookPayloadException(Reason.SCHEMA);
        }
        List<JsonNode> statuses = array(value, "statuses", false);
        // Every status element counts toward the bound, tracked or not, before any of them is parsed.
        batch.statuses += statuses.size();
        if (batch.statuses > MAX_STATUSES) {
            throw new WebhookPayloadException(Reason.TOO_MANY_STATUSES);
        }
        for (JsonNode status : statuses) {
            Optional<StatusUpdate> update = status(phoneNumberId, status);
            if (update.isEmpty()) {
                batch.unsupported++;
                continue;
            }
            batch.updates.add(update.get());
        }
    }

    private Optional<StatusUpdate> status(String phoneNumberId, JsonNode status) {
        requireObject(status);
        allowOnly(status, STATUS);
        String id = text(status, "id");
        String state = text(status, "status");
        String timestamp = text(status, "timestamp");
        if (id == null || id.isBlank() || id.length() > StatusUpdate.MAX_PROVIDER_MESSAGE_ID_LENGTH
                || id.indexOf('\0') >= 0 || state == null || timestamp == null
                || !EPOCH_SECONDS.matcher(timestamp).matches()) {
            throw new WebhookPayloadException(Reason.SCHEMA);
        }
        DeliveryStatus delivery = DELIVERY_STATES.get(state.toLowerCase(Locale.ROOT));
        if (delivery == null) {
            // A status Meta may add later (or one this product does not track): acknowledged and counted, not applied.
            return Optional.empty();
        }
        Instant occurredAt;
        try {
            occurredAt = Instant.ofEpochSecond(Long.parseLong(timestamp));
        } catch (NumberFormatException | DateTimeException exception) {
            throw new WebhookPayloadException(Reason.SCHEMA);
        }
        Optional<FailureCategory> failure = delivery == DeliveryStatus.FAILED
                ? Optional.of(failureCategory(status))
                : Optional.empty();
        return Optional.of(new StatusUpdate(phoneNumberId, id, delivery, occurredAt, failure));
    }

    /** The first error's numeric code only; titles, messages and details may carry phones or bodies. */
    private FailureCategory failureCategory(JsonNode status) {
        if (!status.has("errors")) {
            return FailureCategory.UNKNOWN;
        }
        List<JsonNode> errors = array(status, "errors", false);
        if (errors.isEmpty()) {
            return FailureCategory.UNKNOWN;
        }
        JsonNode first = errors.getFirst();
        requireObject(first);
        JsonNode code = first.get("code");
        if (code == null || !code.isIntegralNumber() || !code.canConvertToInt()) {
            return FailureCategory.UNKNOWN;
        }
        return GraphApiSendErrors.categoryFor(code.intValue());
    }

    /** Accumulates one body's parse: the tracked updates, the untracked item count and every status seen. */
    private static final class Batch {
        private final List<StatusUpdate> updates = new ArrayList<>();
        private int unsupported;
        private int statuses;
    }

    private static void allowOnly(JsonNode node, Set<String> allowed) {
        for (String name : node.propertyNames()) {
            if (!allowed.contains(name)) {
                throw new WebhookPayloadException(Reason.UNEXPECTED_MEMBER);
            }
        }
    }

    private static void requireObject(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new WebhookPayloadException(Reason.SCHEMA);
        }
    }

    private static List<JsonNode> array(JsonNode node, String name, boolean required) {
        JsonNode array = node.get(name);
        if (array == null) {
            if (required) {
                throw new WebhookPayloadException(Reason.SCHEMA);
            }
            return List.of();
        }
        if (!array.isArray()) {
            throw new WebhookPayloadException(Reason.SCHEMA);
        }
        List<JsonNode> items = new ArrayList<>(array.size());
        array.forEach(items::add);
        return items;
    }

    private static String text(JsonNode node, String name) {
        JsonNode member = node.get(name);
        return member != null && member.isString() ? member.stringValue() : null;
    }
}
