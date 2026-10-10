// ABOUTME: Unit tests for MessageListQuery: the limit window and the defensive copy of the status filter.
// ABOUTME: Covers the bounds behind spec 0002 AC-8 (default 50, maximum 100, anything outside is invalid input).
package io.github.stevdrey.dokene.messaging.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.messaging.domain.MessageStatus;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MessageListQueryTest {

    @ParameterizedTest
    @ValueSource(ints = {1, 50, 100})
    void acceptsLimitsInsideTheWindow(int limit) {
        MessageListQuery query = new MessageListQuery(Set.of(), Optional.empty(), Optional.empty(), limit);

        assertThat(query.limit()).isEqualTo(limit);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 101, Integer.MAX_VALUE})
    void refusesLimitsOutsideTheWindow(int limit) {
        assertThatThrownBy(() -> new MessageListQuery(Set.of(), Optional.empty(), Optional.empty(), limit))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("100");
    }

    @Test
    void exposesTheDefaultAndMaximumFromAdr0009() {
        assertThat(MessageListQuery.DEFAULT_LIMIT).isEqualTo(50);
        assertThat(MessageListQuery.MAX_LIMIT).isEqualTo(100);
    }

    @Test
    void copiesTheStatusSetSoLaterMutationDoesNotLeakIn() {
        Set<MessageStatus> statuses = new HashSet<>(Set.of(MessageStatus.PENDING_APPROVAL));

        MessageListQuery query = new MessageListQuery(statuses, Optional.empty(), Optional.empty(), 10);
        statuses.add(MessageStatus.CANCELLED);

        assertThat(query.statuses()).containsExactly(MessageStatus.PENDING_APPROVAL);
        assertThatThrownBy(() -> query.statuses().add(MessageStatus.FAILED))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void refusesNullComponents() {
        assertThatThrownBy(() -> new MessageListQuery(null, Optional.empty(), Optional.empty(), 10))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MessageListQuery(Set.of(), null, Optional.empty(), 10))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MessageListQuery(Set.of(), Optional.empty(), null, 10))
                .isInstanceOf(NullPointerException.class);
    }
}
