package io.github.stevdrey.dokene.customer.domain;

import java.util.List;

public record ContactEligibility(boolean eligible, List<ContactEligibilityReason> reasons) {
    public ContactEligibility {
        reasons = List.copyOf(reasons);
        if (eligible == !reasons.isEmpty()) {
            throw new IllegalArgumentException("Eligibility and reasons are inconsistent");
        }
    }
}
