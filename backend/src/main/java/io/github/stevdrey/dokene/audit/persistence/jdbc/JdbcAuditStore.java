package io.github.stevdrey.dokene.audit.persistence.jdbc;

import io.github.stevdrey.dokene.audit.application.AuditCursor;
import io.github.stevdrey.dokene.audit.domain.AuditDenialReason;
import io.github.stevdrey.dokene.audit.domain.AuditEvent;
import io.github.stevdrey.dokene.audit.domain.AuditEventType;
import io.github.stevdrey.dokene.audit.domain.AuditMetadata;
import io.github.stevdrey.dokene.audit.domain.AuditOutcome;
import io.github.stevdrey.dokene.audit.domain.AuditTarget;
import io.github.stevdrey.dokene.tenant.application.SignedDatabaseContext;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Package-private append/read adapter; no update/delete API. */
@Repository
class JdbcAuditStore {
    private final JdbcTemplate jdbc;

    JdbcAuditStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void append(AuditEvent event, SignedDatabaseContext capability) {
        AuditMetadata.AuthorizationDenied denial = event.metadata() instanceof AuditMetadata.AuthorizationDenied value
                ? value : null;
        AuditMetadata.MembershipRoleChanged change = event.metadata() instanceof AuditMetadata.MembershipRoleChanged value
                ? value : null;
        jdbc.queryForObject("""
                SELECT dokene.append_audit_event(
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.class,
                event.id(), Timestamp.from(event.timestamp()),
                event.type().name(),
                event.target() == null ? null : event.target().type().name(),
                event.target() == null ? null : event.target().id(), event.outcome().name(), event.correlationId(),
                denial == null || denial.permission() == null ? null : denial.permission().name(),
                denial == null ? null : denial.reason().name(),
                change == null ? null : change.previousRole().name(), change == null ? null : change.newRole().name(),
                capability == null ? null : capability.payload(),
                capability == null ? null : capability.signature());
    }

    List<AuditEvent> read(TenantId tenant, AuditCursor before, int limit) {
        if (before == null) {
            return jdbc.query("""
                    SELECT * FROM dokene.audit_events WHERE tenant_id = ?
                    ORDER BY occurred_at DESC, id DESC LIMIT ?
                    """, this::map, tenant.value(), limit);
        }
        return jdbc.query("""
                SELECT * FROM dokene.audit_events
                WHERE tenant_id = ? AND (occurred_at, id) < (?, ?)
                ORDER BY occurred_at DESC, id DESC LIMIT ?
                """, this::map, tenant.value(), Timestamp.from(before.timestamp()), before.id(), limit);
    }

    private AuditEvent map(ResultSet row, int index) throws SQLException {
        AuditEventType type = AuditEventType.valueOf(row.getString("event_type"));
        AuditMetadata metadata = switch (type) {
            case AUTHORIZATION_DENIED -> new AuditMetadata.AuthorizationDenied(
                    row.getString("permission") == null ? null : TenantPermission.valueOf(row.getString("permission")),
                    AuditDenialReason.valueOf(row.getString("denial_reason")));
            case MEMBERSHIP_ROLE_CHANGED -> new AuditMetadata.MembershipRoleChanged(
                    TenantRole.valueOf(row.getString("previous_role")), TenantRole.valueOf(row.getString("new_role")));
        };
        return new AuditEvent(row.getObject("id", UUID.class), row.getTimestamp("occurred_at").toInstant(),
                new TenantId(row.getObject("tenant_id", UUID.class)), new IdentityId(row.getObject("actor_id", UUID.class)),
                new TenantMembershipId(row.getObject("membership_id", UUID.class)), type,
                row.getString("target_type") == null ? null : new AuditTarget(
                        AuditTarget.Type.valueOf(row.getString("target_type")), row.getObject("target_id", UUID.class)),
                AuditOutcome.valueOf(row.getString("outcome")), row.getObject("correlation_id", UUID.class), metadata);
    }
}
