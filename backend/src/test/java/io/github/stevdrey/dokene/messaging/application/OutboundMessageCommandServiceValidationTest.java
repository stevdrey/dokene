// ABOUTME: Unit tests for OutboundMessageCommandService input validation and the permission gate, before any lock.
// ABOUTME: Covers the INVALID_INPUT and FORBIDDEN clauses of AC-4 of spec 0001 with no repository interaction.
package io.github.stevdrey.dokene.messaging.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.stevdrey.dokene.customer.application.ContactPolicyRepository;
import io.github.stevdrey.dokene.customer.application.CustomerRepository;
import io.github.stevdrey.dokene.followup.application.DraftGroundingAssembler;
import io.github.stevdrey.dokene.followup.application.FollowUpService;
import io.github.stevdrey.dokene.messaging.domain.MessageOperation;
import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.messaging.domain.MessagingRefusedException;
import io.github.stevdrey.dokene.messaging.domain.RequestFingerprint;
import io.github.stevdrey.dokene.tenant.application.CurrentMembershipAuthorizer;
import io.github.stevdrey.dokene.tenant.application.TenantAccessDeniedException;
import io.github.stevdrey.dokene.tenant.application.TenantAuthorizationService;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OutboundMessageCommandServiceValidationTest {
    private static final String BODY = "Hola, ¿cómo le ha ido con su compra?";
    private static final TenantContext MEMBER = new TenantContext(TenantId.random(),
            new IdentityId(UUID.randomUUID()), TenantMembershipId.random(), TenantRole.OWNER);

    private final OutboundMessageRepository messages = mock(OutboundMessageRepository.class);
    private final MessageIdempotencyStore idempotency = mock(MessageIdempotencyStore.class);
    private final CustomerRepository customers = mock(CustomerRepository.class);
    private final ContactPolicyRepository contactPolicies = mock(ContactPolicyRepository.class);
    private final FollowUpService followUps = mock(FollowUpService.class);
    private final DraftGroundingAssembler grounding = mock(DraftGroundingAssembler.class);
    private final ApprovalBindingAssembler bindings = mock(ApprovalBindingAssembler.class);
    private final OutboundSafetyControl safety = mock(OutboundSafetyControl.class);
    private final TenantAuthorizationService authorization = mock(TenantAuthorizationService.class);
    private final TenantContextProvider contexts = mock(TenantContextProvider.class);
    private final MessageAuditPort audit = mock(MessageAuditPort.class);
    private final OutboundMessageCommandService service = service(true);

    private OutboundMessageCommandService service(boolean englishEnabled) {
        return new OutboundMessageCommandService(messages, idempotency, customers,
                new MessageSendGuards(contactPolicies, followUps, grounding), bindings, safety,
                new MessageCommandSupport(messages, authorization, contexts, mock(MessageTemplatePreviews.class)),
                mock(CurrentMembershipAuthorizer.class), audit,
                Clock.fixed(Instant.parse("2026-10-08T12:00:00Z"), ZoneOffset.UTC),
                new EnglishOutboundProperties(englishEnabled));
    }

    /**
     * Issue 268, expand first: en-US is refused until the deployment switch is on, before any customer or message is
     * touched, but only after the replay lookup, so a submission committed while the switch was on still replays.
     */
    @Test
    void refusesEnglishUntilTheDeploymentEnablesIt() {
        OutboundMessageCommandService off = service(false);
        when(contexts.requireCurrent()).thenReturn(MEMBER);
        when(idempotency.find(any(), any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> off.submit(submit(BODY, "en-US", "GENERAL_CHECK_IN", "GENERAL_FOLLOW_UP", "1", "k1")))
                .isInstanceOf(MessagingRefusedException.class)
                .extracting(e -> ((MessagingRefusedException) e).code()).isEqualTo(MessagingErrorCode.INVALID_INPUT);
        verifyNoInteractions(messages, customers, bindings, safety, audit);
        assertThat(new EnglishOutboundProperties(null).enabled()).isFalse();
    }

    @Test
    void consultsTheReplayBeforeTheEnglishSwitch() {
        OutboundMessageCommandService off = service(false);
        when(contexts.requireCurrent()).thenReturn(MEMBER);
        UUID tenantId = MEMBER.tenantId().value();
        when(idempotency.find(any(), any(), any())).thenReturn(Optional.of(new MessageIdempotencyStore.IdempotencyRecord(
                UUID.randomUUID(), tenantId, MessageOperation.SUBMIT, "k1", "another request", UUID.randomUUID(), null,
                Instant.parse("2026-10-08T11:00:00Z"))));

        // The stored record is reached: a different request under the key is refused as a reuse, not as input.
        assertThatThrownBy(() -> off.submit(submit(BODY, "en-US", "GENERAL_CHECK_IN", "GENERAL_FOLLOW_UP", "1", "k1")))
                .isInstanceOf(MessagingRefusedException.class).extracting(e -> ((MessagingRefusedException) e).code())
                .isEqualTo(MessagingErrorCode.IDEMPOTENCY_KEY_REUSED);
        verifyNoInteractions(customers);
    }

    /** A decision committed by the release before issue 268 named no revision; its exact retry still replays. */
    @Test
    void replaysADecisionStoredWithTheFourFieldFingerprint() {
        when(contexts.requireCurrent()).thenReturn(MEMBER);
        UUID messageId = UUID.randomUUID();
        String legacy = RequestFingerprint.of(MessageOperation.APPROVAL,
                Arrays.asList(messageId.toString(), "REJECT", null, "1"));
        when(idempotency.find(any(), any(), any())).thenReturn(Optional.of(new MessageIdempotencyStore.IdempotencyRecord(
                UUID.randomUUID(), MEMBER.tenantId().value(), MessageOperation.APPROVAL, "k1", legacy, messageId,
                UUID.randomUUID(), Instant.parse("2026-10-08T11:00:00Z"))));
        when(messages.findByIdForUpdate(any(), any())).thenReturn(Optional.empty());

        // The fingerprint matches, so the replay goes on to read the message rather than refusing a reused key.
        assertThatThrownBy(() -> service.reject(new ApprovalCommand(messageId, null, "1", "k1")))
                .isInstanceOf(MessagingRefusedException.class).extracting(e -> ((MessagingRefusedException) e).code())
                .isEqualTo(MessagingErrorCode.NOT_FOUND);
    }

    @Test
    void refusesMissingOrOversizedBodyBeforeTouchingAnything() {
        assertRefused(() -> service.submit(submit(null, "es-419", "GENERAL_CHECK_IN", "GENERAL_FOLLOW_UP", "1", "k1")),
                MessagingErrorCode.INVALID_INPUT);
        assertRefused(() -> service.submit(submit("   ", "es-419", "GENERAL_CHECK_IN", "GENERAL_FOLLOW_UP", "1", "k1")),
                MessagingErrorCode.INVALID_INPUT);
        assertRefused(() -> service.submit(submit("x".repeat(1001), "es-419", "GENERAL_CHECK_IN", "GENERAL_FOLLOW_UP",
                "1", "k1")), MessagingErrorCode.INVALID_INPUT);
    }

    @Test
    void refusesUnsupportedLocaleUnknownEnumsAndMissingIds() {
        for (String locale : new String[] {"fr-FR", "en-GB", "en_US", "en", "es", "EN-US", ""}) {
            assertRefused(() -> service.submit(submit(BODY, locale, "GENERAL_CHECK_IN", "GENERAL_FOLLOW_UP", "1", "k1")),
                    MessagingErrorCode.INVALID_INPUT);
        }
        assertRefused(() -> service.submit(submit(BODY, "es-419", "SELL_HARD", "GENERAL_FOLLOW_UP", "1", "k1")),
                MessagingErrorCode.INVALID_INPUT);
        assertRefused(() -> service.submit(submit(BODY, "es-419", "GENERAL_CHECK_IN", "NOT_AN_INTENT", "1", "k1")),
                MessagingErrorCode.INVALID_INPUT);
        assertRefused(() -> service.submit(new SubmitMessageCommand(null, UUID.randomUUID(), "GENERAL_CHECK_IN",
                "GENERAL_FOLLOW_UP", BODY, "es-419", "MANUAL", "1", "k1")), MessagingErrorCode.INVALID_INPUT);
        assertRefused(() -> service.submit(new SubmitMessageCommand(UUID.randomUUID(), UUID.randomUUID(),
                "GENERAL_CHECK_IN", "GENERAL_FOLLOW_UP", BODY, "es-419", "ROBOT", "1", "k1")),
                MessagingErrorCode.INVALID_INPUT);
    }

    /** AI drafts are generated and safety-checked in Spanish only; an English one cannot claim AI origin (issue 268). */
    @Test
    void refusesAnAiDraftInAnythingButTheDefaultLocale() {
        assertRefused(() -> service.submit(new SubmitMessageCommand(UUID.randomUUID(), UUID.randomUUID(),
                "GENERAL_CHECK_IN", "GENERAL_FOLLOW_UP", "Hello, how was your purchase?", "en-US", "AI_DRAFT", "1",
                "k1")), MessagingErrorCode.INVALID_INPUT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "-1", "1.5", "", "12345678901234567890"})
    void refusesIfMatchValuesThatAreNotAPlainNonNegativeVersion(String ifMatch) {
        assertRefused(() -> service.submit(submit(BODY, "es-419", "GENERAL_CHECK_IN", "GENERAL_FOLLOW_UP", ifMatch, "k1")),
                MessagingErrorCode.INVALID_INPUT);
    }

    @Test
    void refusesIdempotencyKeysOutsideTheAllowedShape() {
        assertRefused(() -> service.submit(submit(BODY, "es-419", "GENERAL_CHECK_IN", "GENERAL_FOLLOW_UP", "1", "")),
                MessagingErrorCode.INVALID_INPUT);
        assertRefused(() -> service.submit(submit(BODY, "es-419", "GENERAL_CHECK_IN", "GENERAL_FOLLOW_UP", "1",
                "has space")), MessagingErrorCode.INVALID_INPUT);
        assertRefused(() -> service.submit(submit(BODY, "es-419", "GENERAL_CHECK_IN", "GENERAL_FOLLOW_UP", "1",
                "k".repeat(129))), MessagingErrorCode.INVALID_INPUT);
        assertRefused(() -> service.submit(submit(BODY, "es-419", "GENERAL_CHECK_IN", "GENERAL_FOLLOW_UP", "1", null)),
                MessagingErrorCode.INVALID_INPUT);
    }

    @Test
    void refusesChildCommandsWithoutAMessageIdOrWithAnOversizedNote() {
        assertRefused(() -> service.approve(new ApprovalCommand(null, null, "1", "k1")), MessagingErrorCode.INVALID_INPUT);
        assertRefused(() -> service.reject(new ApprovalCommand(UUID.randomUUID(), "n".repeat(501), "1", "k1")),
                MessagingErrorCode.INVALID_INPUT);
        assertRefused(() -> service.cancel(new CancellationCommand(UUID.randomUUID(), null, "one", "k1")),
                MessagingErrorCode.INVALID_INPUT);
    }

    @Test
    void refusesNulCharactersInTheBodyAndInNotesBeforeTouchingAnything() {
        // Postgres refuses U+0000 in text columns; the module must answer INVALID_INPUT, not a persistence failure.
        assertRefused(() -> service.submit(submit("Hola\u0000mundo", "es-419", "GENERAL_CHECK_IN", "GENERAL_FOLLOW_UP",
                "1", "k1")), MessagingErrorCode.INVALID_INPUT);
        assertRefused(() -> service.approve(new ApprovalCommand(UUID.randomUUID(), "nota\u0000", "1", "k1")),
                MessagingErrorCode.INVALID_INPUT);
        assertRefused(() -> service.reject(new ApprovalCommand(UUID.randomUUID(), "\u0000", "1", "k1")),
                MessagingErrorCode.INVALID_INPUT);
        assertRefused(() -> service.cancel(new CancellationCommand(UUID.randomUUID(), "nota\u0000", "1", "k1")),
                MessagingErrorCode.INVALID_INPUT);
    }

    @Test
    void mapsAnAuthorizationDenialToForbiddenBeforeAnyLock() {
        doThrow(new TenantAccessDeniedException()).when(authorization).requirePermission(any());

        assertRefused(() -> service.submit(submit(BODY, "es-419", "GENERAL_CHECK_IN", "GENERAL_FOLLOW_UP", "1", "k1")),
                MessagingErrorCode.FORBIDDEN);
        assertRefused(() -> service.approve(new ApprovalCommand(UUID.randomUUID(), null, "1", "k1")),
                MessagingErrorCode.FORBIDDEN);
    }

    private void assertRefused(Callable<?> call, MessagingErrorCode code) {
        assertThatThrownBy(call::call).isInstanceOf(MessagingRefusedException.class)
                .extracting(e -> ((MessagingRefusedException) e).code()).isEqualTo(code);
        verifyNoInteractions(messages, idempotency, customers, contactPolicies, followUps, grounding, bindings, safety,
                audit);
    }

    private static SubmitMessageCommand submit(String body, String locale, String action, String intent, String ifMatch,
            String key) {
        return new SubmitMessageCommand(UUID.randomUUID(), UUID.randomUUID(), action, intent, body, locale, "MANUAL",
                ifMatch, key);
    }
}
