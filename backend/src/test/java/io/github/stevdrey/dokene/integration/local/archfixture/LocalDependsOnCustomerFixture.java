// ABOUTME: Deliberate violation used only to prove the integration.local ArchUnit rule matches (spec 0003 AC-10).
// ABOUTME: Never referenced by production code; it imports customer.domain from under integration.local.
package io.github.stevdrey.dokene.integration.local.archfixture;

import io.github.stevdrey.dokene.customer.domain.CustomerId;

public final class LocalDependsOnCustomerFixture {
    private LocalDependsOnCustomerFixture() {
    }

    public static Class<?> leaks() {
        return CustomerId.class;
    }
}
