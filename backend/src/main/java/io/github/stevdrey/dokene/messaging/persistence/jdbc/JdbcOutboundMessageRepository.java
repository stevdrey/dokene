// ABOUTME: JDBC adapter for the six messaging tables; identity columns have no UPDATE path by construction.
// ABOUTME: Translates the open message index violation into MESSAGE_ALREADY_OPEN and a version miss into STALE_VERSION.
package io.github.stevdrey.dokene.messaging.persistence.jdbc;

import io.github.stevdrey.dokene.messaging.application.OutboundMessageRepository;
import io.github.stevdrey.dokene.messaging.domain.ApprovalDecision;
import io.github.stevdrey.dokene.messaging.domain.FailureCategory;
import io.github.stevdrey.dokene.messaging.domain.MessageActorKind;
import io.github.stevdrey.dokene.messaging.domain.MessageApproval;
import io.github.stevdrey.dokene.messaging.domain.MessageCancellation;
import io.github.stevdrey.dokene.messaging.domain.MessageEvent;
import io.github.stevdrey.dokene.messaging.domain.MessageEventType;
import io.github.stevdrey.dokene.messaging.domain.MessageStatus;
import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.messaging.domain.MessagingRefusedException;
import io.github.stevdrey.dokene.messaging.domain.OutboundMessage;
import io.github.stevdrey.dokene.messaging.domain.SendAttempt;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcOutboundMessageRepository implements OutboundMessageRepository {
    private static final String OPEN_MESSAGE_INDEX = "one_open_message_per_customer";
    private static final String UNIQUE_VIOLATION = "23505";

    private final JdbcTemplate jdbc;

    public JdbcOutboundMessageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public OutboundMessage insert(OutboundMessage m) {
        try {
            jdbc.update("""
                    INSERT INTO dokene.outbound_messages (
                        id, tenant_id, customer_id, contact_id, recipient_phone, channel, status, outcome_unknown,
                        action, template_intent, locale, origin, body, source_policy_version, send_key,
                        attempt_count, provider_message_id, failure_category, sent_at, delivered_at, read_at,
                        created_by_membership_id, created_by_actor_id, created_at, updated_at, version)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, m.id(), m.tenantId(), m.customerId(), m.contactId(), m.recipientPhone(),
                    m.channel().name(), m.status().name(), m.outcomeUnknown(), m.action().name(),
                    m.templateIntent().name(), m.locale(), m.origin().name(), m.body(), m.sourcePolicyVersion(),
                    m.sendKey(), m.attemptCount(), m.providerMessageId(), name(m.failureCategory()),
                    timestamp(m.sentAt()), timestamp(m.deliveredAt()), timestamp(m.readAt()),
                    m.createdByMembershipId(), m.createdByActorId(), Timestamp.from(m.createdAt()),
                    Timestamp.from(m.updatedAt()), m.version());
        } catch (DataIntegrityViolationException exception) {
            if (violatesOpenMessageIndex(exception)) {
                throw new MessagingRefusedException(MessagingErrorCode.MESSAGE_ALREADY_OPEN);
            }
            throw exception;
        }
        return m;
    }

    @Override
    public Optional<OutboundMessage> findById(UUID tenantId, UUID messageId) {
        return first(jdbc.query("""
                SELECT * FROM dokene.outbound_messages WHERE tenant_id = ? AND id = ?
                """, this::mapMessage, tenantId, messageId));
    }

    @Override
    public Optional<OutboundMessage> findByIdForUpdate(UUID tenantId, UUID messageId) {
        return first(jdbc.query("""
                SELECT * FROM dokene.outbound_messages WHERE tenant_id = ? AND id = ? FOR UPDATE
                """, this::mapMessage, tenantId, messageId));
    }

    @Override
    public Optional<ProviderMessageRef> findIdsByProviderMessageId(UUID tenantId, String providerMessageId) {
        return first(jdbc.query("""
                SELECT id, customer_id FROM dokene.outbound_messages
                WHERE tenant_id = ? AND provider_message_id = ?
                """, (row, index) -> new ProviderMessageRef(row.getObject("id", UUID.class),
                        row.getObject("customer_id", UUID.class)), tenantId, providerMessageId));
    }

    @Override
    public Optional<OutboundMessage> findOpenByCustomer(UUID tenantId, UUID customerId,
            Optional<UUID> excludingMessageId) {
        return first(jdbc.query("""
                SELECT * FROM dokene.outbound_messages
                WHERE tenant_id = ? AND customer_id = ?
                  AND status NOT IN ('READ', 'REJECTED', 'CANCELLED', 'FAILED')
                  AND (CAST(? AS uuid) IS NULL OR id <> CAST(? AS uuid))
                """, this::mapMessage, tenantId, customerId, excludingMessageId.orElse(null),
                excludingMessageId.orElse(null)));
    }

    @Override
    public OutboundMessage update(OutboundMessage expected, OutboundMessage next) {
        if (!expected.id().equals(next.id()) || !expected.tenantId().equals(next.tenantId())) {
            throw new IllegalArgumentException("Update must target the same message");
        }
        int updated = jdbc.update("""
                UPDATE dokene.outbound_messages
                SET status = ?, outcome_unknown = ?, attempt_count = ?, provider_message_id = ?,
                    failure_category = ?, sent_at = ?, delivered_at = ?, read_at = ?, updated_at = ?, version = ?
                WHERE tenant_id = ? AND id = ? AND version = ?
                """, next.status().name(), next.outcomeUnknown(), next.attemptCount(), next.providerMessageId(),
                name(next.failureCategory()), timestamp(next.sentAt()), timestamp(next.deliveredAt()),
                timestamp(next.readAt()), Timestamp.from(next.updatedAt()), next.version(),
                expected.tenantId(), expected.id(), expected.version());
        if (updated != 1) {
            throw new MessagingRefusedException(MessagingErrorCode.STALE_VERSION);
        }
        return next;
    }

    @Override
    public MessageApproval insertApproval(MessageApproval a) {
        jdbc.update("""
                INSERT INTO dokene.outbound_message_approvals
                    (id, tenant_id, message_id, decision, note, decided_at, decided_by_membership_id,
                     decided_by_actor_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, a.id(), a.tenantId(), a.messageId(), a.decision().name(), a.note(),
                Timestamp.from(a.decidedAt()), a.decidedByMembershipId(), a.decidedByActorId());
        return a;
    }

    @Override
    public Optional<MessageApproval> findApproval(UUID tenantId, UUID approvalId) {
        return first(jdbc.query("""
                SELECT * FROM dokene.outbound_message_approvals WHERE tenant_id = ? AND id = ?
                """, JdbcOutboundMessageRepository::mapApproval, tenantId, approvalId));
    }

    @Override
    public Optional<MessageApproval> findApprovalByMessage(UUID tenantId, UUID messageId) {
        return first(jdbc.query("""
                SELECT * FROM dokene.outbound_message_approvals WHERE tenant_id = ? AND message_id = ?
                """, JdbcOutboundMessageRepository::mapApproval, tenantId, messageId));
    }

    private static MessageApproval mapApproval(ResultSet row, int index) throws SQLException {
        return new MessageApproval(row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class), row.getObject("message_id", UUID.class),
                ApprovalDecision.valueOf(row.getString("decision")), row.getString("note"),
                row.getTimestamp("decided_at").toInstant(),
                row.getObject("decided_by_membership_id", UUID.class),
                row.getObject("decided_by_actor_id", UUID.class));
    }

    @Override
    public Optional<Instant> findLatestSentAt(UUID tenantId, UUID customerId) {
        Timestamp latest = jdbc.queryForObject("""
                SELECT max(sent_at) FROM dokene.outbound_messages
                WHERE tenant_id = ? AND customer_id = ? AND sent_at IS NOT NULL
                """, Timestamp.class, tenantId, customerId);
        return Optional.ofNullable(latest).map(Timestamp::toInstant);
    }

    @Override
    public MessageCancellation insertCancellation(MessageCancellation c) {
        jdbc.update("""
                INSERT INTO dokene.outbound_message_cancellations
                    (id, tenant_id, message_id, status_from, note, cancelled_at, cancelled_by_membership_id,
                     cancelled_by_actor_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, c.id(), c.tenantId(), c.messageId(), c.statusFrom().name(), c.note(),
                Timestamp.from(c.cancelledAt()), c.cancelledByMembershipId(), c.cancelledByActorId());
        return c;
    }

    @Override
    public Optional<MessageCancellation> findCancellation(UUID tenantId, UUID cancellationId) {
        return first(jdbc.query("""
                SELECT * FROM dokene.outbound_message_cancellations WHERE tenant_id = ? AND id = ?
                """, (row, index) -> new MessageCancellation(row.getObject("id", UUID.class),
                        row.getObject("tenant_id", UUID.class), row.getObject("message_id", UUID.class),
                        MessageStatus.valueOf(row.getString("status_from")), row.getString("note"),
                        row.getTimestamp("cancelled_at").toInstant(),
                        row.getObject("cancelled_by_membership_id", UUID.class),
                        row.getObject("cancelled_by_actor_id", UUID.class)), tenantId, cancellationId));
    }

    @Override
    public SendAttempt insertAttempt(SendAttempt a) {
        jdbc.update("""
                INSERT INTO dokene.outbound_send_attempts
                    (id, tenant_id, message_id, attempt_number, send_key, outcome, failure_category,
                     provider_message_id, started_at, finished_at, requested_by_membership_id, requested_by_actor_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, a.id(), a.tenantId(), a.messageId(), a.attemptNumber(), a.sendKey(), a.outcome().name(),
                name(a.failureCategory()), a.providerMessageId(), Timestamp.from(a.startedAt()),
                timestamp(a.finishedAt()), a.requestedByMembershipId(), a.requestedByActorId());
        return a;
    }

    @Override
    public SendAttempt updateAttempt(SendAttempt a) {
        int updated = jdbc.update("""
                UPDATE dokene.outbound_send_attempts
                SET outcome = ?, failure_category = ?, provider_message_id = ?, finished_at = ?
                WHERE tenant_id = ? AND id = ? AND outcome = 'STARTED'
                """, a.outcome().name(), name(a.failureCategory()), a.providerMessageId(),
                timestamp(a.finishedAt()), a.tenantId(), a.id());
        if (updated != 1) {
            throw new MessagingRefusedException(MessagingErrorCode.STALE_VERSION);
        }
        return a;
    }

    @Override
    public List<SendAttempt> findAttempts(UUID tenantId, UUID messageId) {
        return jdbc.query("""
                SELECT * FROM dokene.outbound_send_attempts WHERE tenant_id = ? AND message_id = ?
                ORDER BY attempt_number
                """, (row, index) -> OutboundMessageRows.attempt(row), tenantId, messageId);
    }

    @Override
    public MessageEvent appendEvent(MessageEvent e) {
        jdbc.update("""
                INSERT INTO dokene.outbound_message_events
                    (id, tenant_id, message_id, sequence_number, event_type, status_from, status_to, applied,
                     actor_kind, membership_id, failure_category, attempt_number, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, e.id(), e.tenantId(), e.messageId(), e.sequenceNumber(), e.type().name(),
                e.statusFrom() == null ? null : e.statusFrom().name(), e.statusTo().name(), e.applied(),
                e.actorKind().name(), e.membershipId(), name(e.failureCategory()), e.attemptNumber(),
                Timestamp.from(e.occurredAt()));
        return e;
    }

    @Override
    public List<MessageEvent> findEvents(UUID tenantId, UUID messageId) {
        return jdbc.query("""
                SELECT * FROM dokene.outbound_message_events WHERE tenant_id = ? AND message_id = ?
                ORDER BY sequence_number
                """, (row, index) -> new MessageEvent(row.getObject("id", UUID.class),
                        row.getObject("tenant_id", UUID.class), row.getObject("message_id", UUID.class),
                        row.getLong("sequence_number"), MessageEventType.valueOf(row.getString("event_type")),
                        row.getString("status_from") == null ? null : MessageStatus.valueOf(row.getString("status_from")),
                        MessageStatus.valueOf(row.getString("status_to")), row.getBoolean("applied"),
                        MessageActorKind.valueOf(row.getString("actor_kind")),
                        row.getObject("membership_id", UUID.class), failureCategory(row.getString("failure_category")),
                        (Integer) row.getObject("attempt_number"), row.getTimestamp("occurred_at").toInstant()),
                tenantId, messageId);
    }

    private OutboundMessage mapMessage(ResultSet row, int index) throws SQLException {
        return OutboundMessageRows.message(row);
    }

    private static boolean violatesOpenMessageIndex(DataIntegrityViolationException exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof SQLException sql && UNIQUE_VIOLATION.equals(sql.getSQLState())) {
                return sql.getMessage() != null && sql.getMessage().contains(OPEN_MESSAGE_INDEX);
            }
            cause = cause.getCause();
        }
        return false;
    }

    private static <T> Optional<T> first(List<T> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static FailureCategory failureCategory(String value) {
        return value == null ? null : FailureCategory.valueOf(value);
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
