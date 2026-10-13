// ABOUTME: Unit tests for DurableMessageAuditAdapter: every message event maps onto the closed audit vocabulary.
// ABOUTME: Covers AC-6 of spec 0001: closed metadata, wrong event types refused, provider attributed delivery audit.
package io.github.stevdrey.dokene.audit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import io.github.stevdrey.dokene.audit.domain.AuditEventType;
import io.github.stevdrey.dokene.audit.domain.AuditFailureCategory;
import io.github.stevdrey.dokene.audit.domain.AuditMessageStatus;
import io.github.stevdrey.dokene.audit.domain.AuditMetadata;
import io.github.stevdrey.dokene.messaging.domain.FailureCategory;
import io.github.stevdrey.dokene.messaging.domain.MessageActorKind;
import io.github.stevdrey.dokene.messaging.domain.MessageEvent;
import io.github.stevdrey.dokene.messaging.domain.MessageEventType;
import io.github.stevdrey.dokene.messaging.domain.MessageStatus;
import io.github.stevdrey.dokene.tenant.application.ProviderContext;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class DurableMessageAuditAdapterTest {
    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");

    private final AuditRecorder recorder = mock(AuditRecorder.class);
    private final DurableMessageAuditAdapter adapter = new DurableMessageAuditAdapter(recorder);
    private final UUID tenantId = UUID.randomUUID();
    private final UUID messageId = UUID.randomUUID();

    @Test
    void submittedCarriesNoSourceStatusAndTargetsTheMessage() {
        adapter.submitted(member(MessageEventType.SUBMITTED, null, MessageStatus.PENDING_APPROVAL, null, null));

        verify(recorder).messageTransition(messageId, AuditEventType.MESSAGE_SUBMITTED,
                new AuditMetadata.MessageTransition(null, AuditMessageStatus.PENDING_APPROVAL, null, null));
    }

    @Test
    void sendFailedCarriesTheFailureCategoryAndAttemptNumberOnly() {
        adapter.sendFailed(system(MessageEventType.SEND_FAILED, MessageStatus.SENDING, MessageStatus.FAILED,
                FailureCategory.TEMPLATE_REJECTED, 2));

        verify(recorder).messageTransition(messageId, AuditEventType.MESSAGE_SEND_FAILED,
                new AuditMetadata.MessageTransition(AuditMessageStatus.SENDING, AuditMessageStatus.FAILED,
                        AuditFailureCategory.TEMPLATE_REJECTED, 2));
    }

    @Test
    void everyMemberAndSystemEventMapsToItsOwnAuditType() {
        adapter.approved(member(MessageEventType.APPROVED, MessageStatus.PENDING_APPROVAL, MessageStatus.APPROVED, null, null));
        adapter.rejected(member(MessageEventType.REJECTED, MessageStatus.PENDING_APPROVAL, MessageStatus.REJECTED, null, null));
        adapter.cancelled(member(MessageEventType.CANCELLED, MessageStatus.APPROVED, MessageStatus.CANCELLED, null, null));
        adapter.sendRequested(member(MessageEventType.SEND_REQUESTED, MessageStatus.APPROVED, MessageStatus.QUEUED, null, null));
        adapter.sent(system(MessageEventType.SENT, MessageStatus.SENDING, MessageStatus.SENT, null, 1));
        adapter.sendOutcomeUnknown(system(MessageEventType.SEND_OUTCOME_UNKNOWN, MessageStatus.SENDING,
                MessageStatus.SENDING, null, 1));

        verify(recorder).messageTransition(eq(messageId), eq(AuditEventType.MESSAGE_APPROVED), any());
        verify(recorder).messageTransition(eq(messageId), eq(AuditEventType.MESSAGE_REJECTED), any());
        verify(recorder).messageTransition(eq(messageId), eq(AuditEventType.MESSAGE_CANCELLED), any());
        verify(recorder).messageTransition(eq(messageId), eq(AuditEventType.MESSAGE_SEND_REQUESTED), any());
        verify(recorder).messageTransition(eq(messageId), eq(AuditEventType.MESSAGE_SENT), any());
        verify(recorder).messageTransition(eq(messageId), eq(AuditEventType.MESSAGE_SEND_OUTCOME_UNKNOWN), any());
    }

    @Test
    void refusesAnEventHandedToTheWrongPortMethod() {
        MessageEvent approved = member(MessageEventType.APPROVED, MessageStatus.PENDING_APPROVAL,
                MessageStatus.APPROVED, null, null);

        assertThatThrownBy(() -> adapter.submitted(approved)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.sent(approved)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.deliveryUpdated(new ProviderContext(new TenantId(tenantId)), approved))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(recorder);
    }

    @Test
    void attemptStartIsNotAnAuditEvent() {
        assertThatThrownBy(() -> DurableMessageAuditAdapter.auditType(MessageEventType.SEND_ATTEMPT_STARTED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deliveryUpdateIsAuditedUnderTheProviderContextTenantWithoutAnActor() {
        ProviderContext provider = new ProviderContext(new TenantId(tenantId));
        MessageEvent delivered = system(MessageEventType.DELIVERY_UPDATED, MessageStatus.SENT,
                MessageStatus.DELIVERED, null, null);

        adapter.deliveryUpdated(provider, delivered);

        verify(recorder).messageDeliveryUpdated(new TenantId(tenantId), messageId,
                new AuditMetadata.MessageTransition(AuditMessageStatus.SENT, AuditMessageStatus.DELIVERED, null, null));
    }

    @Test
    void ignoredDeliveryReportsAreNeverAudited() {
        MessageEvent ignored = new MessageEvent(UUID.randomUUID(), tenantId, messageId, 8,
                MessageEventType.DELIVERY_UPDATED, MessageStatus.READ, MessageStatus.READ, false,
                MessageActorKind.SYSTEM, null, null, null, NOW);

        assertThatThrownBy(() -> adapter.deliveryUpdated(new ProviderContext(new TenantId(tenantId)), ignored))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.deliveryUpdated(null, ignored)).isInstanceOf(NullPointerException.class);
        verifyNoInteractions(recorder);
    }

    @ParameterizedTest
    @EnumSource(MessageStatus.class)
    void everyMessageStatusHasAnAuditMirror(MessageStatus status) {
        assertThat(DurableMessageAuditAdapter.status(status).name()).isEqualTo(status.name());
    }

    @ParameterizedTest
    @EnumSource(FailureCategory.class)
    void everyFailureCategoryHasAnAuditMirror(FailureCategory category) {
        assertThat(DurableMessageAuditAdapter.category(category).name()).isEqualTo(category.name());
    }

    private MessageEvent member(MessageEventType type, MessageStatus from, MessageStatus to,
            FailureCategory category, Integer attempt) {
        return new MessageEvent(UUID.randomUUID(), tenantId, messageId, 2, type, from, to, true,
                MessageActorKind.MEMBER, UUID.randomUUID(), category, attempt, NOW);
    }

    private MessageEvent system(MessageEventType type, MessageStatus from, MessageStatus to,
            FailureCategory category, Integer attempt) {
        return new MessageEvent(UUID.randomUUID(), tenantId, messageId, 5, type, from, to, true,
                MessageActorKind.SYSTEM, null, category, attempt, NOW);
    }
}
