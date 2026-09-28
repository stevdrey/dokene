package io.github.stevdrey.dokene.followup.api;

import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.followup.application.FollowUpService;
import io.github.stevdrey.dokene.followup.domain.CustomerFollowUpPolicy;
import io.github.stevdrey.dokene.followup.domain.FollowUpEvaluation;
import io.github.stevdrey.dokene.followup.domain.FollowUpReason;
import io.github.stevdrey.dokene.followup.domain.FollowUpStatus;
import io.github.stevdrey.dokene.followup.domain.FollowUpTimingSource;
import io.github.stevdrey.dokene.followup.domain.TenantFollowUpPolicy;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import io.github.stevdrey.dokene.followup.application.FollowUpQueueCursor;
import io.github.stevdrey.dokene.followup.application.FollowUpQueueQuery;
import io.github.stevdrey.dokene.followup.domain.FollowUpQueueItem;
import io.github.stevdrey.dokene.ai.domain.NoRecommendationReason;
import io.github.stevdrey.dokene.ai.domain.SemanticAction;
import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import io.github.stevdrey.dokene.followup.application.ActionGateRejectionReason;
import io.github.stevdrey.dokene.followup.application.FollowUpRecommendationResult;
import io.github.stevdrey.dokene.followup.application.FollowUpRecommendationService;
import io.github.stevdrey.dokene.followup.application.RecommendationStatus;
import java.time.Duration;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.stevdrey.dokene.tenant.application.TenantAuthorizationService;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import io.github.stevdrey.dokene.followup.application.FollowUpRecommendationRateLimiter;

@RestController
@RequestMapping("/api")
public class FollowUpController {
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private final FollowUpService followUps;
    private final FollowUpRecommendationService recommendations;
    private final TenantContextProvider contexts;
    private final FollowUpRecommendationRateLimiter rateLimiter;
    private final TenantAuthorizationService authorization;

    @org.springframework.beans.factory.annotation.Autowired
    public FollowUpController(
            FollowUpService followUps,
            FollowUpRecommendationService recommendations,
            TenantContextProvider contexts,
            FollowUpRecommendationRateLimiter rateLimiter,
            TenantAuthorizationService authorization) {
        this.followUps = followUps;
        this.recommendations = recommendations;
        this.contexts = contexts;
        this.rateLimiter = rateLimiter;
        this.authorization = authorization;
    }

    public FollowUpController(
            FollowUpService followUps,
            FollowUpRecommendationService recommendations,
            TenantContextProvider contexts,
            FollowUpRecommendationRateLimiter rateLimiter) {
        this(followUps, recommendations, contexts, rateLimiter, null);
    }

    public FollowUpController(FollowUpService followUps, FollowUpRecommendationService recommendations) {
        this(followUps, recommendations, null, null);
    }

    public FollowUpController(FollowUpService followUps) {
        this(followUps, null, null, null);
    }

    @GetMapping("/follow-up-policy")
    public ResponseEntity<TenantPolicyResponse> tenantPolicy() {
        var policy = followUps.tenantPolicy();
        return ResponseEntity.ok().eTag(etag(policy.version())).body(response(policy));
    }

    @PutMapping("/follow-up-policy")
    public ResponseEntity<TenantPolicyResponse> configureTenant(@RequestHeader("If-Match") String ifMatch,
            @RequestBody TenantPolicyRequest request) {
        if (request == null || request.cadenceDays() == null || request.timeZone() == null) {
            throw new IllegalArgumentException("Cadence and time zone are required");
        }
        var policy = followUps.configureTenant(request.cadenceDays(), ZoneId.of(request.timeZone()), version(ifMatch));
        return ResponseEntity.ok().eTag(etag(policy.version())).body(response(policy));
    }

    @GetMapping("/customers/{customerId}/follow-up-policy")
    public ResponseEntity<CustomerPolicyResponse> customerPolicy(@PathVariable UUID customerId) {
        var policy = followUps.customerPolicy(new CustomerId(customerId));
        return ResponseEntity.ok().eTag(etag(policy.version())).body(response(policy));
    }

    @PutMapping("/customers/{customerId}/follow-up-policy")
    public ResponseEntity<CustomerPolicyResponse> configureCustomer(@PathVariable UUID customerId,
            @RequestHeader("If-Match") String ifMatch,
            @RequestBody CustomerPolicyRequest request) {
        if (request == null) throw new IllegalArgumentException("Customer policy is required");
        var policy = followUps.configureCustomer(new CustomerId(customerId), request.cadenceDays(),
                request.explicitNextDate(), version(ifMatch));
        return ResponseEntity.ok().eTag(etag(policy.version())).body(response(policy));
    }

