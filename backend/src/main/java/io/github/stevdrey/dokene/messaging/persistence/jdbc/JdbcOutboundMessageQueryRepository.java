// ABOUTME: JDBC read side for spec 0002: the joined detail row and the cursor paged list with the customer name.
// ABOUTME: contact_removed is computed in SQL as NOT EXISTS on customer_phone_contacts; nothing is stored.
package io.github.stevdrey.dokene.messaging.persistence.jdbc;

import io.github.stevdrey.dokene.ai.domain.SemanticAction;
import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import io.github.stevdrey.dokene.messaging.application.MessageCursor;
import io.github.stevdrey.dokene.messaging.application.MessageDetail;
import io.github.stevdrey.dokene.messaging.application.MessageListQuery;
import io.github.stevdrey.dokene.messaging.application.MessagePage;
import io.github.stevdrey.dokene.messaging.application.MessageSummary;
import io.github.stevdrey.dokene.messaging.application.OutboundMessageQueryRepository;
import io.github.stevdrey.dokene.messaging.domain.ApprovalDecision;
import io.github.stevdrey.dokene.messaging.domain.MessageApproval;
import io.github.stevdrey.dokene.messaging.domain.MessageCancellation;
import io.github.stevdrey.dokene.messaging.domain.MessageOrigin;
import io.github.stevdrey.dokene.messaging.domain.MessageStatus;
import io.github.stevdrey.dokene.messaging.domain.SendAttempt;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcOutboundMessageQueryRepository implements OutboundMessageQueryRepository {
    private static final String CONTACT_REMOVED = """
            NOT EXISTS (SELECT 1 FROM dokene.customer_phone_contacts p
                        WHERE p.tenant_id = m.tenant_id AND p.customer_id = m.customer_id AND p.id = m.contact_id)
                AS contact_removed""";

    private final JdbcTemplate jdbc;

    public JdbcOutboundMessageQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<MessageDetail> findDetail(UUID tenantId, UUID messageId) {
        List<MessageDetail> rows = jdbc.query("""
                SELECT m.*, c.display_name, %s,
                       a.id AS approval_id, a.decision AS approval_decision, a.note AS approval_note,
                       a.decided_at, a.decided_by_membership_id, a.decided_by_actor_id,
                       x.id AS cancellation_id, x.status_from, x.note AS cancellation_note,
                       x.cancelled_at, x.cancelled_by_membership_id, x.cancelled_by_actor_id
                FROM dokene.outbound_messages m
                JOIN dokene.customers c ON c.tenant_id = m.tenant_id AND c.id = m.customer_id
                LEFT JOIN dokene.outbound_message_approvals a ON a.tenant_id = m.tenant_id AND a.message_id = m.id
                LEFT JOIN dokene.outbound_message_cancellations x ON x.tenant_id = m.tenant_id AND x.message_id = m.id
                WHERE m.tenant_id = ? AND m.id = ?
                """.formatted(CONTACT_REMOVED), (row, index) -> detail(row), tenantId, messageId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        MessageDetail partial = rows.getFirst();
        List<SendAttempt> attempts = jdbc.query("""
                SELECT * FROM dokene.outbound_send_attempts WHERE tenant_id = ? AND message_id = ?
                ORDER BY attempt_number
                """, (row, index) -> OutboundMessageRows.attempt(row), tenantId, messageId);
        return Optional.of(new MessageDetail(partial.message(), partial.customerDisplayName(), partial.approval(),
                partial.cancellation(), attempts, partial.contactRemoved()));
    }

    @Override
    public MessagePage findPage(UUID tenantId, MessageListQuery query) {
        StringBuilder sql = new StringBuilder("""
                SELECT m.id, m.customer_id, c.display_name, m.contact_id, m.recipient_phone, m.status,
                       m.outcome_unknown, m.action, m.template_intent, m.locale, m.origin, m.created_at,
                       m.updated_at, m.version, %s
                FROM dokene.outbound_messages m
                JOIN dokene.customers c ON c.tenant_id = m.tenant_id AND c.id = m.customer_id
                WHERE m.tenant_id = ?
                """.formatted(CONTACT_REMOVED));
        List<Object> parameters = new ArrayList<>();
        parameters.add(tenantId);
        if (!query.statuses().isEmpty()) {
            sql.append(" AND m.status IN (")
                    .append(query.statuses().stream().map(status -> "?").collect(Collectors.joining(", ")))
                    .append(")");
            query.statuses().stream().map(MessageStatus::name).sorted().forEach(parameters::add);
        }
        query.customerId().ifPresent(customerId -> {
            sql.append(" AND m.customer_id = ?");
            parameters.add(customerId);
        });
        query.cursor().ifPresent(cursor -> {
            sql.append(" AND (m.created_at, m.id) < (?, ?)");
            parameters.add(Timestamp.from(cursor.createdAt()));
            parameters.add(cursor.messageId());
        });
        sql.append(" ORDER BY m.created_at DESC, m.id DESC LIMIT ?");
        parameters.add(query.limit() + 1);

        List<MessageSummary> rows = jdbc.query(sql.toString(), (row, index) -> summary(row), parameters.toArray());
        if (rows.size() <= query.limit()) {
            return new MessagePage(rows, Optional.empty());
        }
        List<MessageSummary> page = rows.subList(0, query.limit());
        MessageSummary last = page.getLast();
        return new MessagePage(page, Optional.of(new MessageCursor(last.createdAt(), last.messageId())));
    }

    private static MessageDetail detail(ResultSet row) throws SQLException {
        Optional<MessageApproval> approval = Optional.empty();
        UUID approvalId = row.getObject("approval_id", UUID.class);
        if (approvalId != null) {
            approval = Optional.of(new MessageApproval(approvalId, row.getObject("tenant_id", UUID.class),
                    row.getObject("id", UUID.class), ApprovalDecision.valueOf(row.getString("approval_decision")),
                    row.getString("approval_note"), row.getTimestamp("decided_at").toInstant(),
                    row.getObject("decided_by_membership_id", UUID.class),
                    row.getObject("decided_by_actor_id", UUID.class)));
        }
        Optional<MessageCancellation> cancellation = Optional.empty();
        UUID cancellationId = row.getObject("cancellation_id", UUID.class);
        if (cancellationId != null) {
            cancellation = Optional.of(new MessageCancellation(cancellationId, row.getObject("tenant_id", UUID.class),
                    row.getObject("id", UUID.class), MessageStatus.valueOf(row.getString("status_from")),
                    row.getString("cancellation_note"), row.getTimestamp("cancelled_at").toInstant(),
                    row.getObject("cancelled_by_membership_id", UUID.class),
                    row.getObject("cancelled_by_actor_id", UUID.class)));
        }
        return new MessageDetail(OutboundMessageRows.message(row), row.getString("display_name"), approval,
                cancellation, List.of(), row.getBoolean("contact_removed"));
    }

    private static MessageSummary summary(ResultSet row) throws SQLException {
        return new MessageSummary(row.getObject("id", UUID.class), row.getObject("customer_id", UUID.class),
                row.getString("display_name"), row.getObject("contact_id", UUID.class),
                row.getString("recipient_phone"), MessageStatus.valueOf(row.getString("status")),
                row.getBoolean("outcome_unknown"), SemanticAction.valueOf(row.getString("action")),
                SemanticTemplateIntent.valueOf(row.getString("template_intent")), row.getString("locale"),
                MessageOrigin.valueOf(row.getString("origin")), row.getBoolean("contact_removed"),
                row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant(),
                row.getLong("version"));
    }
}
