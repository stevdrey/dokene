package io.github.stevdrey.dokene.customer.persistence.jdbc;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.github.stevdrey.dokene.customer.application.CustomerConflictException;
import io.github.stevdrey.dokene.customer.domain.ContactIntentSource;
import io.github.stevdrey.dokene.customer.domain.Customer;
import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.customer.domain.CustomerPhone;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcContactPolicyRepositoryTest {
    @Test
    void rejectsVersionOverflowBeforeExecutingSql() {
        JdbcTemplate jdbc = mock();
        var repository = new JdbcContactPolicyRepository(jdbc);
        TenantId tenantId = TenantId.random();
        Customer customer = Customer.create(new CustomerId(UUID.randomUUID()), tenantId, "Overflow", null,
                List.of(CustomerPhone.create("+50688887777", true)), Instant.parse("2026-09-07T00:00:00Z"));
        TenantContext actor = new TenantContext(tenantId, new IdentityId(UUID.randomUUID()),
                TenantMembershipId.random(), TenantRole.OWNER);

        assertThatThrownBy(() -> repository.changeDoNotContact(customer, true,
                ContactIntentSource.CUSTOMER_VERBAL, Long.MAX_VALUE, actor, Instant.now()))
                .isInstanceOf(CustomerConflictException.class);
        verifyNoInteractions(jdbc);
    }
}
