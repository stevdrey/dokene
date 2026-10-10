// ABOUTME: Unit tests for OutboundMessageQueryService: who may read, what is not found, which tenant is queried.
// ABOUTME: Covers spec 0002 AC-6 (MESSAGE_READ on every GET), AC-7 (NOT_FOUND, no tenant leak) and the post command read.
package io.github.stevdrey.dokene.messaging.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.stevdrey.dokene.messaging.domain.MessageActorKind;
import io.github.stevdrey.dokene.messaging.domain.MessageEvent;
import io.github.stevdrey.dokene.messaging.domain.MessageEventType;
import io.github.stevdrey.dokene.messaging.domain.MessageStatus;
import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.messaging.domain.MessagingRefusedException;
import io.github.stevdrey.dokene.messaging.domain.OutboundMessage;
import io.github.stevdrey.dokene.tenant.application.TenantAccessDeniedException;
import io.github.stevdrey.dokene.tenant.application.TenantAuthorizationService;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.application.TenantContextUnavailableException;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OutboundMessageQueryServiceTest {
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID MESSAGE_ID = UUID.randomUUID();

    private final OutboundMessageQueryRepository queries = mock(OutboundMessageQueryRepository.class);
    private final OutboundMessageRepository messages = mock(OutboundMessageRepository.class);
    private final TenantAuthorizationService authorization = mock(TenantAuthorizationService.class);
    private final TenantContextProvider contexts = mock(TenantContextProvider.class);
    private final OutboundMessageQueryService service =
            new OutboundMessageQueryService(queries, messages, authorization, contexts);

    @BeforeEach
    void inTenantContext() {
        when(contexts.requireCurrent()).thenReturn(new TenantContext(new TenantId(TENANT_ID),
                new IdentityId(UUID.randomUUID()), new TenantMembershipId(UUID.randomUUID()), TenantRole.OPERATOR));
    }

    @Test
    void detailRequiresMessageReadAndQueriesTheCurrentTenant() {
        OutboundMessage message = MessageDetailTest.message();
        MessageDetail detail = new MessageDetail(message, "Cliente", Optional.empty(), Optional.empty(), List.of(), false);
        when(queries.findDetail(TENANT_ID, MESSAGE_ID)).thenReturn(Optional.of(detail));

        assertThat(service.detail(MESSAGE_ID)).isSameAs(detail);

        verify(authorization).requirePermission(TenantPermission.MESSAGE_READ);
    }

    @Test
    void detailIsForbiddenWhenMessageReadIsDeniedAndNothingIsQueried() {
        doThrow(new TenantAccessDeniedException("no")).when(authorization)
                .requirePermission(TenantPermission.MESSAGE_READ);

        assertRefused(() -> service.detail(MESSAGE_ID), MessagingErrorCode.FORBIDDEN);
        verifyNoInteractions(queries);
    }

    @Test
    void detailIsNotFoundWhenTheTenantHasNoSuchMessage() {
        when(queries.findDetail(TENANT_ID, MESSAGE_ID)).thenReturn(Optional.empty());

        assertRefused(() -> service.detail(MESSAGE_ID), MessagingErrorCode.NOT_FOUND);
    }

    @Test
    void detailAfterCommandSkipsThePermissionCheckButStillScopesToTheTenant() {
        MessageDetail detail = new MessageDetail(MessageDetailTest.message(), "Cliente", Optional.empty(),
                Optional.empty(), List.of(), false);
        when(queries.findDetail(TENANT_ID, MESSAGE_ID)).thenReturn(Optional.of(detail));

        assertThat(service.detailAfterCommand(MESSAGE_ID)).isSameAs(detail);

        verify(authorization, never()).requirePermission(any());
    }

    @Test
    void listRequiresMessageReadAndPassesTheQueryThrough() {
        MessageListQuery query = new MessageListQuery(Set.of(MessageStatus.PENDING_APPROVAL), Optional.empty(),
                Optional.empty(), 10);
        MessagePage page = new MessagePage(List.of(), Optional.empty());
        when(queries.findPage(TENANT_ID, query)).thenReturn(page);

        assertThat(service.list(query)).isSameAs(page);

        verify(authorization).requirePermission(TenantPermission.MESSAGE_READ);
    }

    @Test
    void listIsForbiddenWithoutMessageRead() {
        doThrow(new TenantAccessDeniedException("no")).when(authorization)
                .requirePermission(TenantPermission.MESSAGE_READ);

        assertRefused(() -> service.list(new MessageListQuery(Set.of(), Optional.empty(), Optional.empty(), 1)),
                MessagingErrorCode.FORBIDDEN);
        verifyNoInteractions(queries);
    }

    @Test
    void eventsAreNotFoundWhenTheMessageIsNotInTheTenant() {
        when(messages.findById(TENANT_ID, MESSAGE_ID)).thenReturn(Optional.empty());

        assertRefused(() -> service.events(MESSAGE_ID), MessagingErrorCode.NOT_FOUND);
        verify(messages, never()).findEvents(any(), any());
    }

    @Test
    void eventsReturnTheLogOfAnExistingMessage() {
        OutboundMessage message = MessageDetailTest.message();
        MessageEvent submitted = new MessageEvent(UUID.randomUUID(), TENANT_ID, MESSAGE_ID, 1,
                MessageEventType.SUBMITTED, null, MessageStatus.PENDING_APPROVAL, true, MessageActorKind.MEMBER,
                UUID.randomUUID(), null, null, Instant.parse("2026-10-10T12:00:00Z"));
        when(messages.findById(TENANT_ID, MESSAGE_ID)).thenReturn(Optional.of(message));
        when(messages.findEvents(TENANT_ID, MESSAGE_ID)).thenReturn(List.of(submitted));

        assertThat(service.events(MESSAGE_ID)).containsExactly(submitted);

        verify(authorization).requirePermission(TenantPermission.MESSAGE_READ);
    }

    @Test
    void aMissingTenantContextIsForbiddenNotAnInternalError() {
        when(contexts.requireCurrent()).thenThrow(new TenantContextUnavailableException());

        assertRefused(() -> service.detail(MESSAGE_ID), MessagingErrorCode.FORBIDDEN);
        assertRefused(() -> service.detailAfterCommand(MESSAGE_ID), MessagingErrorCode.FORBIDDEN);
        assertRefused(() -> service.events(MESSAGE_ID), MessagingErrorCode.FORBIDDEN);
        verifyNoInteractions(queries);
    }

    @Test
    void refusesNullIdsAndQueries() {
        assertThatThrownBy(() -> service.detail(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.events(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.list(null)).isInstanceOf(NullPointerException.class);
    }

    private static void assertRefused(Runnable call, MessagingErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOf(MessagingRefusedException.class)
                .extracting(e -> ((MessagingRefusedException) e).code()).isEqualTo(code);
    }
}
