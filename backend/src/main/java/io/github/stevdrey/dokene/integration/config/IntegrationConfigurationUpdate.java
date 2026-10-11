// ABOUTME: Input of IntegrationConfigurationService.update: the six configurable values plus the expected version.
// ABOUTME: Raw strings on purpose; the service validates and normalizes them before any transaction opens.
package io.github.stevdrey.dokene.integration.config;

public record IntegrationConfigurationUpdate(String phoneNumberId, String businessAccountId,
        String displayPhoneNumber, String displayPhoneRegion, String secretRef, boolean enabled,
        long expectedVersion) {
}
