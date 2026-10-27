// ABOUTME: Builds the Meta WhatsApp statuses callback bodies the webhook tests post, so every test shares one shape.
// ABOUTME: Error texts are the fixed canaries, so a leak check has known needles to search for.
package io.github.stevdrey.dokene.integration.testsupport;

import java.nio.charset.StandardCharsets;

public final class MetaCallbacks {
    private MetaCallbacks() {
    }

    /** A {@code messages} change for {@code phoneNumberId} of business account {@code wabaId} carrying the reports. */
    public static byte[] statuses(String wabaId, String phoneNumberId, String... reports) {
        return ("{\"object\":\"whatsapp_business_account\",\"entry\":[{\"id\":\"" + wabaId + "\",\"changes\":[{"
                + "\"field\":\"messages\",\"value\":{\"messaging_product\":\"whatsapp\",\"metadata\":{"
                + "\"display_phone_number\":\"50600000000\",\"phone_number_id\":\"" + phoneNumberId + "\"},"
                + "\"statuses\":[" + String.join(",", reports) + "]}}]}]}").getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The members live Meta status callbacks add (issue 289): recipient logical and user ids, conversation, pricing and
     * internal provider metadata a parser must accept and never read. Every value is synthetic.
     */
    public static final String LIVE_MEMBERS = ",\"recipient_logical_id\":\"SYNTHETIC-LOGICAL-ID-0001\""
            + ",\"recipient_user_id\":\"SYNTHETIC-USER-ID-0001\""
            + ",\"conversation\":{\"id\":\"SYNTHETIC-CONVERSATION-0001\",\"origin\":{\"type\":\"utility\"}}"
            + ",\"pricing\":{\"billable\":true,\"pricing_model\":\"PMP\",\"category\":\"utility\"}"
            + ",\"internal_1p_only_data\":{\"note\":\"SYNTHETIC-INTERNAL-0001\"}";

    /** One status report; a non-null {@code errorCode} adds an error whose title and message are the canaries. */
    public static String report(String providerMessageId, String state, long epochSeconds, Integer errorCode) {
        return report(providerMessageId, state, epochSeconds, errorCode, "");
    }

    /** One status report followed by {@code extraMembers}, each written as {@code ,"name":value}. */
    public static String report(String providerMessageId, String state, long epochSeconds, Integer errorCode,
            String extraMembers) {
        String errors = errorCode == null ? "" : ",\"errors\":[{\"code\":" + errorCode
                + ",\"title\":\"CANARY-TOKEN\",\"message\":\"CANARY-PHONE\"}]";
        return "{\"id\":\"" + providerMessageId + "\",\"status\":\"" + state + "\",\"timestamp\":\"" + epochSeconds
                + "\",\"recipient_id\":\"50600000001\"" + errors + extraMembers + "}";
    }

    /** One status report in the shape live Meta callbacks carry (issue 289). */
    public static String liveReport(String providerMessageId, String state, long epochSeconds, Integer errorCode) {
        return report(providerMessageId, state, epochSeconds, errorCode, LIVE_MEMBERS);
    }
}
