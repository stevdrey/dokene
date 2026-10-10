// ABOUTME: HTTP surface of specs 0002 and 0003: submit, list, read, events, approve or reject, cancel, send.
// ABOUTME: Thin by design: parses headers and bodies, calls the spec 0001 services, answers from one read model.
package io.github.stevdrey.dokene.messaging.api;

import io.github.stevdrey.dokene.messaging.application.ApprovalCommand;
import io.github.stevdrey.dokene.messaging.application.CancellationCommand;
import io.github.stevdrey.dokene.messaging.application.CommandResult;
import io.github.stevdrey.dokene.messaging.application.MessageCursor;
import io.github.stevdrey.dokene.messaging.application.MessageDetail;
import io.github.stevdrey.dokene.messaging.application.MessageListQuery;
import io.github.stevdrey.dokene.messaging.application.MessagePage;
import io.github.stevdrey.dokene.messaging.application.MessageSummary;
import io.github.stevdrey.dokene.messaging.application.OutboundMessageCommandService;
import io.github.stevdrey.dokene.messaging.application.OutboundMessageQueryService;
import io.github.stevdrey.dokene.messaging.application.OutboundSendService;
import io.github.stevdrey.dokene.messaging.application.SendMessageCommand;
import io.github.stevdrey.dokene.messaging.application.SubmitMessageCommand;
import io.github.stevdrey.dokene.messaging.domain.ApprovalDecision;
import io.github.stevdrey.dokene.messaging.domain.MessageApproval;
import io.github.stevdrey.dokene.messaging.domain.MessageCancellation;
import io.github.stevdrey.dokene.messaging.domain.MessageEvent;
import io.github.stevdrey.dokene.messaging.domain.MessageStatus;
import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.messaging.domain.MessagingRefusedException;
import io.github.stevdrey.dokene.messaging.domain.OutboundMessage;
import io.github.stevdrey.dokene.messaging.domain.SendAttempt;
import io.github.stevdrey.dokene.tenant.application.RequiredPermission;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import io.github.stevdrey.dokene.web.IdempotencyKeys;
import io.github.stevdrey.dokene.web.StrongEtag;
import java.net.URI;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class MessageController {
    private final OutboundMessageCommandService commands;
    private final OutboundSendService sends;
    private final OutboundMessageQueryService queries;

    public MessageController(OutboundMessageCommandService commands, OutboundSendService sends,
            OutboundMessageQueryService queries) {
        this.commands = Objects.requireNonNull(commands, "Command service is required");
        this.sends = Objects.requireNonNull(sends, "Send service is required");
        this.queries = Objects.requireNonNull(queries, "Query service is required");
    }

    @RequiredPermission(TenantPermission.MESSAGE_DRAFT)
    @PostMapping("/customers/{customerId}/messages")
    public ResponseEntity<MessageDetailResponse> submit(@PathVariable UUID customerId,
            @RequestHeader("If-Match") String ifMatch, @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String accept,
            @RequestBody SubmitMessageRequest request) throws HttpMediaTypeNotAcceptableException {
        requireJsonAcceptable(accept);
        if (request == null) {
            throw invalidInput();
        }
        CommandResult result = commands.submit(new SubmitMessageCommand(customerId, request.contactId(),
                request.action(), request.templateIntent(), request.body(), request.locale(), request.origin(),
                version(ifMatch), key(idempotencyKey)));
        MessageDetail detail = queries.detailAfterCommand(result.message().id());
        var builder = result.created()
                ? ResponseEntity.created(URI.create("/api/messages/" + detail.message().id()))
                : ResponseEntity.ok();
        return builder.eTag(StrongEtag.format(detail.message().version())).body(MessageDetailResponse.from(detail));
    }

    @RequiredPermission(TenantPermission.MESSAGE_READ)
    @GetMapping("/messages")
    public MessageListResponse list(@RequestParam MultiValueMap<String, String> parameters,
            @RequestParam(required = false) UUID customerId, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        // Raw values: Spring would turn "status=" into an empty list, and an empty filter value must be refused.
        MessagePage page = queries.list(new MessageListQuery(statuses(parameters.get("status")),
                Optional.ofNullable(customerId), cursor(cursor), limit(limit)));
        return new MessageListResponse(page.items().stream().map(MessageListItem::from).toList(),
                page.next().map(MessageCursor::encode).orElse(null));
    }

    @RequiredPermission(TenantPermission.MESSAGE_READ)
    @GetMapping("/messages/{messageId}")
    public ResponseEntity<MessageDetailResponse> detail(@PathVariable UUID messageId) {
        MessageDetail detail = queries.detail(messageId);
        return ResponseEntity.ok().eTag(StrongEtag.format(detail.message().version()))
                .body(MessageDetailResponse.from(detail));
    }

    @RequiredPermission(TenantPermission.MESSAGE_READ)
    @GetMapping("/messages/{messageId}/events")
    public MessageEventsResponse events(@PathVariable UUID messageId) {
        return new MessageEventsResponse(queries.events(messageId).stream().map(MessageEventResponse::from).toList());
    }

    @RequiredPermission(TenantPermission.MESSAGE_APPROVE)
    @PostMapping("/messages/{messageId}/approvals")
    public ResponseEntity<MessageDetailResponse> approval(@PathVariable UUID messageId,
            @RequestHeader("If-Match") String ifMatch, @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String accept,
            @RequestBody ApprovalRequest request) throws HttpMediaTypeNotAcceptableException {
        requireJsonAcceptable(accept);
        if (request == null) {
            throw invalidInput();
        }
        ApprovalDecision decision = decision(request.decision());
        var command = new ApprovalCommand(messageId, request.note(), version(ifMatch), key(idempotencyKey));
        CommandResult result = switch (decision) {
            case APPROVE -> commands.approve(command);
            case REJECT -> commands.reject(command);
        };
        return commandResponse(result);
    }

    @RequiredPermission(value = {TenantPermission.MESSAGE_APPROVE, TenantPermission.MESSAGE_DRAFT}, anyOf = true)
    @PostMapping("/messages/{messageId}/cancellations")
    public ResponseEntity<MessageDetailResponse> cancellation(@PathVariable UUID messageId,
            @RequestHeader("If-Match") String ifMatch, @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String accept,
            @RequestBody(required = false) CancellationRequest request) throws HttpMediaTypeNotAcceptableException {
        requireJsonAcceptable(accept);
        String note = request == null ? null : request.note();
        return commandResponse(commands.cancel(
                new CancellationCommand(messageId, note, version(ifMatch), key(idempotencyKey))));
    }

    /** Spec 0003: no body; the attempt completes before the detail is read, so the ETag is the final version. */
    @RequiredPermission(TenantPermission.MESSAGE_SEND)
    @PostMapping("/messages/{messageId}/sends")
    public ResponseEntity<MessageDetailResponse> send(@PathVariable UUID messageId,
            @RequestHeader("If-Match") String ifMatch, @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String accept)
            throws HttpMediaTypeNotAcceptableException {
        requireJsonAcceptable(accept);
        return commandResponse(sends.send(new SendMessageCommand(messageId, version(ifMatch), key(idempotencyKey))));
    }

    private ResponseEntity<MessageDetailResponse> commandResponse(CommandResult result) {
        MessageDetail detail = queries.detailAfterCommand(result.message().id());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .eTag(StrongEtag.format(detail.message().version())).body(MessageDetailResponse.from(detail));
    }

    /**
     * A mutation negotiates Accept before it runs: otherwise the command would commit and the client would read
     * the 406 as "nothing happened". The read routes let Spring negotiate when it writes the body.
     */
    private static void requireJsonAcceptable(String accept) throws HttpMediaTypeNotAcceptableException {
        if (accept == null || accept.isBlank()) {
            return;
        }
        List<MediaType> accepted;
        try {
            accepted = MediaType.parseMediaTypes(accept);
            // Spring sorts when it writes the body and refuses more than 50 types; refuse the same way, but first.
            MimeTypeUtils.sortBySpecificity(accepted);
        } catch (IllegalArgumentException malformed) {
            throw new HttpMediaTypeNotAcceptableException(List.of(MediaType.APPLICATION_JSON));
        }
        if (accepted.stream().noneMatch(type -> type.isCompatibleWith(MediaType.APPLICATION_JSON))) {
            throw new HttpMediaTypeNotAcceptableException(List.of(MediaType.APPLICATION_JSON));
        }
    }

    private static String version(String ifMatch) {
        return Long.toString(StrongEtag.parse(ifMatch).orElseThrow(MessageController::invalidInput));
    }

    private static String key(String idempotencyKey) {
        if (!IdempotencyKeys.isValid(idempotencyKey)) {
            throw invalidInput();
        }
        return idempotencyKey;
    }

    private static ApprovalDecision decision(String value) {
        if (value == null) {
            throw invalidInput();
        }
        try {
            return ApprovalDecision.valueOf(value);
        } catch (IllegalArgumentException unknown) {
            throw invalidInput();
        }
    }

    private static Set<MessageStatus> statuses(List<String> values) {
        Set<MessageStatus> statuses = new HashSet<>();
        for (String value : values == null ? List.<String>of() : values) {
            if (value == null || value.isBlank()) {
                throw invalidInput();
            }
            try {
                statuses.add(MessageStatus.valueOf(value));
            } catch (IllegalArgumentException unknown) {
                throw invalidInput();
            }
        }
        return statuses;
    }

    private static Optional<MessageCursor> cursor(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(MessageCursor.decode(value).orElseThrow(MessageController::invalidInput));
    }

    private static int limit(Integer value) {
        int limit = value == null ? MessageListQuery.DEFAULT_LIMIT : value;
        if (limit < 1 || limit > MessageListQuery.MAX_LIMIT) {
            throw invalidInput();
        }
        return limit;
    }

    private static MessagingRefusedException invalidInput() {
        return new MessagingRefusedException(MessagingErrorCode.INVALID_INPUT);
    }

    public record SubmitMessageRequest(UUID contactId, String action, String templateIntent, String body,
            String locale, String origin) {
    }

    public record ApprovalRequest(String decision, String note) {
    }

    public record CancellationRequest(String note) {
    }

    public record MessageListResponse(List<MessageListItem> items, String nextCursor) {
    }

    public record MessageListItem(UUID messageId, UUID customerId, String customerDisplayName, UUID contactId,
            String recipientPhone, MessageStatus status, boolean outcomeUnknown, String action, String templateIntent,
            String locale, String origin, boolean contactRemoved, Instant createdAt, Instant updatedAt, long version) {
        static MessageListItem from(MessageSummary s) {
            return new MessageListItem(s.messageId(), s.customerId(), s.customerDisplayName(), s.contactId(),
                    s.recipientPhone(), s.status(), s.outcomeUnknown(), s.action().name(), s.templateIntent().name(),
                    s.locale(), s.origin().name(), s.contactRemoved(), s.createdAt(), s.updatedAt(), s.version());
        }
    }

    public record MessageDetailResponse(UUID messageId, UUID customerId, String customerDisplayName, UUID contactId,
            String recipientPhone, String channel, MessageStatus status, boolean outcomeUnknown, String action,
            String templateIntent, String locale, String origin, String body, long sourcePolicyVersion,
            ApprovalResponse approval, CancellationResponse cancellation, List<AttemptResponse> attempts,
            AttemptResponse lastAttempt, String providerMessageId, Instant sentAt, Instant deliveredAt,
            Instant readAt, UUID createdByMembershipId, boolean contactRemoved, Instant createdAt,
            Instant updatedAt, long version) {
        static MessageDetailResponse from(MessageDetail detail) {
            OutboundMessage m = detail.message();
            return new MessageDetailResponse(m.id(), m.customerId(), detail.customerDisplayName(), m.contactId(),
                    m.recipientPhone(), m.channel().name(), m.status(), m.outcomeUnknown(), m.action().name(),
                    m.templateIntent().name(), m.locale(), m.origin().name(), m.body(), m.sourcePolicyVersion(),
                    detail.approval().map(ApprovalResponse::from).orElse(null),
                    detail.cancellation().map(CancellationResponse::from).orElse(null),
                    detail.attempts().stream().map(AttemptResponse::from).toList(),
                    detail.lastAttempt().map(AttemptResponse::from).orElse(null),
                    m.providerMessageId(), m.sentAt(), m.deliveredAt(), m.readAt(), m.createdByMembershipId(),
                    detail.contactRemoved(), m.createdAt(), m.updatedAt(), m.version());
        }
    }

    public record ApprovalResponse(String decision, String note, Instant decidedAt, UUID decidedByMembershipId) {
        static ApprovalResponse from(MessageApproval a) {
            return new ApprovalResponse(a.decision().name(), a.note(), a.decidedAt(), a.decidedByMembershipId());
        }
    }

    public record CancellationResponse(MessageStatus statusFrom, String note, Instant cancelledAt,
            UUID cancelledByMembershipId) {
        static CancellationResponse from(MessageCancellation c) {
            return new CancellationResponse(c.statusFrom(), c.note(), c.cancelledAt(), c.cancelledByMembershipId());
        }
    }

    public record AttemptResponse(int attemptNumber, Instant startedAt, Instant completedAt, String outcome,
            String failureCategory) {
        static AttemptResponse from(SendAttempt a) {
            return new AttemptResponse(a.attemptNumber(), a.startedAt(), a.finishedAt(), a.outcome().name(),
                    a.failureCategory() == null ? null : a.failureCategory().name());
        }
    }

    public record MessageEventsResponse(List<MessageEventResponse> events) {
    }

    public record MessageEventResponse(long sequenceNumber, String eventType, MessageStatus statusFrom,
            MessageStatus statusTo, Instant occurredAt, String actorKind, UUID actorMembershipId, boolean applied,
            String failureCategory, Integer attemptNumber) {
        static MessageEventResponse from(MessageEvent e) {
            return new MessageEventResponse(e.sequenceNumber(), e.type().name(), e.statusFrom(), e.statusTo(),
                    e.occurredAt(), e.actorKind().name(), e.membershipId(), e.applied(),
                    e.failureCategory() == null ? null : e.failureCategory().name(), e.attemptNumber());
        }
    }
}
