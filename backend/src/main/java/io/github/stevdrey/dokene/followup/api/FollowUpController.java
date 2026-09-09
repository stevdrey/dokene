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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class FollowUpController {
    private final FollowUpService followUps;

    public FollowUpController(FollowUpService followUps) {
        this.followUps = followUps;
    }

    @GetMapping("/follow-up-policy")
    public TenantPolicyResponse tenantPolicy() {
        return response(followUps.tenantPolicy());
    }

    @PutMapping("/follow-up-policy")
    public TenantPolicyResponse configureTenant(@RequestBody TenantPolicyRequest request) {
        if (request == null || request.cadenceDays() == null || request.timeZone() == null) {
            throw new IllegalArgumentException("Cadence and time zone are required");
        }
        return response(followUps.configureTenant(request.cadenceDays(), ZoneId.of(request.timeZone())));
    }

    @GetMapping("/customers/{customerId}/follow-up-policy")
    public CustomerPolicyResponse customerPolicy(@PathVariable UUID customerId) {
        return response(followUps.customerPolicy(new CustomerId(customerId)));
    }

    @PutMapping("/customers/{customerId}/follow-up-policy")
    public CustomerPolicyResponse configureCustomer(@PathVariable UUID customerId,
            @RequestBody CustomerPolicyRequest request) {
        if (request == null) throw new IllegalArgumentException("Customer policy is required");
        return response(followUps.configureCustomer(new CustomerId(customerId), request.cadenceDays(),
                request.explicitNextDate()));
    }

    @GetMapping("/customers/{customerId}/follow-up-eligibility")
    public EvaluationResponse evaluate(@PathVariable UUID customerId) {
        return response(followUps.evaluate(new CustomerId(customerId)));
    }

    @PostMapping("/customers/{customerId}/manual-follow-ups")
    public CustomerPolicyResponse recordManualFollowUp(@PathVariable UUID customerId) {
        return response(followUps.recordManualFollowUp(new CustomerId(customerId)));
    }

    @PutMapping("/customers/{customerId}/follow-up-snooze")
    public CustomerPolicyResponse snooze(@PathVariable UUID customerId, @RequestBody SnoozeRequest request) {
        if (request == null || request.until() == null) throw new IllegalArgumentException("Snooze date is required");
        return response(followUps.snooze(new CustomerId(customerId), request.until()));
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

    public record TenantPolicyRequest(Integer cadenceDays, String timeZone) { }
    public record CustomerPolicyRequest(Integer cadenceDays, LocalDate explicitNextDate) { }
    public record SnoozeRequest(LocalDate until) { }
    public record TenantPolicyResponse(int cadenceDays, String timeZone) { }
    public record CustomerPolicyResponse(UUID customerId, Integer cadenceDays, LocalDate explicitNextDate,
                                         LocalDate snoozedUntil, LocalDate lastManualFollowUpDate) { }
    public record EvaluationResponse(UUID customerId, boolean eligible, FollowUpStatus status,
                                     List<FollowUpReason> reasons, Instant evaluatedAt, LocalDate tenantDate,
                                     String tenantTimeZone, LocalDate nextFollowUpDate,
                                     FollowUpTimingSource timingSource, int effectiveCadenceDays,
                                     Instant lastPurchaseAt) { }
}
