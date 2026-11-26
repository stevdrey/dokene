package io.github.stevdrey.dokene.followup.persistence.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.followup.domain.FollowUpCandidate;
import io.github.stevdrey.dokene.followup.domain.FollowUpTimingSource;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JdbcFollowUpCandidateRepositoryOrderTest {
    private static final LocalDate TODAY = LocalDate.parse("2026-11-26");

    @Test
    void ordersEqualDueDatesByUnsignedUuidBytesLikePostgreSql() {
        // UUID.compareTo would put the 0x80… id first because it compares signed halves.
        UUID low = UUID.fromString("7fffffff-0000-4000-8000-000000000000");
        UUID high = UUID.fromString("80000000-0000-4000-8000-000000000000");
        UUID lowTail = UUID.fromString("00000000-0000-4000-7fff-ffffffffffff");
        UUID highTail = UUID.fromString("00000000-0000-4000-8000-000000000000");
        FollowUpCandidate earlier = candidate(TODAY.minusDays(1), high);

        List<FollowUpCandidate> sorted = List.of(candidate(TODAY, high), candidate(TODAY, highTail), earlier,
                candidate(TODAY, low), candidate(TODAY, lowTail)).stream()
                .sorted(JdbcFollowUpCandidateRepository.DUE_ORDER).toList();

        assertThat(sorted).extracting(candidate -> candidate.customerId().value())
                .containsExactly(high, lowTail, highTail, low, high);
        assertThat(sorted.getFirst()).isSameAs(earlier);
    }

    private static FollowUpCandidate candidate(LocalDate dueDate, UUID customerId) {
        return new FollowUpCandidate(UUID.randomUUID(), TenantId.random(), new CustomerId(customerId), dueDate,
                FollowUpTimingSource.EXPLICIT_DATE, 7, 1, TODAY, Instant.parse("2026-11-26T12:00:00Z"));
    }
}
