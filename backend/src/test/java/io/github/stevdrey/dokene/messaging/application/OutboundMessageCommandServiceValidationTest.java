// ABOUTME: Unit tests for OutboundMessageCommandService input validation and the permission gate, before any lock.
// ABOUTME: Covers the INVALID_INPUT and FORBIDDEN clauses of AC-4 of spec 0001 with no repository interaction.
package io.github.stevdrey.dokene.messaging.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.github.stevdrey.dokene.customer.application.ContactPolicyRepository;
import io.github.stevdrey.dokene.customer.application.CustomerRepository;
import io.github.stevdrey.dokene.followup.application.DraftGroundingAssembler;
import io.github.stevdrey.dokene.followup.application.FollowUpService;
import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.messaging.domain.MessagingRefusedException;
import io.github.stevdrey.dokene.tenant.application.TenantAccessDeniedException;
import io.github.stevdrey.dokene.tenant.application.TenantAuthorizationService;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OutboundMessageCommandServiceValidationTest {
    private static final String BODY = "Hola, ¿cómo le ha ido con su compra?";

    private final OutboundMessageRepository messages = mock(OutboundMessageRepository.class);
    private final MessageIdempotencyStore idempotency = mock(MessageIdempotencyStore.class);
    private final CustomerRepository customers = mock(CustomerRepository.class);
    private final ContactPolicyRepository contactPolicies = mock(ContactPolicyRepository.class);
    private final FollowUpService followUps = mock(FollowUpService.class);
    private final DraftGroundingAssembler grounding = mock(DraftGroundingAssembler.class);
    private final TemplateMappingGate templates = mock(TemplateMappingGate.class);
    private final TenantAuthorizationService authorization = mock(TenantAuthorizationService.class);
    private final TenantContextProvider contexts = mock(TenantContextProvider.class);
    private final MessageAuditPort audit = mock(MessageAuditPort.class);
    private final OutboundMessageCommandService service = new OutboundMessageCommandService(messages, idempotency,
            customers, contactPolicies, followUps, grounding, templates, authorization, contexts, audit,
            Clock.fixed(Instant.parse("2026-10-08T12:00:00Z"), ZoneOffset.UTC));

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
        assertRefused(() -> service.submit(submit(BODY, "en-US", "GENERAL_CHECK_IN", "GENERAL_FOLLOW_UP", "1", "k1")),
                MessagingErrorCode.INVALID_INPUT);
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
        verifyNoInteractions(messages, idempotency, customers, contactPolicies, followUps, grounding, templates, audit);
    }

    private static SubmitMessageCommand submit(String body, String locale, String action, String intent, String ifMatch,
            String key) {
        return new SubmitMessageCommand(UUID.randomUUID(), UUID.randomUUID(), action, intent, body, locale, "MANUAL",
                ifMatch, key);
    }
}
