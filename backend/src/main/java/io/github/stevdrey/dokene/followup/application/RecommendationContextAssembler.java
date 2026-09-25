package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.ai.application.RecommendationContext;
import io.github.stevdrey.dokene.ai.application.RecommendationContextException;
import io.github.stevdrey.dokene.ai.domain.SemanticAction;
import io.github.stevdrey.dokene.customer.application.ContactPolicyService;
import io.github.stevdrey.dokene.customer.application.CustomerService;
import io.github.stevdrey.dokene.customer.domain.ContactChannel;
import io.github.stevdrey.dokene.customer.domain.Customer;
import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.followup.domain.FollowUpEvaluation;
import io.github.stevdrey.dokene.purchase.application.PurchaseService;
import io.github.stevdrey.dokene.purchase.domain.PurchaseStatus;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
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

    @Transactional(readOnly = true)
    public Assembly assemble(CustomerId customerId) {
        Objects.requireNonNull(customerId, "Customer ID is required");
        FollowUpEvaluation evaluation = followUps.evaluate(customerId);
        Customer customer = customers.get(customerId);
        boolean contactEligible = customer.phones().stream().anyMatch(phone ->
                contacts.evaluate(customerId, phone.id(), ContactChannel.WHATSAPP).eligible());
        if (evaluation.eligible() && !contactEligible) {
            throw new RecommendationContextException(RecommendationContextException.Reason.UNSUPPORTED);
        }
        var recent = purchases.list(customerId, PurchaseStatus.VALID, null, RecommendationContext.MAX_PURCHASES)
                .purchases();
        var trusted = new RecommendationContext.TrustedFacts(evaluation.tenantDate(),
                evaluation.status().name(), evaluation.reasons().stream().map(Enum::name).toList(),
                evaluation.effectiveCadenceDays(), evaluation.nextFollowUpDate(), contactEligible,
                recent.stream().map(purchase -> purchase.purchasedAt()).toList(),
                evaluation.eligible() ? Arrays.asList(SemanticAction.values()) : List.of());
        var untrusted = new RecommendationContext.UntrustedText(customer.displayName(), customer.notes(),
                recent.stream().map(purchase -> purchase.description()).toList());
        return new Assembly(evaluation, new RecommendationContext(trusted, untrusted));
    }

    public record Assembly(FollowUpEvaluation evaluation, RecommendationContext context) { }
}
