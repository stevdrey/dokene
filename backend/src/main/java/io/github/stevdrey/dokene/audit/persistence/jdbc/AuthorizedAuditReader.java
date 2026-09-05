package io.github.stevdrey.dokene.audit.persistence.jdbc;

import io.github.stevdrey.dokene.audit.application.AuditCursor;
import io.github.stevdrey.dokene.audit.application.AuditPage;
import io.github.stevdrey.dokene.audit.application.AuditReader;
import io.github.stevdrey.dokene.audit.domain.AuditEvent;
import io.github.stevdrey.dokene.tenant.application.TenantAuthorizationService;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AuthorizedAuditReader implements AuditReader {
    private final JdbcAuditStore store;
    private final TenantAuthorizationService authorization;
    private final TenantContextProvider contexts;

    AuthorizedAuditReader(JdbcAuditStore store, TenantAuthorizationService authorization, TenantContextProvider contexts) {
        this.store = store;
        this.authorization = authorization;
        this.contexts = contexts;
    }

    @Override
    @Transactional(readOnly = true)
    public AuditPage read() {
        return read(null, 50);
    }

    @Override
    @Transactional(readOnly = true)
    public AuditPage read(AuditCursor before, int limit) {
        authorization.requirePermission(TenantPermission.AUDIT_READ);
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Audit page size must be between 1 and 100");
        }
        List<AuditEvent> rows = store.read(contexts.requireCurrent().tenantId(), before, limit + 1);
        boolean more = rows.size() > limit;
        List<AuditEvent> page = more ? rows.subList(0, limit) : rows;
        Optional<AuditCursor> next = more
                ? Optional.of(new AuditCursor(page.getLast().timestamp(), page.getLast().id())) : Optional.empty();
        return new AuditPage(page, next);
    }
}
