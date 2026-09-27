package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.ai.application.RecommendationContext;
import io.github.stevdrey.dokene.ai.application.RecommendationContextException;
import io.github.stevdrey.dokene.ai.domain.SemanticAction;
import io.github.stevdrey.dokene.ai.domain.TrustedFollowUpReason;
import io.github.stevdrey.dokene.customer.application.ContactPolicyService;
import io.github.stevdrey.dokene.customer.application.CustomerService;
import io.github.stevdrey.dokene.customer.domain.ContactChannel;
import io.github.stevdrey.dokene.customer.domain.Customer;
import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.followup.domain.FollowUpEvaluation;
import io.github.stevdrey.dokene.purchase.application.PurchaseService;
import io.github.stevdrey.dokene.purchase.domain.PurchaseId;
import io.github.stevdrey.dokene.purchase.domain.PurchaseStatus;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Assembles provider-bound context only from authorized application reads. */
@Service
public class RecommendationContextAssembler {
    private final FollowUpService followUps;
    private final CustomerService customers;
    private final ContactPolicyService contacts;
    private final PurchaseService purchases;

    public RecommendationContextAssembler(FollowUpService followUps, CustomerService customers,
            ContactPolicyService contacts, PurchaseService purchases) {
        this.followUps = Objects.requireNonNull(followUps);
        this.customers = Objects.requireNonNull(customers);
        this.contacts = Objects.requireNonNull(contacts);
        this.purchases = Objects.requireNonNull(purchases);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Assembly assemble(CustomerId customerId) {
        Objects.requireNonNull(customerId, "Customer ID is required");
        FollowUpEvaluation evaluation = followUps.evaluate(customerId);
        if (!evaluation.eligible()) {
            return new Assembly(evaluation, null, List.of());
        }
        Customer customer = customers.get(customerId);
        boolean contactEligible = customer.phones().stream().anyMatch(phone ->
                contacts.evaluate(customerId, phone.id(), ContactChannel.WHATSAPP).eligible());
        if (!contactEligible) {
            throw new RecommendationContextException(RecommendationContextException.Reason.UNSUPPORTED);
        }
        var recent = purchases.list(customerId, PurchaseStatus.VALID, null, RecommendationContext.MAX_PURCHASES)
                .purchases();
        Instant latestPurchase = recent.isEmpty() ? null : recent.getFirst().purchasedAt();
        if (!Objects.equals(evaluation.lastPurchaseAt(), latestPurchase)) {
            evaluation = followUps.evaluate(customerId);
            if (!evaluation.eligible()) {
                return new Assembly(evaluation, null, List.of());
            }
            recent = purchases.list(customerId, PurchaseStatus.VALID, null, RecommendationContext.MAX_PURCHASES)
                    .purchases();
            latestPurchase = recent.isEmpty() ? null : recent.getFirst().purchasedAt();
            if (!Objects.equals(evaluation.lastPurchaseAt(), latestPurchase)) {
                throw new RecommendationContextException(RecommendationContextException.Reason.UNSUPPORTED);
            }
        }
        var trusted = new RecommendationContext.TrustedFacts(evaluation.tenantDate(),
                evaluation.status().name(), evaluation.reasons().stream()
                        .map(r -> TrustedFollowUpReason.valueOf(r.name())).toList(),
                evaluation.effectiveCadenceDays(), evaluation.nextFollowUpDate(), contactEligible,
                recent.stream().map(purchase -> purchase.purchasedAt()).toList(),
                Arrays.asList(SemanticAction.values()));
        var untrusted = new RecommendationContext.UntrustedText(customer.displayName(), customer.notes(),
                recent.stream().map(purchase -> purchase.description()).toList());
        List<PurchaseBaseline> purchaseBaselines = recent.stream()
                .map(PurchaseBaseline::from)
                .toList();
        return new Assembly(evaluation, new RecommendationContext(trusted, untrusted), purchaseBaselines);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Assembly assemble(FollowUpEvaluation evaluation) {
        Objects.requireNonNull(evaluation, "Evaluation is required");
        return assemble(evaluation.customerId());
    }

    public record Assembly(
            FollowUpEvaluation evaluation,
            RecommendationContext context,
            List<PurchaseBaseline> purchases
    ) {
        public Assembly {
            Objects.requireNonNull(evaluation, "Evaluation is required");
            purchases = purchases == null ? List.of() : List.copyOf(purchases);

            if (purchases.stream().map(PurchaseBaseline::id).distinct().count() != purchases.size()) {
                throw new IllegalArgumentException("Purchase baseline IDs must be unique");
            }

            if (context != null) {
                var contextDates = context.trusted() != null ? context.trusted().purchaseDates() : List.<Instant>of();
                if (contextDates.size() != purchases.size()) {
                    throw new IllegalArgumentException("Purchase baseline must match provider context");
                }
                var baselineDates = purchases.stream()
                        .map(PurchaseBaseline::purchasedAt)
                        .toList();
                if (!contextDates.equals(baselineDates)) {
                    throw new IllegalArgumentException("Purchase baseline must match provider context");
                }
            }

            if (context != null && evaluation.lastPurchaseAt() != null && purchases.isEmpty()) {
                throw new IllegalArgumentException("Purchase baseline is required when baseline contains purchase history");
            }
        }

        public PurchaseId lastPurchaseId() {
            return purchases.isEmpty() ? null : purchases.getFirst().id();
        }
    }
}
