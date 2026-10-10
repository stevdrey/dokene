// ABOUTME: Row mappers for the messaging tables shared by the command and query JDBC adapters.
// ABOUTME: Column names are the table's own; joined reads alias their extra columns before calling these.
package io.github.stevdrey.dokene.messaging.persistence.jdbc;

import io.github.stevdrey.dokene.ai.domain.SemanticAction;
import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import io.github.stevdrey.dokene.messaging.domain.FailureCategory;
import io.github.stevdrey.dokene.messaging.domain.MessageChannel;
import io.github.stevdrey.dokene.messaging.domain.MessageOrigin;
import io.github.stevdrey.dokene.messaging.domain.MessageStatus;
import io.github.stevdrey.dokene.messaging.domain.OutboundMessage;
import io.github.stevdrey.dokene.messaging.domain.SendAttempt;
import io.github.stevdrey.dokene.messaging.domain.SendAttemptOutcome;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

final class OutboundMessageRows {
    private OutboundMessageRows() {
    }

    static OutboundMessage message(ResultSet row) throws SQLException {
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

    static SendAttempt attempt(ResultSet row) throws SQLException {
        return new SendAttempt(row.getObject("id", UUID.class), row.getObject("tenant_id", UUID.class),
                row.getObject("message_id", UUID.class), row.getInt("attempt_number"),
                row.getObject("send_key", UUID.class), SendAttemptOutcome.valueOf(row.getString("outcome")),
                failureCategory(row.getString("failure_category")), row.getString("provider_message_id"),
                row.getTimestamp("started_at").toInstant(), instant(row.getTimestamp("finished_at")),
                row.getObject("requested_by_membership_id", UUID.class),
                row.getObject("requested_by_actor_id", UUID.class));
    }

    static FailureCategory failureCategory(String value) {
        return value == null ? null : FailureCategory.valueOf(value);
    }

    static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
