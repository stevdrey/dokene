package io.github.stevdrey.dokene.followup.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class FollowUpQueueCursorTest {

    @Test
    void encodesAndDecodesRoundtrip() {
        LocalDate date = LocalDate.of(2026, 9, 11);
        UUID customerId = UUID.randomUUID();
        FollowUpQueueCursor cursor = new FollowUpQueueCursor(date, customerId);

        String encoded = cursor.encode();
        assertThat(encoded).isNotBlank();

        FollowUpQueueCursor decoded = FollowUpQueueCursor.decode(encoded);
        assertThat(decoded).isEqualTo(cursor);
        assertThat(decoded.dueDate()).isEqualTo(date);
        assertThat(decoded.customerId()).isEqualTo(customerId);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "not-base64-!@#", "invalid", "MjAyNi0wOS0xMQ", "MjAyNi0wOS0xMXxub3QtdXVpZA"})
    void rejectsInvalidCursorStrings(String value) {
        assertThatThrownBy(() -> FollowUpQueueCursor.decode(value))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid queue cursor");
    }

    @Test
    void rejectsNullArguments() {
        assertThatThrownBy(() -> new FollowUpQueueCursor(null, UUID.randomUUID()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new FollowUpQueueCursor(LocalDate.now(), null))
                .isInstanceOf(NullPointerException.class);
    }
}
