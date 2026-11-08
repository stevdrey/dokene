// ABOUTME: Unit checks of the signing-key callback configuration (issue 324): the retirement grace is bounded and
// ABOUTME: configuration and database errors never echo key material, and a cleanup failure never hides the cause.
package io.github.stevdrey.dokene.tenant.persistence.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.junit.jupiter.api.Test;

class TenantContextSigningKeyCallbackTest {
    private static final String KEY = "a".repeat(64);

    @Test
    void acceptsAGraceFromZeroToSevenDays() {
        assertThatCode(() -> new TenantContextSigningKeyCallback(KEY, "k1", Duration.ZERO)).doesNotThrowAnyException();
        assertThatCode(() -> new TenantContextSigningKeyCallback(KEY, "k1", Duration.ofDays(7)))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsANegativeMissingOrUnboundedGrace() {
        for (Duration grace : new Duration[] {Duration.ofSeconds(-1), Duration.ofDays(7).plusSeconds(1), null}) {
            assertThatThrownBy(() -> new TenantContextSigningKeyCallback(KEY, "k1", grace))
                    .as(String.valueOf(grace))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("DOKENE_TENANT_CONTEXT_KEY_RETIREMENT_GRACE");
        }
    }

    @Test
    void rejectsAMalformedKeyWithoutEchoingIt() {
        String malformed = "b".repeat(63);
        assertThatThrownBy(() -> new TenantContextSigningKeyCallback(malformed, "k1", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(malformed);
    }

    @Test
    void aDatabaseFailureKeepsOnlyTheSqlStateAndNeverTheDriverDetail() throws SQLException {
        Connection connection = mock(Connection.class);
        when(connection.getAutoCommit()).thenReturn(false);
        when(connection.prepareStatement(anyString())).thenThrow(new SQLException(
                "ERROR: new row violates check constraint. Detail: Failing row contains (k1, \\x" + KEY + ").",
                "23514"));

        assertThatThrownBy(() -> callback().handle(Event.AFTER_MIGRATE, context(connection)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("23514")
                .satisfies(failure -> assertThat(messages(failure)).noneMatch(message -> message.contains(KEY)));
    }

    @Test
    void aFailedRollbackNeverReplacesTheOriginalFailure() throws SQLException {
        Connection connection = mock(Connection.class);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(anyString())).thenThrow(new SQLException("statement failed " + KEY, "40001"));
        doThrow(new SQLException("rollback failed " + KEY, "08006")).when(connection).rollback();
        doThrow(new SQLException("restore failed " + KEY, "08006")).when(connection).setAutoCommit(true);

        assertThatThrownBy(() -> callback().handle(Event.AFTER_MIGRATE, context(connection)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("40001")
                .satisfies(failure -> {
                    assertThat(failure.getSuppressed()).hasSize(2);
                    assertThat(messages(failure)).noneMatch(message -> message.contains(KEY));
                });
    }

    private static TenantContextSigningKeyCallback callback() {
        return new TenantContextSigningKeyCallback(KEY, "k1", Duration.ofHours(1));
    }

    private static Context context(Connection connection) {
        Context context = mock(Context.class);
        when(context.getConnection()).thenReturn(connection);
        return context;
    }

    /** Every message in the failure, its causes and its suppressed exceptions, recursively. */
    private static List<String> messages(Throwable failure) {
        List<String> messages = new ArrayList<>();
        collect(failure, messages);
        return messages;
    }

    private static void collect(Throwable failure, List<String> messages) {
        if (failure == null) {
            return;
        }
        messages.add(String.valueOf(failure.getMessage()));
        messages.add(String.valueOf(failure));
        for (Throwable suppressed : failure.getSuppressed()) {
            collect(suppressed, messages);
        }
        collect(failure.getCause(), messages);
    }
}
