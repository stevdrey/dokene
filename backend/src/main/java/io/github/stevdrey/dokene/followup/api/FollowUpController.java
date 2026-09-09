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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class FollowUpController {
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private final FollowUpService followUps;

    public FollowUpController(FollowUpService followUps) {
        this.followUps = followUps;
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

    @GetMapping("/customers/{customerId}/follow-up-eligibility")
    public EvaluationResponse evaluate(@PathVariable UUID customerId) {
        return response(followUps.evaluate(new CustomerId(customerId)));
    }

    @PostMapping("/customers/{customerId}/manual-follow-ups")
    public ResponseEntity<ManualFollowUpResponse> recordManualFollowUp(@PathVariable UUID customerId,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        if (!IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new IllegalArgumentException("Invalid idempotency key");
        }
        var result = followUps.recordManualFollowUp(new CustomerId(customerId), version(ifMatch), idempotencyKey);
        var completion = result.completion();
        var response = new ManualFollowUpResponse(completion.id(), completion.customerId().value(),
                completion.completedOn(), completion.policyVersion());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .eTag(etag(completion.policyVersion())).body(response);
    }

    @PutMapping("/customers/{customerId}/follow-up-snooze")
    public ResponseEntity<CustomerPolicyResponse> snooze(@PathVariable UUID customerId,
            @RequestHeader("If-Match") String ifMatch, @RequestBody SnoozeRequest request) {
        if (request == null || request.until() == null) throw new IllegalArgumentException("Snooze date is required");
        var policy = followUps.snooze(new CustomerId(customerId), request.until(), version(ifMatch));
        return ResponseEntity.ok().eTag(etag(policy.version())).body(response(policy));
    }

    private TenantPolicyResponse response(TenantFollowUpPolicy policy) {
        return new TenantPolicyResponse(policy.cadenceDays(), policy.zoneId().getId());
    }

    private CustomerPolicyResponse response(CustomerFollowUpPolicy policy) {
        return new CustomerPolicyResponse(policy.customerId().value(), policy.cadenceDays(), policy.explicitNextDate(),
                policy.snoozedUntil(), policy.lastManualFollowUpDate());
    }

    private EvaluationResponse response(FollowUpEvaluation evaluation) {
        return new EvaluationResponse(evaluation.customerId().value(), evaluation.eligible(), evaluation.status(),
                evaluation.reasons(), evaluation.evaluatedAt(), evaluation.tenantDate(),
                evaluation.tenantZone().getId(), evaluation.nextFollowUpDate(), evaluation.timingSource(),
                evaluation.effectiveCadenceDays(), evaluation.lastPurchaseAt());
    }

    private long version(String ifMatch) {
        if (ifMatch == null || !ifMatch.matches("\"[0-9]+\"")) {
            throw new IllegalArgumentException("If-Match must contain one strong numeric ETag");
        }
        try {
            return Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid If-Match version", exception);
        }
    }

    private String etag(long version) { return "\"" + version + "\""; }

    public record TenantPolicyRequest(Integer cadenceDays, String timeZone) { }
    public record CustomerPolicyRequest(Integer cadenceDays, LocalDate explicitNextDate) { }
    public record SnoozeRequest(LocalDate until) { }
    public record TenantPolicyResponse(int cadenceDays, String timeZone) { }
    public record CustomerPolicyResponse(UUID customerId, Integer cadenceDays, LocalDate explicitNextDate,
                                         LocalDate snoozedUntil, LocalDate lastManualFollowUpDate) { }
    public record ManualFollowUpResponse(UUID id, UUID customerId, LocalDate completedOn, long policyVersion) { }
    public record EvaluationResponse(UUID customerId, boolean eligible, FollowUpStatus status,
                                     List<FollowUpReason> reasons, Instant evaluatedAt, LocalDate tenantDate,
                                     String tenantTimeZone, LocalDate nextFollowUpDate,
                                     FollowUpTimingSource timingSource, int effectiveCadenceDays,
                                     Instant lastPurchaseAt) { }
}
