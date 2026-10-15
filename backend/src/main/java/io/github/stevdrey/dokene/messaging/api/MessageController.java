// ABOUTME: /api/messages/** and /api/customers/{id}/messages: submit, read, approve or reject, cancel (ADR 0023 §3.1).
// ABOUTME: Thin transport layer over the frozen v1 contract; every rule and re-check lives in the messaging services.
package io.github.stevdrey.dokene.messaging.api;

import io.github.stevdrey.dokene.messaging.api.MessagingApiContract.ApprovalRequest;
import io.github.stevdrey.dokene.messaging.api.MessagingApiContract.ApprovalSummary;
import io.github.stevdrey.dokene.messaging.api.MessagingApiContract.AttemptSummary;
import io.github.stevdrey.dokene.messaging.api.MessagingApiContract.CancellationRequest;
import io.github.stevdrey.dokene.messaging.api.MessagingApiContract.MessageEventItem;
import io.github.stevdrey.dokene.messaging.api.MessagingApiContract.MessageEventsResponse;
import io.github.stevdrey.dokene.messaging.api.MessagingApiContract.MessageListItem;
import io.github.stevdrey.dokene.messaging.api.MessagingApiContract.MessagePageResponse;
import io.github.stevdrey.dokene.messaging.api.MessagingApiContract.MessageResponse;
import io.github.stevdrey.dokene.messaging.api.MessagingApiContract.SubmitMessageRequest;
import io.github.stevdrey.dokene.messaging.application.ApprovalCommand;
import io.github.stevdrey.dokene.messaging.application.CancellationCommand;
import io.github.stevdrey.dokene.messaging.application.CommandResult;
import io.github.stevdrey.dokene.messaging.application.OutboundMessageCommandService;
import io.github.stevdrey.dokene.messaging.application.OutboundMessageQueryService;
import io.github.stevdrey.dokene.messaging.application.MessageDetail;
import io.github.stevdrey.dokene.messaging.application.OutboundMessageRepository.ListedMessage;
import io.github.stevdrey.dokene.messaging.application.SubmitMessageCommand;
import io.github.stevdrey.dokene.messaging.domain.ApprovalDecision;
import io.github.stevdrey.dokene.messaging.domain.MessageEvent;
import io.github.stevdrey.dokene.messaging.domain.MessageStatus;
import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.messaging.domain.MessagingRefusedException;
import io.github.stevdrey.dokene.messaging.domain.OutboundMessage;
import io.github.stevdrey.dokene.messaging.domain.SendAttempt;
import io.github.stevdrey.dokene.tenant.api.StrictRequests;
import io.github.stevdrey.dokene.tenant.application.RequiredPermission;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * There is no edit route: a submitted body, recipient and template intent are immutable (ADR 0023 §2), so changing
 * the text means cancelling and submitting a new message, which needs a new approval. There is no route that accepts a
 * target status either; each transition is its own command resource with its own permission.
 */
@RestController
@RequestMapping("/api")
public class MessageController {
    private final OutboundMessageCommandService commands;
    private final OutboundMessageQueryService queries;

    public MessageController(OutboundMessageCommandService commands, OutboundMessageQueryService queries) {
        this.commands = commands;
        this.queries = queries;
    }

    /** T1. {@code If-Match} is the customer's follow-up policy version the operator's draft was made against. */
    @RequiredPermission(TenantPermission.MESSAGE_DRAFT)
    @PostMapping("/customers/{customerId}/messages")
    public ResponseEntity<MessageResponse> submit(@PathVariable UUID customerId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody(required = false) JsonNode body) {
        String version = version(ifMatch);
        SubmitMessageRequest request = read(body, SubmitMessageRequest.class);
        CommandResult result = commands.submit(new SubmitMessageCommand(customerId, request.contactId(),
                request.action().name(), request.templateIntent().name(), request.body(), request.locale(),
                request.origin().name(), version, idempotencyKey));
        return respond(result, true);
    }

