package io.github.stevdrey.dokene.audit.persistence.jdbc;

import io.github.stevdrey.dokene.audit.application.AuditCursor;
import io.github.stevdrey.dokene.audit.domain.AiAuditDetail;
import io.github.stevdrey.dokene.audit.domain.AiAuditOperation;
import io.github.stevdrey.dokene.audit.domain.AiAuditOutcome;
import io.github.stevdrey.dokene.audit.domain.AuditDenialReason;
import io.github.stevdrey.dokene.audit.domain.AuditEvent;
import io.github.stevdrey.dokene.audit.domain.AuditEventType;
import io.github.stevdrey.dokene.audit.domain.AuditFailureCategory;
import io.github.stevdrey.dokene.audit.domain.IntegrationAuditField;
import io.github.stevdrey.dokene.audit.domain.AuditMessageStatus;
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
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
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
        AuditMetadata.MembershipCreated created = event.metadata() instanceof AuditMetadata.MembershipCreated value
                ? value : null;
        AuditMetadata.AiInvocation ai = event.metadata() instanceof AuditMetadata.AiInvocation value
                ? value : null;
        AuditMetadata.MessageTransition transition = event.metadata() instanceof AuditMetadata.MessageTransition value
                ? value : null;
        AuditMetadata.IntegrationToggle toggle = event.metadata() instanceof AuditMetadata.IntegrationToggle value
                ? value : null;
        AuditMetadata.IntegrationUpdate update = event.metadata() instanceof AuditMetadata.IntegrationUpdate value
                ? value : null;
        // Boxed explicitly: a mixed boolean/Boolean ternary would unbox a null and throw.
        Boolean enabled = toggle != null ? Boolean.valueOf(toggle.enabled())
                : (update != null ? Boolean.valueOf(update.enabled()) : null);
        String previousRole = change == null ? null : change.previousRole().name();
        String newRole = change != null ? change.newRole().name() : (created != null ? created.role().name() : null);
        jdbc.queryForObject("""
                SELECT dokene.append_audit_event(
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.class,
                event.id(), Timestamp.from(event.timestamp()),
                event.type().name(),
                event.target() == null ? null : event.target().type().name(),
                event.target() == null ? null : event.target().id(), event.outcome().name(), event.correlationId(),
                denial == null || denial.permission() == null ? null : denial.permission().name(),
                denial == null ? null : denial.reason().name(),
                previousRole, newRole,
                capability == null ? null : capability.payload(),
                capability == null ? null : capability.signature(),
                ai == null ? null : ai.operation().name(),
                ai == null ? null : ai.outcome().name(),
                ai == null ? null : ai.detail().name(),
                transition == null || transition.from() == null ? null : transition.from().name(),
                transition == null ? null : transition.to().name(),
                transition == null || transition.failureCategory() == null ? null : transition.failureCategory().name(),
                transition == null ? null : transition.attemptNumber(),
                enabled,
                update == null ? null : changedFields(update.changedFields()));
    }

    /** Comma joined enum names in declaration order, null for an empty set (the column constraint allows nothing else). */
    static String changedFields(Set<IntegrationAuditField> fields) {
        if (fields.isEmpty()) {
            return null;
        }
        return fields.stream().sorted().map(Enum::name).collect(Collectors.joining(","));
    }

    static Set<IntegrationAuditField> changedFields(String column) {
        EnumSet<IntegrationAuditField> fields = EnumSet.noneOf(IntegrationAuditField.class);
        if (column != null && !column.isEmpty()) {
            Arrays.stream(column.split(",")).map(IntegrationAuditField::valueOf).forEach(fields::add);
        }
        return fields;
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
            case MEMBERSHIP_CREATED -> new AuditMetadata.MembershipCreated(
                    TenantRole.valueOf(row.getString("new_role")));
            case MEMBERSHIP_REVOKED -> new AuditMetadata.MembershipRevoked();
            case CUSTOMER_CREATED, CUSTOMER_UPDATED, CUSTOMER_ARCHIVED,
                    CUSTOMER_CONSENT_CHANGED, CUSTOMER_DO_NOT_CONTACT_CHANGED -> new AuditMetadata.CustomerMutation();
            case PURCHASE_RECORDED, PURCHASE_CORRECTED, PURCHASE_VOIDED -> new AuditMetadata.PurchaseMutation();
            case TENANT_FOLLOW_UP_POLICY_CHANGED, CUSTOMER_FOLLOW_UP_POLICY_CHANGED,
                    FOLLOW_UP_SNOOZED, MANUAL_FOLLOW_UP_RECORDED, FOLLOW_UP_DISMISSED -> new AuditMetadata.FollowUpMutation();
            case AI_INVOCATION_OUTCOME -> new AuditMetadata.AiInvocation(
                    AiAuditOperation.valueOf(row.getString("ai_operation")),
                    AiAuditOutcome.valueOf(row.getString("ai_outcome")),
                    AiAuditDetail.valueOf(row.getString("ai_detail")));
            case MESSAGE_SUBMITTED, MESSAGE_APPROVED, MESSAGE_REJECTED, MESSAGE_CANCELLED, MESSAGE_SEND_REQUESTED,
                    MESSAGE_SENT, MESSAGE_SEND_FAILED, MESSAGE_SEND_OUTCOME_UNKNOWN, MESSAGE_DELIVERY_UPDATED ->
                    new AuditMetadata.MessageTransition(
                            row.getString("status_from") == null ? null
                                    : AuditMessageStatus.valueOf(row.getString("status_from")),
                            AuditMessageStatus.valueOf(row.getString("status_to")),
                            row.getString("failure_category") == null ? null
                                    : AuditFailureCategory.valueOf(row.getString("failure_category")),
                            (Integer) row.getObject("attempt_number"));
            case TEMPLATE_MAPPING_UPDATED, OUTBOUND_KILL_SWITCH_CHANGED ->
                    new AuditMetadata.IntegrationToggle(row.getBoolean("enabled"));
            case INTEGRATION_UPDATED -> new AuditMetadata.IntegrationUpdate(row.getBoolean("enabled"),
                    changedFields(row.getString("changed_fields")));
        };
        UUID actorId = row.getObject("actor_id", UUID.class);
        UUID membershipId = row.getObject("membership_id", UUID.class);
        return new AuditEvent(row.getObject("id", UUID.class), row.getTimestamp("occurred_at").toInstant(),
                new TenantId(row.getObject("tenant_id", UUID.class)),
                actorId == null ? null : new IdentityId(actorId),
                membershipId == null ? null : new TenantMembershipId(membershipId), type,
                row.getString("target_type") == null ? null : new AuditTarget(
                        AuditTarget.Type.valueOf(row.getString("target_type")), row.getObject("target_id", UUID.class)),
                AuditOutcome.valueOf(row.getString("outcome")), row.getObject("correlation_id", UUID.class), metadata);
    }
}
