// ABOUTME: Recovery of exhausted follow-up work (issue 461, ADR 0038): GET lists items awaiting a decision with
// ABOUTME: FOLLOWUP_READ; POST applies REQUEUE or RESOLVE with FOLLOWUP_WRITE under If-Match on the recovery count.
package io.github.stevdrey.dokene.followup.api;

import io.github.stevdrey.dokene.followup.application.FollowUpWorkRecoveryService;
import io.github.stevdrey.dokene.followup.domain.FollowUpRecoverableWork;
import io.github.stevdrey.dokene.followup.domain.FollowUpWorkRecoveryAction;
import io.github.stevdrey.dokene.http.StrictRequests;
import io.github.stevdrey.dokene.tenant.application.RequiredPermission;
import io.github.stevdrey.dokene.tenant.application.TenantAccessDeniedException;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import tools.jackson.databind.JsonNode;

/**
 * Responses carry ids, safe failure categories, counts and instants only. A refused decision is {@code 409} with its
 * reason; an applied decision is {@code 200} with the item and its new recovery count as the strong ETag, and repeating
 * it with the same If-Match returns the same item again without a second change.
 */
@RestController
@RequestMapping("/api/follow-up-work/recovery")
public class FollowUpWorkRecoveryController {
    private static final String MEMBER = "action";

    private final FollowUpWorkRecoveryService recovery;

    public FollowUpWorkRecoveryController(FollowUpWorkRecoveryService recovery) {
        this.recovery = Objects.requireNonNull(recovery, "Recovery service is required");
    }

    @RequiredPermission(TenantPermission.FOLLOWUP_READ)
    @GetMapping
    public List<RecoverableWorkResponse> recoverable(@RequestParam(defaultValue = "50") int limit) {
        return recovery.recoverable(limit).stream().map(FollowUpWorkRecoveryController::response).toList();
    }

    /** The body must be exactly {@code {"action": "REQUEUE"}} or {@code {"action": "RESOLVE"}}. */
    @RequiredPermission(TenantPermission.FOLLOWUP_WRITE)
    @PostMapping("/{candidateId}")
    public ResponseEntity<RecoverableWorkResponse> recover(@PathVariable UUID candidateId,
            @RequestHeader("If-Match") String ifMatch, @RequestBody(required = false) JsonNode body) {
        long expected = StrictRequests.strongNumericVersion(ifMatch)
                .orElseThrow(() -> new IllegalArgumentException("If-Match must be one strong numeric ETag"));
        if (expected > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("If-Match is out of range");
        }
        if (body == null || !body.isObject() || !body.has(MEMBER)) {
            throw new IllegalArgumentException("Recovery action is required");
        }
        RecoveryRequest request = StrictRequests.read(body, RecoveryRequest.class)
                .orElseThrow(() -> new IllegalArgumentException("Recovery request is malformed"));
        if (request.action() == null) {
            throw new IllegalArgumentException("Recovery action is required");
        }
        FollowUpWorkRecoveryAction action = FollowUpWorkRecoveryAction.valueOf(request.action());
        FollowUpWorkRecoveryService.Recovery result = recovery.recover(candidateId, action, (int) expected);
        return ResponseEntity.ok().eTag(StrictRequests.strongEtag(result.item().recoveryCount()))
                .body(response(result.item()));
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class,
            MissingRequestHeaderException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<Void> invalidInput() {
        return ResponseEntity.badRequest().build();
    }

    @ExceptionHandler(FollowUpWorkRecoveryService.RecoverableWorkNotFoundException.class)
    ResponseEntity<Void> notFound() {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(FollowUpWorkRecoveryService.RecoveryRefusedException.class)
    ResponseEntity<RefusalResponse> refused(FollowUpWorkRecoveryService.RecoveryRefusedException refusal) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new RefusalResponse(refusal.refusal().name()));
    }

    @ExceptionHandler(TenantAccessDeniedException.class)
    ResponseEntity<Void> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    private static RecoverableWorkResponse response(FollowUpRecoverableWork item) {
        return new RecoverableWorkResponse(item.candidateId(), item.customerId().value(), item.dueDate(),
                item.state().name(), item.lastFailure().name(), item.outcomeUnknown(), item.attempts(),
                item.finishedAt().orElse(null), item.recoveryCount(),
                item.lastRecoveryAction().map(Enum::name).orElse(null));
    }

    public record RecoveryRequest(String action) {
    }

    /** {@code outcomeUnknown} items may only be resolved; {@code recoveryCount} is the version a decision names. */
    public record RecoverableWorkResponse(UUID candidateId, UUID customerId, LocalDate dueDate, String state,
            String lastFailure, boolean outcomeUnknown, int attempts, Instant finishedAt, int recoveryCount,
            String lastRecoveryAction) {
    }

    public record RefusalResponse(String reason) {
    }
}
