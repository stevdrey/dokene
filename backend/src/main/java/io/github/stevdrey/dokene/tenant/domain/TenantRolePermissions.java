package io.github.stevdrey.dokene.tenant.domain;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Explicit mapping between tenant roles and granted tenant permissions.
 */
public final class TenantRolePermissions {

    private static final Set<TenantPermission> OWNER_PERMISSIONS = Collections.unmodifiableSet(
            EnumSet.of(
                    TenantPermission.TENANT_READ,
                    TenantPermission.TENANT_UPDATE,
                    TenantPermission.TENANT_ARCHIVE,
                    TenantPermission.MEMBERSHIP_READ,
                    TenantPermission.MEMBERSHIP_INVITE,
                    TenantPermission.MEMBERSHIP_ROLE_UPDATE,
                    TenantPermission.MEMBERSHIP_REVOKE,
                    TenantPermission.CUSTOMER_READ,
                    TenantPermission.CUSTOMER_WRITE,
                    TenantPermission.CUSTOMER_DELETE,
                    TenantPermission.FOLLOWUP_READ,
                    TenantPermission.FOLLOWUP_WRITE,
                    TenantPermission.FOLLOWUP_EVALUATE,
                    TenantPermission.TEMPLATE_READ,
                    TenantPermission.TEMPLATE_WRITE,
                    TenantPermission.MESSAGE_READ,
                    TenantPermission.MESSAGE_DRAFT,
                    TenantPermission.MESSAGE_APPROVE,
                    TenantPermission.MESSAGE_SEND,
                    TenantPermission.INTEGRATION_READ,
                    TenantPermission.INTEGRATION_MANAGE,
                    TenantPermission.AUDIT_READ,
                    TenantPermission.DATA_EXPORT
            )
    );

    private static final Set<TenantPermission> ADMIN_PERMISSIONS = Collections.unmodifiableSet(
            EnumSet.of(
                    TenantPermission.TENANT_READ,
                    TenantPermission.TENANT_UPDATE,
                    TenantPermission.MEMBERSHIP_READ,
                    TenantPermission.MEMBERSHIP_INVITE,
                    TenantPermission.MEMBERSHIP_ROLE_UPDATE,
                    TenantPermission.MEMBERSHIP_REVOKE,
                    TenantPermission.CUSTOMER_READ,
                    TenantPermission.CUSTOMER_WRITE,
                    TenantPermission.CUSTOMER_DELETE,
                    TenantPermission.FOLLOWUP_READ,
                    TenantPermission.FOLLOWUP_WRITE,
                    TenantPermission.FOLLOWUP_EVALUATE,
                    TenantPermission.TEMPLATE_READ,
                    TenantPermission.TEMPLATE_WRITE,
                    TenantPermission.MESSAGE_READ,
                    TenantPermission.MESSAGE_DRAFT,
                    TenantPermission.MESSAGE_APPROVE,
                    TenantPermission.MESSAGE_SEND,
                    TenantPermission.INTEGRATION_READ,
                    TenantPermission.INTEGRATION_MANAGE,
                    TenantPermission.AUDIT_READ,
                    TenantPermission.DATA_EXPORT
            )
    );

    private static final Set<TenantPermission> OPERATOR_PERMISSIONS = Collections.unmodifiableSet(
            EnumSet.of(
                    TenantPermission.TENANT_READ,
                    TenantPermission.MEMBERSHIP_READ,
                    TenantPermission.CUSTOMER_READ,
                    TenantPermission.CUSTOMER_WRITE,
                    TenantPermission.FOLLOWUP_READ,
                    TenantPermission.FOLLOWUP_WRITE,
                    TenantPermission.FOLLOWUP_EVALUATE,
                    TenantPermission.TEMPLATE_READ,
                    TenantPermission.TEMPLATE_WRITE,
                    TenantPermission.MESSAGE_READ,
                    TenantPermission.MESSAGE_DRAFT,
                    TenantPermission.MESSAGE_APPROVE,
                    TenantPermission.MESSAGE_SEND,
                    TenantPermission.INTEGRATION_READ
            )
    );

    private static final Set<TenantPermission> VIEWER_PERMISSIONS = Collections.unmodifiableSet(
            EnumSet.of(
                    TenantPermission.TENANT_READ,
                    TenantPermission.MEMBERSHIP_READ,
                    TenantPermission.CUSTOMER_READ,
                    TenantPermission.FOLLOWUP_READ,
                    TenantPermission.TEMPLATE_READ,
                    TenantPermission.MESSAGE_READ,
                    TenantPermission.INTEGRATION_READ
            )
    );

    private static final Map<TenantRole, Set<TenantPermission>> ROLE_PERMISSIONS;

    static {
        Map<TenantRole, Set<TenantPermission>> map = new EnumMap<>(TenantRole.class);
        map.put(TenantRole.OWNER, OWNER_PERMISSIONS);
        map.put(TenantRole.ADMIN, ADMIN_PERMISSIONS);
        map.put(TenantRole.OPERATOR, OPERATOR_PERMISSIONS);
        map.put(TenantRole.VIEWER, VIEWER_PERMISSIONS);
        ROLE_PERMISSIONS = Collections.unmodifiableMap(map);
    }

    private TenantRolePermissions() {
    }

    public static Set<TenantPermission> permissionsFor(TenantRole role) {
        Objects.requireNonNull(role, "Tenant role is required");
        return ROLE_PERMISSIONS.getOrDefault(role, Set.of());
    }

    public static boolean hasPermission(TenantRole role, TenantPermission permission) {
        if (role == null || permission == null) {
            return false;
        }
        return permissionsFor(role).contains(permission);
    }
}
