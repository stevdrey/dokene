// ABOUTME: Unit tests for MessageCursor: the opaque list cursor must round trip and reject anything it did not produce.
// ABOUTME: Covers spec 0002 AC-8 (cursor continuity and an invalid cursor being invalid input).
package io.github.stevdrey.dokene.messaging.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class MessageCursorTest {
    private static final Instant CREATED_AT = Instant.parse("2026-10-10T19:53:04.172599Z");
    private static final UUID MESSAGE_ID = UUID.fromString("8e8c2f9a-e417-4394-9676-80708f32503c");

    @Test
    void roundTripsThroughEncodeAndDecode() {
        MessageCursor cursor = new MessageCursor(CREATED_AT, MESSAGE_ID);

        assertThat(MessageCursor.decode(cursor.encode())).contains(cursor);
    }

    @Test
    void encodesAsUnpaddedBase64UrlOfInstantPipeId() {
        String encoded = new MessageCursor(CREATED_AT, MESSAGE_ID).encode();

        assertThat(encoded).doesNotContain("=", "+", "/");
        assertThat(new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8))
                .isEqualTo(CREATED_AT + "|" + MESSAGE_ID);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void decodesNothingFromNullOrBlank(String value) {
        assertThat(MessageCursor.decode(value)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"not base64 at all!", "fA", "MjAyNi0xMC0xMFQxOTo1MzowNFo", "fDhlOGMyZjlh"})
    void decodesNothingWhenTheSeparatorIsMissingOrAtAnEdge(String value) {
        // "fA" is "|", the third is an instant with no pipe, the last starts with the pipe.
        assertThat(MessageCursor.decode(value)).isEmpty();
    }

    @Test
    void decodesNothingWhenTheInstantIsNotParseable() {
        assertThat(MessageCursor.decode(encode("yesterday|" + MESSAGE_ID))).isEmpty();
    }

    @Test
    void decodesNothingWhenTheIdIsNotAUuid() {
        assertThat(MessageCursor.decode(encode(CREATED_AT + "|not-a-uuid"))).isEmpty();
    }

    @Test
    void decodesNothingWhenTheInstantEndsWithThePipe() {
        assertThat(MessageCursor.decode(encode(CREATED_AT + "|"))).isEmpty();
    }

    @Test
    void decodesNothingForAnInstantOutsideWhatTheColumnCanHold() {
        assertThat(MessageCursor.decode(encode("+1000000000-01-01T00:00:00Z|" + MESSAGE_ID))).isEmpty();
        assertThat(MessageCursor.decode(encode("+300000-01-01T00:00:00Z|" + MESSAGE_ID))).isEmpty();
        assertThat(MessageCursor.decode(encode("1969-12-31T23:59:59Z|" + MESSAGE_ID))).isEmpty();
    }

    @Test
    void decodesNothingForANonCanonicalEncodingOfAValidCursor() {
        String padded = Base64.getUrlEncoder().encodeToString((CREATED_AT + "|" + MESSAGE_ID).getBytes(StandardCharsets.UTF_8));
        String nonCanonicalInstant = encode("2026-10-10T19:53:04.172599000Z|" + MESSAGE_ID);

        assertThat(padded).endsWith("=");
        assertThat(MessageCursor.decode(padded)).isEmpty();
        assertThat(MessageCursor.decode(nonCanonicalInstant)).isEmpty();
    }

    @Test
    void refusesNullParts() {
        assertThatThrownBy(() -> new MessageCursor(null, MESSAGE_ID)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MessageCursor(CREATED_AT, null)).isInstanceOf(NullPointerException.class);
    }

    private static String encode(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
