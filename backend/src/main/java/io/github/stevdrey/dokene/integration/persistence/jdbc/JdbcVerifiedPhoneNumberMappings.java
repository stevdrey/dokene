package io.github.stevdrey.dokene.integration.persistence.jdbc;

import io.github.stevdrey.dokene.integration.application.VerifiedPhoneNumberMappings;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Calls the security-definer resolver, which bypasses tenant RLS but returns nothing beyond a verified tenant id. */
@Repository
public class JdbcVerifiedPhoneNumberMappings implements VerifiedPhoneNumberMappings {
    private final JdbcTemplate jdbc;

    public JdbcVerifiedPhoneNumberMappings(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<TenantId> tenantFor(String phoneNumberId) {
        if (phoneNumberId == null) {
            return Optional.empty();
        }
        UUID tenantId = jdbc.queryForObject("SELECT dokene.resolve_verified_whatsapp_tenant(?)", UUID.class,
                phoneNumberId);
        return Optional.ofNullable(tenantId).map(TenantId::new);
    }
}
