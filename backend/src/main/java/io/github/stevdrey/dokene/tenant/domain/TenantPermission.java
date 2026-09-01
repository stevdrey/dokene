package io.github.stevdrey.dokene.tenant.domain;

import java.util.Locale;

/**
 * Granular permissions for operations within a tenant boundary.
 */
public enum TenantPermission {

    // Tenant lifecycle
    TENANT_READ,
    TENANT_UPDATE,
    TENANT_ARCHIVE,

    // Membership management
    MEMBERSHIP_READ,
    MEMBERSHIP_INVITE,
    MEMBERSHIP_ROLE_UPDATE,
    MEMBERSHIP_REVOKE,

    // Customer management
    CUSTOMER_READ,
    CUSTOMER_WRITE,
    CUSTOMER_DELETE,

    // Follow-up evaluation and management
    FOLLOWUP_READ,
    FOLLOWUP_WRITE,
    FOLLOWUP_EVALUATE,

    // Template management
    TEMPLATE_READ,
    TEMPLATE_WRITE,

    // Outbound messaging and approval
    MESSAGE_READ,
    MESSAGE_DRAFT,
    MESSAGE_APPROVE,
    MESSAGE_SEND,

    // Integrations
    INTEGRATION_READ,
    INTEGRATION_MANAGE,

    // Audit and data export
    AUDIT_READ,
    DATA_EXPORT;

    public static TenantPermission parse(String permissionName) {
        if (permissionName == null || permissionName.isBlank()) {
            throw new IllegalArgumentException("Permission name is required");
        }
        return TenantPermission.valueOf(permissionName.trim().toUpperCase(Locale.ROOT));
    }
}
