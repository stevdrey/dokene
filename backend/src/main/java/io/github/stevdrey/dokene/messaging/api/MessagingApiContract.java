// ABOUTME: Frozen v1 HTTP request, response and problem shapes for /api/messages/** (ADR 0023 §3.1, ADR 0024 §1).
// ABOUTME: Mirrored by frontend/src/features/messages/types and pinned by the shared contracts/messaging/v1 fixtures.
package io.github.stevdrey.dokene.messaging.api;

import io.github.stevdrey.dokene.ai.domain.SemanticAction;
import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import io.github.stevdrey.dokene.messaging.domain.ApprovalDecision;
import io.github.stevdrey.dokene.messaging.domain.FailureCategory;
import io.github.stevdrey.dokene.messaging.domain.MessageActorKind;
import io.github.stevdrey.dokene.messaging.domain.MessageApproval;
import io.github.stevdrey.dokene.messaging.domain.MessageChannel;
import io.github.stevdrey.dokene.messaging.domain.MessageEventType;
import io.github.stevdrey.dokene.messaging.domain.MessageOrigin;
import io.github.stevdrey.dokene.messaging.domain.MessageStatus;
import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.messaging.domain.SendAttemptOutcome;
import io.github.stevdrey.dokene.template.domain.TemplateButtonType;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Transport shapes, used by {@code MessageController}. Requests reject missing required members and out-of-contract
 * values at construction so a controller maps the failure to {@code 400 INVALID_INPUT}; business rules stay in the
 * service. Responses never carry secrets, provider payloads, notes or identity UUIDs.
 */
public final class MessagingApiContract {
    public static final String PROBLEM_MEDIA_TYPE = "application/problem+json";

    private MessagingApiContract() {
    }

    /** {@code POST /api/customers/{customerId}/messages}, executes T1. */
    public record SubmitMessageRequest(UUID contactId, SemanticAction action, SemanticTemplateIntent templateIntent,
            String body, String locale, MessageOrigin origin) {
        public SubmitMessageRequest {
            Objects.requireNonNull(contactId, "contactId is required");
            Objects.requireNonNull(action, "action is required");
            Objects.requireNonNull(templateIntent, "templateIntent is required");
            Objects.requireNonNull(body, "body is required");
            Objects.requireNonNull(locale, "locale is required");
            Objects.requireNonNull(origin, "origin is required");
        }
    }

    /**
     * {@code POST /api/messages/{messageId}/approvals}, executes T2 or T3. This client's approval names the template
     * preview it was given ({@code MessageTemplatePreview.revision}, issue 268); an older client's approval without one
     * is accepted for a default-locale message only (expand first), and a rejection carrying one is refused.
     */
    public record ApprovalRequest(ApprovalDecision decision, String note, String templateRevision) {
        private static final Pattern TEMPLATE_REVISION = Pattern.compile("^[0-9a-f]{64}$");

        public ApprovalRequest {
            Objects.requireNonNull(decision, "decision is required");
            requireNoteWithinLimit(note);
            // Expand first: an approval without a revision, from a client older than issue 268, stays valid here; the
            // service accepts it only for the default locale, which that client could already approve.
            if (decision == ApprovalDecision.REJECT && templateRevision != null) {
                // A rejection binds no preview; accepting one would let a retry change it under the same key.
                throw new IllegalArgumentException("templateRevision is only accepted to approve");
            }
            if (templateRevision != null && !TEMPLATE_REVISION.matcher(templateRevision).matches()) {
                throw new IllegalArgumentException("templateRevision must be 64 lowercase hex characters");
            }
        }
    }

    /** {@code POST /api/messages/{messageId}/cancellations}, executes T4 or T5. */
    public record CancellationRequest(String note) {
        public CancellationRequest {
            requireNoteWithinLimit(note);
        }
    }

    /** {@code POST /api/messages/{messageId}/resolutions}, executes T12; a human may only declare FAILED. */
    public record ResolutionRequest(ResolutionOutcome outcome, String note) {
        public ResolutionRequest {
            Objects.requireNonNull(outcome, "outcome is required");
            requireNoteWithinLimit(note);
        }
    }