    @GetMapping("/follow-up-queue")
    public FollowUpQueuePageResponse dueQueue(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        FollowUpStatus statusFilter = null;
        if (status != null && !status.isBlank()) {
            try {
                statusFilter = FollowUpStatus.valueOf(status.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Status filter must be DUE or OVERDUE");
            }
        }
        FollowUpQueueCursor cursorObj = cursor != null && !cursor.isBlank()
                ? FollowUpQueueCursor.decode(cursor) : null;
        var page = followUps.dueQueue(new FollowUpQueueQuery(statusFilter, cursorObj, limit));
        var items = page.items().stream().map(this::response).toList();
        return new FollowUpQueuePageResponse(items, page.nextCursor());
    }

    @GetMapping("/customers/{customerId}/follow-up-eligibility")
    public EvaluationResponse evaluate(@PathVariable UUID customerId) {
        return response(followUps.evaluate(new CustomerId(customerId)));
    }

    @PostMapping("/customers/{customerId}/manual-follow-ups")
    public ResponseEntity<ManualFollowUpResponse> recordManualFollowUp(@PathVariable UUID customerId,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody(required = false) ManualFollowUpRequest request) {
        if (!IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new IllegalArgumentException("Invalid idempotency key");
        }
        String notes = validateNotes(request != null ? request.notes() : null);
        var result = followUps.recordManualFollowUp(new CustomerId(customerId), version(ifMatch), idempotencyKey, notes);
        var completion = result.completion();
        var response = new ManualFollowUpResponse(completion.id(), completion.customerId().value(),
                completion.completedOn(), completion.policyVersion(), completion.notes());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .eTag(etag(completion.policyVersion())).body(response);
    }

    @PostMapping("/customers/{customerId}/follow-up-dismissals")
    public ResponseEntity<DismissalResponse> dismiss(@PathVariable UUID customerId,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody(required = false) DismissRequest request) {
        if (!IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new IllegalArgumentException("Invalid idempotency key");
        }
        String notes = validateNotes(request != null ? request.notes() : null);
        var result = followUps.dismiss(new CustomerId(customerId), version(ifMatch), idempotencyKey, notes);
        var dismissal = result.dismissal();
        var response = new DismissalResponse(dismissal.id(), dismissal.customerId().value(),
                dismissal.dismissedOn(), dismissal.policyVersion(), dismissal.notes());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .eTag(etag(dismissal.policyVersion())).body(response);
    }

    @PutMapping("/customers/{customerId}/follow-up-snooze")
    public ResponseEntity<CustomerPolicyResponse> snooze(@PathVariable UUID customerId,
            @RequestHeader("If-Match") String ifMatch, @RequestBody SnoozeRequest request) {
        if (request == null || request.until() == null) throw new IllegalArgumentException("Snooze date is required");
        var policy = followUps.snooze(new CustomerId(customerId), request.until(), version(ifMatch));
        return ResponseEntity.ok().eTag(etag(policy.version())).body(response(policy));
    }

    @PostMapping({"/customers/{customerId}/recommendation", "/customers/{customerId}/follow-up-recommendation"})
    public ResponseEntity<RecommendationResponse> requestRecommendation(
            @PathVariable UUID customerId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody(required = false) RecommendationRequest request) {
        if (recommendations == null) {
            throw new IllegalStateException("Recommendation service is not configured");
        }
        if (authorization != null) {
            authorization.requirePermission(TenantPermission.FOLLOWUP_EVALUATE);
        }
        if (rateLimiter != null && contexts != null) {
            var tenantContext = contexts.requireCurrent();
            rateLimiter.acquire(tenantContext.tenantId(), tenantContext.identityId());
        }
        Long expectedVersion = (ifMatch != null && !ifMatch.isBlank()) ? version(ifMatch) : null;
        Duration timeout = request != null && request.timeoutMs() != null
                ? Duration.ofMillis(request.timeoutMs())
                : null;
        var result = recommendations.recommendSafe(new CustomerId(customerId), timeout, expectedVersion);
        return ResponseEntity.ok()
                .eTag(etag(result.policyVersion()))
                .body(response(result));
    }

    private RecommendationResponse response(FollowUpRecommendationResult result) {
        EvaluationResponse evalResponse = result.evaluation() != null ? response(result.evaluation()) : null;
        ActionRecommendationResponse actionResponse = null;
        if (result.recommendation() != null) {
            var action = result.recommendation();
            var vars = action.draftVariables() != null
                    ? action.draftVariables().entries().stream()
                            .map(e -> new DraftVariableResponse(e.key(), e.value()))
                            .toList()
                    : List.<DraftVariableResponse>of();
            actionResponse = new ActionRecommendationResponse(
                    action.action(),
                    action.templateIntent(),
                    action.rationale(),
                    action.confidence().value(),
                    vars);
        }
        RefusalResponse refusalResponse = null;
        if (result.refusal() != null) {
            refusalResponse = new RefusalResponse(
                    result.refusal().reason(),
                    result.refusal().rationale(),
                    result.refusal().confidence().value());
        }
        UUID cId = result.evaluation() != null
                ? result.evaluation().customerId().value()
                : null;
        return new RecommendationResponse(
                result.status(),
                cId,
                evalResponse,
                actionResponse,
                refusalResponse,
                result.refusalReason(),
                result.rejectionReason(),
                result.unavailableReason());
    }

    private TenantPolicyResponse response(TenantFollowUpPolicy policy) {
        return new TenantPolicyResponse(policy.cadenceDays(), policy.zoneId().getId());
    }

    private CustomerPolicyResponse response(CustomerFollowUpPolicy policy) {
        return new CustomerPolicyResponse(policy.customerId().value(), policy.cadenceDays(), policy.explicitNextDate(),
                policy.snoozedUntil(), policy.lastManualFollowUpDate(), policy.lastDismissedDate());
    }

    private EvaluationResponse response(FollowUpEvaluation evaluation) {
        return new EvaluationResponse(evaluation.customerId().value(), evaluation.eligible(), evaluation.status(),
                evaluation.reasons(), evaluation.evaluatedAt(), evaluation.tenantDate(),
                evaluation.tenantZone().getId(), evaluation.nextFollowUpDate(), evaluation.timingSource(),
                evaluation.effectiveCadenceDays(), evaluation.lastPurchaseAt());
    }

    private QueueItemResponse response(FollowUpQueueItem item) {
        return new QueueItemResponse(item.customerId().value(), item.displayName(), item.primaryPhone(),
                item.status(), item.reasons(), item.dueDate(), item.timingSource(), item.policyVersion(),
                item.effectiveCadenceDays(), item.lastPurchaseAt(), item.lastManualFollowUpDate(),
                item.lastDismissedDate(), item.evaluatedAt());
    }

    private long version(String ifMatch) {
        if (ifMatch == null || !ifMatch.matches("\"(0|[1-9][0-9]*)\"")) {
            throw new IllegalArgumentException("If-Match must contain one strong numeric ETag");
        }
        try {
            long version = Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1));
            if (!etag(version).equals(ifMatch)) {
                throw new IllegalArgumentException("If-Match must contain one strong numeric ETag");
            }
            return version;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid If-Match version", exception);
        }
    }

    private String validateNotes(String notes) {
        if (notes != null && notes.length() > 500) {
            throw new IllegalArgumentException("Notes cannot exceed 500 characters");
        }
        return notes;
    }

    private String etag(long version) { return "\"" + version + "\""; }

    public record TenantPolicyRequest(Integer cadenceDays, String timeZone) { }
    public record CustomerPolicyRequest(Integer cadenceDays, LocalDate explicitNextDate) { }
    public record SnoozeRequest(LocalDate until) { }
    public record ManualFollowUpRequest(String notes) { }
    public record DismissRequest(String notes) { }
    public record TenantPolicyResponse(int cadenceDays, String timeZone) { }
    public record CustomerPolicyResponse(UUID customerId, Integer cadenceDays, LocalDate explicitNextDate,
                                         LocalDate snoozedUntil, LocalDate lastManualFollowUpDate,
                                         LocalDate lastDismissedDate) { }
    public record ManualFollowUpResponse(UUID id, UUID customerId, LocalDate completedOn,
                                         long policyVersion, String notes) { }
    public record DismissalResponse(UUID id, UUID customerId, LocalDate dismissedOn,
                                    long policyVersion, String notes) { }
    public record EvaluationResponse(UUID customerId, boolean eligible, FollowUpStatus status,
                                     List<FollowUpReason> reasons, Instant evaluatedAt, LocalDate tenantDate,
                                     String tenantTimeZone, LocalDate nextFollowUpDate,
                                     FollowUpTimingSource timingSource, int effectiveCadenceDays,
                                     Instant lastPurchaseAt) { }
    public record QueueItemResponse(UUID customerId, String displayName, String primaryPhone,
                                    FollowUpStatus status, List<FollowUpReason> reasons, LocalDate dueDate,
                                    FollowUpTimingSource timingSource, long policyVersion,
                                    int effectiveCadenceDays, Instant lastPurchaseAt,
                                    LocalDate lastManualFollowUpDate, LocalDate lastDismissedDate,
                                    Instant evaluatedAt) { }
    public record FollowUpQueuePageResponse(List<QueueItemResponse> items, String nextCursor) { }
    public record RecommendationRequest(Integer timeoutMs) { }
    public record DraftVariableResponse(String key, String value) { }
    public record ActionRecommendationResponse(
            SemanticAction action,
            SemanticTemplateIntent templateIntent,
            String rationale,
            double confidence,
            List<DraftVariableResponse> draftVariables) { }
    public record RefusalResponse(
            NoRecommendationReason reason,
            String rationale,
            double confidence) { }
    public record RecommendationResponse(
            RecommendationStatus status,
            UUID customerId,
            EvaluationResponse evaluation,
            ActionRecommendationResponse recommendation,
            RefusalResponse refusal,
            NoRecommendationReason refusalReason,
            ActionGateRejectionReason rejectionReason,
            String unavailableReason) { }
}
