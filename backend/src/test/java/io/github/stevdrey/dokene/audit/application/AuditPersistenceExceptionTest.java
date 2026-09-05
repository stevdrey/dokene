package io.github.stevdrey.dokene.audit.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AuditPersistenceExceptionTest {

    @Test
    void exposesOnlyTheGenericMessageWithoutCauseSuppressionOrStackTrace() {
        AuditPersistenceException exception = new AuditPersistenceException();

        assertThat(exception)
                .hasMessage("Audit persistence unavailable")
                .hasNoCause();
        assertThat(exception.getStackTrace()).isEmpty();
        assertThat(exception.getSuppressed()).isEmpty();
    }
}