    public enum ResolutionOutcome { FAILED }

    /**
     * Detail representation returned by reads and by every command (201 first, 200 on replay). {@code template} is the
     * provider template the message would be sent with, present only while it awaits approval or dispatch and a
     * sendable mapping in its language exists; otherwise null (issue 268).
     */
    public record MessageResponse(UUID messageId, UUID customerId, UUID contactId, boolean contactRemoved,
            String recipientPhone, MessageChannel channel, MessageStatus status, boolean outcomeUnknown,
            FailureCategory failureCategory, SemanticAction action, SemanticTemplateIntent templateIntent, String locale, MessageOrigin origin,
            String body, MessageTemplatePreview template, long sourcePolicyVersion, ApprovalSummary approval, List<AttemptSummary> attempts,
            String providerMessageId, Instant sentAt, Instant deliveredAt, Instant readAt,
            UUID createdByMembershipId, Instant createdAt, Instant updatedAt, long version) {
        public MessageResponse {
            attempts = List.copyOf(Objects.requireNonNull(attempts, "attempts is required"));
        }
    }

    /**
     * The template as the provider approved it, with placeholders left as written in {@code bodyText}, and the body as
     * the recipient reads it in {@code renderedBody}, every parameter filled in. {@code revision} identifies this exact
     * template, content and rendered values; a current client approves by naming it (issue 268, ApprovalRequest). When {@code parameterNames}
     * lacks {@code message_body} the message body is not sent and equals {@code renderedBody}.
     */
    public record MessageTemplatePreview(String revision, String providerTemplateName, String language,
            String headerText, String bodyText, String renderedBody, String footerText,
            List<MessageTemplateButton> buttons, List<String> parameterNames) {
        public MessageTemplatePreview {
            Objects.requireNonNull(revision, "revision is required");
            Objects.requireNonNull(providerTemplateName, "providerTemplateName is required");
            Objects.requireNonNull(language, "language is required");
            Objects.requireNonNull(bodyText, "bodyText is required");
            Objects.requireNonNull(renderedBody, "renderedBody is required");
            buttons = List.copyOf(Objects.requireNonNull(buttons, "buttons is required"));
            parameterNames = List.copyOf(Objects.requireNonNull(parameterNames, "parameterNames is required"));
        }
    }

    /** One fixed button of the template, in order; {@code value} is the phone number or URL, null for a quick reply. */
    public record MessageTemplateButton(TemplateButtonType type, String text, String value) {
        public MessageTemplateButton {
            Objects.requireNonNull(type, "type is required");
            Objects.requireNonNull(text, "text is required");
        }
    }

    /**
     * {@code GET /api/messaging/locales}: the locales a new message may be submitted in on this deployment, default
     * first (issue 268). {@code en-US} appears only once the deployment enables English writes; submit refuses any
     * locale not listed with {@code LOCALE_NOT_ACCEPTED}. The path is outside {@code /messages/{messageId}}, so an
     * older backend answers {@code 404}; a client then offers the default locale only.
     */
    public record MessageLocalesResponse(List<String> locales, String defaultLocale) {
        public MessageLocalesResponse {
            locales = List.copyOf(Objects.requireNonNull(locales, "locales is required"));
            Objects.requireNonNull(defaultLocale, "defaultLocale is required");
            if (!locales.contains(defaultLocale)) {
                throw new IllegalArgumentException("defaultLocale must be one of locales");
            }
        }
    }

    /** Membership UUID only, never an identity UUID or display name (ADR 0006); the note is never returned. */
    public record ApprovalSummary(ApprovalDecision decision, Instant decidedAt, UUID decidedByMembershipId) {
    }

    /** One provider attempt; the last element is the "last attempt" ADR 0023 §4.4 refers to. */
    public record AttemptSummary(int attemptNumber, Instant startedAt, SendAttemptOutcome outcome,
            FailureCategory failureCategory) {
    }

