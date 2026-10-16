// ABOUTME: The only way to map a provider phone number id to a tenant without a tenant context (issue 210, ADR 0024).
// ABOUTME: Resolves verified, enabled mappings only; webhook ingress (#139) must route through this and nothing else.
package io.github.stevdrey.dokene.integration.application;

import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.util.Optional;

public interface VerifiedPhoneNumberMappings {
    /**
     * The tenant whose ownership of {@code phoneNumberId} was proven for its current credential and whose integration
     * is enabled; empty for an unknown, malformed, unverified or disabled mapping. Unverified claims by other tenants
     * are never returned, so a pre-registered number cannot divert the real owner's events.
     */
    Optional<TenantId> tenantFor(String phoneNumberId);
}
