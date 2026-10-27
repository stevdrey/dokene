// ABOUTME: JDBC adapter for the six messaging tables; identity columns have no UPDATE path by construction.
// ABOUTME: Translates the open message index violation into MESSAGE_ALREADY_OPEN and a version miss into STALE_VERSION.
package io.github.stevdrey.dokene.messaging.persistence.jdbc;

import io.github.stevdrey.dokene.ai.domain.SemanticAction;
import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import io.github.stevdrey.dokene.messaging.application.MessageCursor;
import io.github.stevdrey.dokene.messaging.application.OutboundMessageRepository;
import io.github.stevdrey.dokene.messaging.domain.ApprovalDecision;
import io.github.stevdrey.dokene.messaging.domain.ApprovalFingerprint;
import io.github.stevdrey.dokene.messaging.domain.FailureCategory;
import io.github.stevdrey.dokene.messaging.domain.MessageActorKind;
import io.github.stevdrey.dokene.messaging.domain.MessageApproval;
import io.github.stevdrey.dokene.messaging.domain.MessageCancellation;
import io.github.stevdrey.dokene.messaging.domain.MessageResolution;
import io.github.stevdrey.dokene.messaging.domain.MessageChannel;
import io.github.stevdrey.dokene.messaging.domain.MessageEvent;
import io.github.stevdrey.dokene.messaging.domain.MessageEventType;
import io.github.stevdrey.dokene.messaging.domain.MessageOrigin;
import io.github.stevdrey.dokene.messaging.domain.MessageStatus;
import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.messaging.domain.MessagingRefusedException;
import io.github.stevdrey.dokene.messaging.domain.OutboundMessage;
import io.github.stevdrey.dokene.messaging.domain.SendAttempt;
import io.github.stevdrey.dokene.messaging.domain.SendAttemptOutcome;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcOutboundMessageRepository implements OutboundMessageRepository {
    private static final String OPEN_MESSAGE_INDEX = "one_open_message_per_customer";
    private static final String STARTED_ATTEMPT_INDEX = "one_started_attempt_per_message";
    private static final String UNIQUE_VIOLATION = "23505";
    /** The message plus whether the customer still owns the snapshotted contact (no foreign key, spec 0001). */
    private static final String LISTED_SELECT = """
            SELECT m.*, NOT EXISTS (
                SELECT 1 FROM dokene.customer_phone_contacts c
                WHERE c.tenant_id = m.tenant_id AND c.customer_id = m.customer_id AND c.id = m.contact_id
            ) AS contact_removed
            FROM dokene.outbound_messages m
            """;

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
    public Optional<ListedMessage> findListedById(UUID tenantId, UUID messageId) {
        return first(jdbc.query(LISTED_SELECT + "WHERE m.tenant_id = ? AND m.id = ?", this::mapListed, tenantId,
                messageId));
    }

    @Override
    public List<ListedMessage> findPage(UUID tenantId, Set<MessageStatus> statuses, Optional<UUID> customerId,
            Optional<MessageCursor> after, int fetchLimit) {
        var sql = new StringBuilder(LISTED_SELECT).append("WHERE m.tenant_id = ?");
        var arguments = new ArrayList<Object>();
        arguments.add(tenantId);
        if (!statuses.isEmpty()) {
            sql.append(" AND m.status IN (").append(String.join(", ", statuses.stream().map(status -> "?").toList()))
                    .append(')');
            statuses.stream().map(MessageStatus::name).sorted().forEach(arguments::add);
        }
        customerId.ifPresent(id -> {
            sql.append(" AND m.customer_id = ?");
            arguments.add(id);
        });
        after.ifPresent(cursor -> {
            sql.append(" AND (m.created_at, m.id) < (?, ?)");
            arguments.add(Timestamp.from(cursor.createdAt()));
            arguments.add(cursor.id());
        });
        sql.append(" ORDER BY m.created_at DESC, m.id DESC LIMIT ?");
        arguments.add(fetchLimit);
        return jdbc.query(sql.toString(), this::mapListed, arguments.toArray());
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
                  AND status IN ('PENDING_APPROVAL', 'APPROVED', 'QUEUED', 'SENDING')
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
                    (id, tenant_id, message_id, decision, note, approval_fingerprint, decided_at,
                     decided_by_membership_id, decided_by_actor_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, a.id(), a.tenantId(), a.messageId(), a.decision().name(), a.note(),
                a.fingerprint().map(ApprovalFingerprint::value).orElse(null), Timestamp.from(a.decidedAt()),
                a.decidedByMembershipId(), a.decidedByActorId());
        return a;
    }

    @Override
    public Optional<MessageApproval> findApproval(UUID tenantId, UUID approvalId) {
        return first(jdbc.query("""
                SELECT * FROM dokene.outbound_message_approvals WHERE tenant_id = ? AND id = ?
                """, this::mapApproval, tenantId, approvalId));
    }

    @Override
    public Optional<MessageApproval> findApprovalByMessage(UUID tenantId, UUID messageId) {
        return first(jdbc.query("""
                SELECT * FROM dokene.outbound_message_approvals WHERE tenant_id = ? AND message_id = ?
                """, this::mapApproval, tenantId, messageId));
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
    public MessageResolution insertResolution(MessageResolution r) {
        jdbc.update("""
                INSERT INTO dokene.outbound_message_resolutions
                    (id, tenant_id, message_id, note, resolved_at, resolved_by_membership_id, resolved_by_actor_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, r.id(), r.tenantId(), r.messageId(), r.note(), Timestamp.from(r.resolvedAt()),
                r.resolvedByMembershipId(), r.resolvedByActorId());
        return r;
    }

    @Override
    public Optional<MessageResolution> findResolution(UUID tenantId, UUID resolutionId) {
        return first(jdbc.query("""
                SELECT * FROM dokene.outbound_message_resolutions WHERE tenant_id = ? AND id = ?
                """, (row, index) -> new MessageResolution(row.getObject("id", UUID.class),
                        row.getObject("tenant_id", UUID.class), row.getObject("message_id", UUID.class),
                        row.getString("note"), row.getTimestamp("resolved_at").toInstant(),
                        row.getObject("resolved_by_membership_id", UUID.class),
                        row.getObject("resolved_by_actor_id", UUID.class)), tenantId, resolutionId));
    }

    @Override
    public SendAttempt insertAttempt(SendAttempt a) {
        try {
            insertAttemptRow(a);
        } catch (DataIntegrityViolationException exception) {
            // The durable claim (issue 138): a second STARTED attempt for one message is a lost race, not a fault.
            if (violatesIndex(exception, STARTED_ATTEMPT_INDEX)) {
                throw new MessagingRefusedException(MessagingErrorCode.INVALID_TRANSITION);
            }
            throw exception;
        }
        return a;
    }

    private void insertAttemptRow(SendAttempt a) {
        jdbc.update("""
                INSERT INTO dokene.outbound_send_attempts
                    (id, tenant_id, message_id, attempt_number, send_key, outcome, failure_category,
                     provider_message_id, started_at, finished_at, requested_by_membership_id, requested_by_actor_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, a.id(), a.tenantId(), a.messageId(), a.attemptNumber(), a.sendKey(), a.outcome().name(),
                name(a.failureCategory()), a.providerMessageId(), Timestamp.from(a.startedAt()),
                timestamp(a.finishedAt()), a.requestedByMembershipId(), a.requestedByActorId());
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
                """, (row, index) -> new SendAttempt(row.getObject("id", UUID.class),
                        row.getObject("tenant_id", UUID.class), row.getObject("message_id", UUID.class),
                        row.getInt("attempt_number"), row.getObject("send_key", UUID.class),
                        SendAttemptOutcome.valueOf(row.getString("outcome")),
                        failureCategory(row.getString("failure_category")), row.getString("provider_message_id"),
                        row.getTimestamp("started_at").toInstant(), instant(row.getTimestamp("finished_at")),
                        row.getObject("requested_by_membership_id", UUID.class),
                        row.getObject("requested_by_actor_id", UUID.class)), tenantId, messageId);
    }

    @Override
    public List<ExpiredClaimRef> findExpiredClaims(Instant startedBefore, Optional<UUID> afterAttemptId, int limit) {
        return jdbc.query("""
                SELECT tenant_id, message_id, attempt_id
                FROM dokene.find_expired_dispatch_claims(?, ?::uuid, ?)
                """, (row, index) -> new ExpiredClaimRef(row.getObject("tenant_id", UUID.class),
                        row.getObject("message_id", UUID.class), row.getObject("attempt_id", UUID.class)),
                Timestamp.from(startedBefore), afterAttemptId.orElse(null), limit);
    }

    @Override
    public <T> Optional<T> runAsSoleRecoveryRunner(Supplier<T> run) {
        return jdbc.execute((ConnectionCallback<Optional<T>>) connection -> {
            if (!recoveryLock(connection, "pg_try_advisory_lock")) {
                return Optional.empty();
            }
            try {
                return Optional.ofNullable(run.get());
            } finally {
                recoveryLock(connection, "pg_advisory_unlock");
            }
        });
    }

    private static boolean recoveryLock(Connection connection, String function) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + function + "(hashtext('dokene'), hashtext('DISPATCH_RECOVERY'))");
                ResultSet result = statement.executeQuery()) {
            return result.next() && result.getBoolean(1);
        }
    }

    @Override
    public void acquireSendBudgetLock(UUID tenantId) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtext(?), hashtext(?))", tenantId + ":SEND_BUDGET",
                tenantId.toString());
    }

    @Override
    public int countAttemptsStartedSince(UUID tenantId, Instant since) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM dokene.outbound_send_attempts WHERE tenant_id = ? AND started_at >= ?
                """, Integer.class, tenantId, Timestamp.from(since));
        return count == null ? 0 : count;
    }

    @Override
    public Optional<Instant> oldestAttemptStartedSince(UUID tenantId, Instant since) {
        Timestamp oldest = jdbc.queryForObject("""
                SELECT min(started_at) FROM dokene.outbound_send_attempts WHERE tenant_id = ? AND started_at >= ?
                """, Timestamp.class, tenantId, Timestamp.from(since));
        return Optional.ofNullable(oldest).map(Timestamp::toInstant);
    }

    @Override
    public boolean sentToCustomerSince(UUID tenantId, UUID customerId, Instant since) {
        Boolean sent = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM dokene.outbound_messages
                               WHERE tenant_id = ? AND customer_id = ? AND sent_at >= ?)
                """, Boolean.class, tenantId, customerId, Timestamp.from(since));
        return Boolean.TRUE.equals(sent);
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

    private MessageApproval mapApproval(ResultSet row, int index) throws SQLException {
        String fingerprint = row.getString("approval_fingerprint");
        return new MessageApproval(row.getObject("id", UUID.class), row.getObject("tenant_id", UUID.class),
                row.getObject("message_id", UUID.class), ApprovalDecision.valueOf(row.getString("decision")),
                row.getString("note"), Optional.ofNullable(fingerprint).map(ApprovalFingerprint::new),
                row.getTimestamp("decided_at").toInstant(), row.getObject("decided_by_membership_id", UUID.class),
                row.getObject("decided_by_actor_id", UUID.class));
    }

    private ListedMessage mapListed(ResultSet row, int index) throws SQLException {
        return new ListedMessage(mapMessage(row, index), row.getBoolean("contact_removed"));
    }

    private OutboundMessage mapMessage(ResultSet row, int index) throws SQLException {
        return new OutboundMessage(row.getObject("id", UUID.class), row.getObject("tenant_id", UUID.class),
                row.getObject("customer_id", UUID.class), row.getObject("contact_id", UUID.class),
                row.getString("recipient_phone"), MessageChannel.valueOf(row.getString("channel")),
                MessageStatus.valueOf(row.getString("status")), row.getBoolean("outcome_unknown"),
                SemanticAction.valueOf(row.getString("action")),
                SemanticTemplateIntent.valueOf(row.getString("template_intent")), row.getString("locale"),
                MessageOrigin.valueOf(row.getString("origin")), row.getString("body"),
                row.getLong("source_policy_version"), row.getObject("send_key", UUID.class),
                row.getInt("attempt_count"), row.getString("provider_message_id"),
                failureCategory(row.getString("failure_category")), instant(row.getTimestamp("sent_at")),
                instant(row.getTimestamp("delivered_at")), instant(row.getTimestamp("read_at")),
                row.getObject("created_by_membership_id", UUID.class),
                row.getObject("created_by_actor_id", UUID.class), row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant(), row.getLong("version"));
    }

    private static boolean violatesOpenMessageIndex(DataIntegrityViolationException exception) {
        return violatesIndex(exception, OPEN_MESSAGE_INDEX);
    }

    private static boolean violatesIndex(DataIntegrityViolationException exception, String index) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof SQLException sql && UNIQUE_VIOLATION.equals(sql.getSQLState())) {
                return sql.getMessage() != null && sql.getMessage().contains(index);
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