    @RequiredPermission(TenantPermission.MESSAGE_READ)
    @GetMapping("/messages")
    public ResponseEntity<MessagePageResponse> list(@RequestParam(value = "status", required = false) List<String> status,
            @RequestParam(value = "customerId", required = false) UUID customerId,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "" + OutboundMessageQueryService.DEFAULT_LIMIT) int limit) {
        var page = queries.page(statuses(status), Optional.ofNullable(customerId), Optional.ofNullable(cursor),
                limit);
        return noStore(ResponseEntity.ok()).body(new MessagePageResponse(
                page.items().stream().map(MessageController::toListItem).toList(), page.nextCursor()));
    }

    /**
     * The ETag is the message version, the If-Match token for the next command, but {@code contactRemoved} can change
     * without it. Headers are set on the servlet response rather than a {@code ResponseEntity}, because Spring answers
     * a matching {@code If-None-Match} on an ETag-bearing {@code ResponseEntity} with {@code 304} even under
     * {@code no-store}, which would hide the live flag. As for the integration endpoint, never {@code 304}.
     */
    @RequiredPermission(TenantPermission.MESSAGE_READ)
    @GetMapping("/messages/{messageId}")
    public MessageResponse get(@PathVariable UUID messageId, HttpServletResponse response) {
        MessageDetail detail = queries.detail(messageId);
        response.setHeader(HttpHeaders.ETAG, StrictRequests.strongEtag(detail.message().version()));
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return toResponse(detail);
    }

    @RequiredPermission(TenantPermission.MESSAGE_READ)
    @GetMapping("/messages/{messageId}/events")
    public ResponseEntity<MessageEventsResponse> events(@PathVariable UUID messageId) {
        List<MessageEvent> events = queries.events(messageId);
        return noStore(ResponseEntity.ok()).body(new MessageEventsResponse(messageId,
                events.stream().map(MessageController::toEventItem).toList()));
    }

    /** T2 or T3. Approval binds the fingerprint (ADR 0024 §2) and calls no provider. */
    @RequiredPermission(TenantPermission.MESSAGE_APPROVE)
    @PostMapping("/messages/{messageId}/approvals")
    public ResponseEntity<MessageResponse> decide(@PathVariable UUID messageId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody(required = false) JsonNode body) {
        String version = version(ifMatch);
        ApprovalRequest request = read(body, ApprovalRequest.class);
        var command = new ApprovalCommand(messageId, request.note(), version, idempotencyKey);
        CommandResult result = request.decision() == ApprovalDecision.APPROVE ? commands.approve(command)
                : commands.reject(command);
        return respond(result, false);
    }

    /**
     * T4 or T5. Either permission admits the request here; the service then requires {@code MESSAGE_DRAFT} for a
     * pending message and {@code MESSAGE_APPROVE} for an approved one, under the message lock (ADR 0023 §5).
     */
    @RequiredPermission(value = {TenantPermission.MESSAGE_DRAFT, TenantPermission.MESSAGE_APPROVE}, anyOf = true)
    @PostMapping("/messages/{messageId}/cancellations")
    public ResponseEntity<MessageResponse> cancel(@PathVariable UUID messageId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody(required = false) JsonNode body) {
        String version = version(ifMatch);
        // The body is optional ({ note? }); an absent body cancels without a note.
        CancellationRequest request = body == null ? new CancellationRequest(null)
                : read(body, CancellationRequest.class);
        return respond(commands.cancel(new CancellationCommand(messageId, request.note(), version, idempotencyKey)),
                false);
    }

    /**
     * {@code 201} for a new record, {@code 200} for a replay; either way the body is the message's current
     * representation and the ETag is the version to send next (ADR 0023 §4.2). The detail was read inside the
     * command's transaction, so a committed command is never reported as failed by a second read.
     */
    private ResponseEntity<MessageResponse> respond(CommandResult result, boolean location) {
        MessageDetail detail = result.detail();
        ResponseEntity.BodyBuilder builder = result.created() ? ResponseEntity.status(HttpStatus.CREATED)
                : ResponseEntity.ok();
        if (result.created() && location) {
            builder.location(URI.create("/api/messages/" + detail.message().id()));
        }
        return noStore(builder).eTag(StrictRequests.strongEtag(detail.message().version())).body(toResponse(detail));
    }

    /**
     * {@code contactRemoved} is derived live from the customer's contacts, so a stored representation could go stale
     * without its version changing: never cache, never answer {@code 304}. Spring's automatic {@code If-None-Match}
     * handling only applies to a {@code GET} or {@code HEAD} returning an ETag-bearing {@code ResponseEntity}, so the
     * {@code POST} command responses here are safe and the detail {@code GET} sets its headers directly.
     */
    private static ResponseEntity.BodyBuilder noStore(ResponseEntity.BodyBuilder builder) {
        return builder.header(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    /** Missing stays null so the service refuses it; anything present but not one strong value is invalid input. */
    private static String version(String ifMatch) {
        if (ifMatch == null) {
            return null;
        }
        return Long.toString(StrictRequests.strongNumericVersion(ifMatch).orElseThrow(MessageController::invalid));
    }

    /**
     * At least as strict as the contract test (ADR 0024 §1): unknown members, unknown enum values, missing required
     * members and scalar coercions are all {@code 400 INVALID_INPUT}.
     */
    private static <T> T read(JsonNode body, Class<T> type) {
        return StrictRequests.read(body, type).orElseThrow(MessageController::invalid);
    }

    private static Set<MessageStatus> statuses(List<String> raw) {
        if (raw == null) {
            return Set.of();
        }
        var statuses = EnumSet.noneOf(MessageStatus.class);
        for (String value : raw) {
            try {
                statuses.add(MessageStatus.valueOf(value));
            } catch (IllegalArgumentException | NullPointerException unknown) {
                throw invalid();
            }
        }
        return statuses;
    }

    private static MessagingRefusedException invalid() {
        return new MessagingRefusedException(MessagingErrorCode.INVALID_INPUT);
    }

    private static MessageResponse toResponse(MessageDetail detail) {
        OutboundMessage m = detail.message();
        ApprovalSummary approval = detail.approval()
                .map(a -> new ApprovalSummary(a.decision(), a.decidedAt(), a.decidedByMembershipId()))
                .orElse(null);
        return new MessageResponse(m.id(), m.customerId(), m.contactId(), detail.contactRemoved(), m.recipientPhone(),
                m.channel(), m.status(), m.outcomeUnknown(), m.failureCategory(), m.action(), m.templateIntent(),
                m.locale(), m.origin(), m.body(), m.sourcePolicyVersion(), approval,
                detail.attempts().stream().map(MessageController::toAttempt).toList(), m.providerMessageId(),
                m.sentAt(), m.deliveredAt(), m.readAt(), m.createdByMembershipId(), m.createdAt(), m.updatedAt(),
                m.version());
    }

    private static AttemptSummary toAttempt(SendAttempt attempt) {
        return new AttemptSummary(attempt.attemptNumber(), attempt.startedAt(), attempt.outcome(),
                attempt.failureCategory());
    }

    private static MessageListItem toListItem(ListedMessage listed) {
        OutboundMessage m = listed.message();
        return new MessageListItem(m.id(), m.customerId(), listed.contactRemoved(), m.status(), m.outcomeUnknown(),
                m.action(), m.templateIntent(), m.origin(), m.createdAt(), m.updatedAt(), m.version());
    }

    private static MessageEventItem toEventItem(MessageEvent event) {
        return new MessageEventItem(event.sequenceNumber(), event.type(), event.statusFrom(), event.statusTo(),
                event.applied(), event.actorKind(), event.failureCategory(), event.attemptNumber(),
                event.occurredAt());
    }
}
