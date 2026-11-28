// ABOUTME: What the JDBC admission store raises when undoing its savepoint fails too (ADR 0040 §7, spec 0024 AC-10):
// ABOUTME: an outage is raised as an outage whichever failure shows it, and any other first failure stands.
package io.github.stevdrey.dokene.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.stevdrey.dokene.admission.AdmissionDecision.Denied;
import io.github.stevdrey.dokene.database.DatabaseUnavailability;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.PlatformTransactionManager;

class JdbcAdmissionStoreTest {
    private static final Denied DENIED = new Denied(AdmissionDenialReason.TENANT_MINUTE, Duration.ofSeconds(1));

    private final DatabaseUnavailability database = new DatabaseUnavailability(Duration.ofSeconds(5));
    private final Connection connection = mock(Connection.class);
    private final Savepoint savepoint = mock(Savepoint.class);
    private JdbcAdmissionStore store;

    @BeforeEach
    void setUp() throws SQLException {
        when(connection.setSavepoint()).thenReturn(savepoint);
        store = new JdbcAdmissionStore(new JdbcTemplate(new SingleConnectionDataSource(connection, true)),
                mock(PlatformTransactionManager.class), database);
    }

    @Test
    void aFailureWhoseRollbackWorksIsRaisedAsItIsAndTheSavepointIsUndone() throws SQLException {
        Throwable raised = catchThrowable(() -> store.underSavepoint(session -> {
            throw lockTimeout();
        }));

        assertThat(database.isCause(raised)).isFalse();
        assertThat(raised).hasRootCauseMessage("lock timeout");
        verify(connection).rollback(savepoint);
    }

    @Test
    void aLockTimeoutFollowedByALostConnectionAtRollbackIsRaisedAsAnOutage() throws SQLException {
        doThrow(new SQLException("An I/O error occurred", "08006")).when(connection).rollback(savepoint);

        Throwable raised = catchThrowable(() -> store.underSavepoint(session -> {
            throw lockTimeout();
        }));

        assertThat(database.isCause(raised)).isTrue();
        assertThat(raised.getCause().getSuppressed()).singleElement()
                .satisfies(first -> assertThat(first).hasMessage("lock timeout"));
    }

    @Test
    void aFailingSwitchFollowedByAConnectionThePoolAlreadyClosedIsRaisedAsAnOutage() throws SQLException {
        theRollbackFindsTheConnectionClosedByThePool();

        Throwable raised = catchThrowable(() -> store.underSavepoint(session -> {
            throw new IllegalStateException("switch state missing");
        }));

        assertThat(database.isCause(raised)).isTrue();
        assertThat(raised).hasRootCauseMessage("Connection is closed");
        assertThat(raised.getCause().getSuppressed()).singleElement()
                .satisfies(first -> assertThat(first).hasMessage("switch state missing"));
    }

    @Test
    void anOutageWhoseRollbackAlsoFailsStaysTheFailureThatIsRaised() throws SQLException {
        theRollbackFindsTheConnectionClosedByThePool();

        Throwable raised = catchThrowable(() -> store.underSavepoint(session -> {
            throw new SQLException("terminating connection due to administrator command", "57P01");
        }));

        assertThat(database.isCause(raised)).isTrue();
        assertThat(raised).hasRootCauseMessage("terminating connection due to administrator command");
        assertThat(raised.getCause().getSuppressed()).singleElement()
                .satisfies(rollback -> assertThat(rollback).hasMessage("Connection is closed"));
    }

    @Test
    void aFailureWhoseRollbackFailsOnAnOpenConnectionKeepsTheFirstFailure() throws SQLException {
        doThrow(new SQLException("savepoint does not exist", "3B001")).when(connection).rollback(savepoint);

        Throwable raised = catchThrowable(() -> store.underSavepoint(session -> {
            throw lockTimeout();
        }));

        assertThat(database.isCause(raised)).isFalse();
        assertThat(raised).hasRootCauseMessage("lock timeout");
    }

    @Test
    void aDenialWhoseRollbackMeetsALostConnectionIsRaisedAsAnOutage() throws SQLException {
        theRollbackFindsTheConnectionClosedByThePool();

        Throwable raised = catchThrowable(() -> store.underSavepoint(session -> DENIED));

        assertThat(database.isCause(raised)).isTrue();
        assertThat(raised).hasRootCauseMessage("Connection is closed");
    }

    @Test
    void aDenialRollsBackToTheSavepointAndReleasesIt() throws SQLException {
        assertThat(store.underSavepoint(session -> DENIED)).isEqualTo(DENIED);

        verify(connection).rollback(savepoint);
        verify(connection).releaseSavepoint(savepoint);
        verify(connection, never()).rollback();
        verify(connection, never()).commit();
        verify(connection, never()).prepareStatement(any());
    }

    /** The connection is open when the store borrows it and closed from the failed rollback on, as with Hikari. */
    private void theRollbackFindsTheConnectionClosedByThePool() throws SQLException {
        doAnswer(call -> {
            when(connection.isClosed()).thenReturn(true);
            throw new SQLException("Connection is closed");
        }).when(connection).rollback(savepoint);
    }

    private static SQLException lockTimeout() {
        return new SQLException("lock timeout", "55P03");
    }
}
