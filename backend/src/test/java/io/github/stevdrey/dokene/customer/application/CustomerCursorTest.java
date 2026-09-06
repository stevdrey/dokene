package io.github.stevdrey.dokene.customer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CustomerCursorTest {

    @Test
    void encodesAndDecodesRoundTrip() {
        Instant timestamp = Instant.parse("2026-09-06T12:34:56.789123Z");
        UUID id = UUID.randomUUID();
        CustomerCursor cursor = new CustomerCursor(timestamp, id);

        String encoded = cursor.encode();
        CustomerCursor decoded = CustomerCursor.decode(encoded);

        assertThat(decoded.createdAt()).isEqualTo(timestamp);
        assertThat(decoded.id()).isEqualTo(id);
    }

    @Test
    void rejectsInvalidBase64() {
        assertThatThrownBy(() -> CustomerCursor.decode("%%%not-base64%%%"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid customer cursor");
    }

    @Test
    void rejectsMissingOrExtraDelimiters() {
        String noDelimiter = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("2026-09-06T12:00:00Z".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> CustomerCursor.decode(noDelimiter))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid customer cursor");

        String threeParts = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("2026-09-06T12:00:00Z|" + UUID.randomUUID() + "|extra").getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> CustomerCursor.decode(threeParts))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid customer cursor");
    }

    @Test
    void rejectsInvalidTimestampOrUuid() {
        String invalidTimestamp = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("not-a-timestamp|" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> CustomerCursor.decode(invalidTimestamp))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid customer cursor");

        String invalidUuid = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("2026-09-06T12:00:00Z|not-a-uuid".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> CustomerCursor.decode(invalidUuid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid customer cursor");
    }
}
