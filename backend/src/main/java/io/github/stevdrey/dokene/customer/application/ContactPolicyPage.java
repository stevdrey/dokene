package io.github.stevdrey.dokene.customer.application;

import io.github.stevdrey.dokene.customer.domain.ContactPolicyEvent;
import java.util.List;

public record ContactPolicyPage(List<ContactPolicyEvent> events, String nextCursor) {
    public ContactPolicyPage {
        events = List.copyOf(events);
    }
}
