// ABOUTME: Builds the allowed context text and grounding facts DraftSafetyValidator needs for a customer.
// ABOUTME: Shared by the AI gate and by message submission so AI and manual bodies are validated identically.
package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.ai.application.RecommendationContext;
import io.github.stevdrey.dokene.ai.domain.DraftGroundingContext;
import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.followup.domain.TenantFollowUpPolicy;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DraftGroundingAssembler {
    private final RecommendationContextAssembler contexts;
    private final FollowUpPolicyRepository policies;
    private final TenantRepository tenants;
    private final TenantContextProvider tenantContexts;

    public DraftGroundingAssembler(RecommendationContextAssembler contexts, FollowUpPolicyRepository policies,
            TenantRepository tenants, TenantContextProvider tenantContexts) {
        this.contexts = Objects.requireNonNull(contexts, "Context assembler is required");
        this.policies = Objects.requireNonNull(policies, "Policy repository is required");
        this.tenants = Objects.requireNonNull(tenants, "Tenant repository is required");
        this.tenantContexts = Objects.requireNonNull(tenantContexts, "Tenant context provider is required");
    }

    /** The validator inputs, or empty when the customer is not currently eligible for a follow up. */
    public record Grounding(String allowedContextText, DraftGroundingContext grounding) {
    }

    @Transactional(readOnly = true)
    public java.util.Optional<Grounding> assemble(CustomerId customerId) {
        Objects.requireNonNull(customerId, "Customer ID is required");
        var assembly = contexts.assemble(customerId);
        if (assembly.context() == null) {
            return java.util.Optional.empty();
        }
        var tenantId = tenantContexts.requireCurrent().tenantId();
        Tenant tenant = tenants.findById(tenantId).orElse(null);
        TenantFollowUpPolicy policy = policies.tenantPolicy(tenantId);
        return java.util.Optional.of(new Grounding(allowedContextText(assembly.context(), policy, tenant),
                grounding(assembly.context())));
    }

    public static DraftGroundingContext grounding(RecommendationContext context) {
        var untrustedText = context.untrusted();
        return new DraftGroundingContext(
                untrustedText.displayName(), untrustedText.notes(), untrustedText.purchaseDescriptions(),
                context.trusted().purchaseDates().stream().map(Object::toString).toList(),
                context.trusted().followUpStatus(),
                context.trusted().tenantDate().toString());
    }

    public static String allowedContextText(RecommendationContext context, TenantFollowUpPolicy tenantPolicy,
            Tenant tenant) {
        if (context == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (tenant != null && tenant.displayName() != null) {
            sb.append(tenant.displayName()).append(" ");
        }
        if (context.trusted() != null) {
            sb.append(context.trusted().tenantDate()).append(" ");
            sb.append(context.trusted().followUpStatus()).append(" ");
            for (var purchaseDate : context.trusted().purchaseDates()) {
                sb.append(purchaseDate).append(" ");
            }
        }
        if (context.untrusted() != null) {
            sb.append(context.untrusted().displayName()).append(" ");
            if (context.untrusted().notes() != null) {
                sb.append(context.untrusted().notes()).append(" ");
            }
            if (context.untrusted().purchaseDescriptions() != null) {
                for (String desc : context.untrusted().purchaseDescriptions()) {
                    sb.append(desc).append(" ");
                }
            }
        }
        if (tenantPolicy != null && tenantPolicy.zoneId() != null) {
            sb.append(tenantPolicy.zoneId().getId()).append(" ");
        }
        return sb.toString();
    }
}