    /** {@code GET /api/messages}: ordered createdAt DESC, messageId DESC; items carry no body or phone. */
    public record MessagePageResponse(List<MessageListItem> items, String nextCursor) {
        public MessagePageResponse {
            items = List.copyOf(Objects.requireNonNull(items, "items is required"));
        }
    }

    public record MessageListItem(UUID messageId, UUID customerId, boolean contactRemoved, MessageStatus status,
            boolean outcomeUnknown, SemanticAction action, SemanticTemplateIntent templateIntent, String locale,
            MessageOrigin origin, Instant createdAt, Instant updatedAt, long version) {
    }

    /** {@code GET /api/messages/{messageId}/events}: append-only history, no provider payloads or membership ids. */
    public record MessageEventsResponse(UUID messageId, List<MessageEventItem> events) {
        public MessageEventsResponse {
            events = List.copyOf(Objects.requireNonNull(events, "events is required"));
        }
    }

    public record MessageEventItem(long sequenceNumber, MessageEventType type, MessageStatus statusFrom,
            MessageStatus statusTo, boolean applied, MessageActorKind actorKind, FailureCategory failureCategory,
            Integer attemptNumber, Instant occurredAt) {
    }

    /** RFC 9457 body with exactly these members (ADR 0023 §4.4); the status is fixed by the code. */
    public record MessagingProblem(String type, String title, int status, MessagingErrorCode code) {
        public static final String TYPE = "about:blank";

        public MessagingProblem {
            Objects.requireNonNull(code, "code is required");
            if (!TYPE.equals(type)) {
                throw new IllegalArgumentException("type must be " + TYPE);
            }
            if (status != statusOf(code)) {
                throw new IllegalArgumentException("status " + status + " does not match " + code);
            }
            if (!titleOf(status).equals(title)) {
                throw new IllegalArgumentException("title must be the reason phrase of " + status);
            }
        }

        public static MessagingProblem of(MessagingErrorCode code) {
            int status = statusOf(code);
            return new MessagingProblem(TYPE, titleOf(status), status, code);
        }

        public static int statusOf(MessagingErrorCode code) {
            return switch (code) {
                case INVALID_INPUT, LOCALE_NOT_ACCEPTED -> 400;
                case FORBIDDEN -> 403;
                case NOT_FOUND -> 404;
                case RATE_LIMITED -> 429;
                case PROVIDER_UNAVAILABLE -> 503;
                case INTERNAL_ERROR -> 500;
                case STALE_VERSION, IDEMPOTENCY_KEY_REUSED, INVALID_TRANSITION, CUSTOMER_ARCHIVED, DO_NOT_CONTACT,
                        NO_CONTACT_CONSENT, CONTACT_NOT_OWNED, FOLLOW_UP_INELIGIBLE, MESSAGE_ALREADY_OPEN,
                        CONTACT_FREQUENCY_EXCEEDED, TEMPLATE_NOT_MAPPED, BODY_REJECTED, OUTBOUND_DISABLED_GLOBALLY,
                        OUTBOUND_DISABLED_FOR_TENANT, INTEGRATION_DISABLED, SEND_ATTEMPTS_EXHAUSTED,
                        APPROVAL_INVALIDATED -> 409;
            };
        }

        private static String titleOf(int status) {
            return switch (status) {
                case 400 -> "Bad Request";
                case 403 -> "Forbidden";
                case 404 -> "Not Found";
                case 409 -> "Conflict";
                case 429 -> "Too Many Requests";
                case 500 -> "Internal Server Error";
                case 503 -> "Service Unavailable";
                default -> throw new IllegalArgumentException("Unexpected problem status " + status);
            };
        }
    }

    private static void requireNoteWithinLimit(String note) {
        if (note != null && note.length() > MessageApproval.MAX_NOTE_LENGTH) {
            throw new IllegalArgumentException("note exceeds " + MessageApproval.MAX_NOTE_LENGTH + " characters");
        }
    }
}
