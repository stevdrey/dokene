// ABOUTME: Every list cursor decodes client input with java.util.Base64 (the Java4Shell path, issue 429).
// ABOUTME: Checks that well-formed cursors round-trip and malformed ones are refused as invalid input, nothing else.
package io.github.stevdrey.dokene.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.customer.application.ContactPolicyCursor;
import io.github.stevdrey.dokene.customer.application.CustomerCursor;
import io.github.stevdrey.dokene.followup.application.FollowUpQueueCursor;
import io.github.stevdrey.dokene.messaging.application.MessageCursor;
import io.github.stevdrey.dokene.purchase.application.PurchaseCursor;
import io.github.stevdrey.dokene.purchase.application.PurchaseEventCursor;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ClientCursorDecodingTest {

    private static final Instant AT = Instant.parse("2026-11-20T10:15:30.123456Z");
    private static final UUID ID = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");

    private record Codec(String name, String encoded, Function<String, Object> decode, Object expected) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static List<Codec> codecs() {
        CustomerCursor customer = new CustomerCursor(AT, ID);
        ContactPolicyCursor policy = new ContactPolicyCursor(AT, ID);
        PurchaseCursor purchase = new PurchaseCursor(AT, ID);
        PurchaseEventCursor purchaseEvent = new PurchaseEventCursor(AT, ID);
        MessageCursor message = new MessageCursor(AT, ID);
        FollowUpQueueCursor queue = new FollowUpQueueCursor(LocalDate.of(2026, 11, 20), ID);
        return List.of(
                new Codec("CustomerCursor", customer.encode(), CustomerCursor::decode, customer),
                new Codec("ContactPolicyCursor", policy.encode(), ContactPolicyCursor::decode, policy),
                new Codec("PurchaseCursor", purchase.encode(), PurchaseCursor::decode, purchase),
                new Codec("PurchaseEventCursor", purchaseEvent.encode(), PurchaseEventCursor::decode, purchaseEvent),
                new Codec("MessageCursor", message.encode(), MessageCursor::decode, message),
                new Codec("FollowUpQueueCursor", queue.encode(), FollowUpQueueCursor::decode, queue));
    }

    private static String b64(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    /** Ordinary malformed client input: wrong alphabet, bad length or padding, and well-formed Base64 of bad content. */
    private static List<String> malformed() {
        return List.of(
                " ",
                "a",
                "!!!!",
                "+/+/",
                "YQ=",
                "====",
                "%2B%2F",
                "YWJj\nZGVm",
                b64("no separator"),
                b64("x|y"),
                b64("2026-11-20T10:15:30Z|" + ID + "|extra"),
                b64("2026-11-20|not-a-uuid"),
                b64("|"),
                "A".repeat(64 * 1024),
                "_-".repeat(16 * 1024));
    }

    static Stream<Codec> roundTrips() {
        return codecs().stream();
    }

    static Stream<Arguments> malformedInputs() {
        List<Arguments> cases = new ArrayList<>();
        for (Codec codec : codecs()) {
            cases.add(Arguments.of(codec, null));
            for (String input : malformed()) {
                cases.add(Arguments.of(codec, input));
            }
            // A valid cursor with one character outside the URL-safe alphabet appended.
            cases.add(Arguments.of(codec, codec.encoded() + "*"));
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} round-trips")
    @MethodSource("roundTrips")
    void wellFormedCursorRoundTrips(Codec codec) {
        assertThat(codec.decode().apply(codec.encoded())).isEqualTo(codec.expected());
    }

    @ParameterizedTest(name = "{0} refuses malformed input #{index}")
    @MethodSource("malformedInputs")
    void malformedCursorIsInvalidInput(Codec codec, String input) {
        assertThatThrownBy(() -> codec.decode().apply(input))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Invalid ");
    }
}
