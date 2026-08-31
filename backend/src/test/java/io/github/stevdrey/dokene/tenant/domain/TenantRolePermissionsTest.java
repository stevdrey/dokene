package io.github.stevdrey.dokene.tenant.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class TenantRolePermissionsTest {

    @Test
    void ownerHasAllPermissions() {
        Set<TenantPermission> permissions = TenantRolePermissions.permissionsFor(TenantRole.OWNER);

        assertThat(permissions).containsExactlyInAnyOrder(TenantPermission.values());
        for (TenantPermission permission : TenantPermission.values()) {
            assertThat(TenantRolePermissions.hasPermission(TenantRole.OWNER, permission)).isTrue();
        }
    }

    @Test
    void adminHasAllPermissionsExceptTenantArchive() {
        Set<TenantPermission> permissions = TenantRolePermissions.permissionsFor(TenantRole.ADMIN);

        assertThat(permissions).doesNotContain(TenantPermission.TENANT_ARCHIVE);
        assertThat(TenantRolePermissions.hasPermission(TenantRole.ADMIN, TenantPermission.TENANT_ARCHIVE)).isFalse();

        for (TenantPermission permission : TenantPermission.values()) {
            if (permission != TenantPermission.TENANT_ARCHIVE) {
                assertThat(TenantRolePermissions.hasPermission(TenantRole.ADMIN, permission)).isTrue();
            }
        }
    }

    @Test
    void operatorHasOperationalPermissionsOnly() {
        Set<TenantPermission> permissions = TenantRolePermissions.permissionsFor(TenantRole.OPERATOR);

        assertThat(permissions).contains(
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
        );

        assertThat(permissions).doesNotContain(
                TenantPermission.TENANT_UPDATE,
                TenantPermission.TENANT_ARCHIVE,
                TenantPermission.MEMBERSHIP_INVITE,
                TenantPermission.MEMBERSHIP_ROLE_UPDATE,
                TenantPermission.MEMBERSHIP_REVOKE,
                TenantPermission.CUSTOMER_DELETE,
                TenantPermission.INTEGRATION_MANAGE,
                TenantPermission.AUDIT_READ,
                TenantPermission.DATA_EXPORT
        );
    }

    @Test
    void viewerHasReadOnlyPermissionsOnly() {
        Set<TenantPermission> permissions = TenantRolePermissions.permissionsFor(TenantRole.VIEWER);

        assertThat(permissions).containsExactlyInAnyOrder(
                TenantPermission.TENANT_READ,
                TenantPermission.MEMBERSHIP_READ,
                TenantPermission.CUSTOMER_READ,
                TenantPermission.FOLLOWUP_READ,
                TenantPermission.TEMPLATE_READ,
                TenantPermission.MESSAGE_READ,
                TenantPermission.INTEGRATION_READ
        );

        for (TenantPermission permission : TenantPermission.values()) {
            if (permissions.contains(permission)) {
                assertThat(TenantRolePermissions.hasPermission(TenantRole.VIEWER, permission)).isTrue();
            } else {
                assertThat(TenantRolePermissions.hasPermission(TenantRole.VIEWER, permission)).isFalse();
            }
        }
    }

    @Test
    void returnsImmutablePermissionSets() {
        Set<TenantPermission> permissions = TenantRolePermissions.permissionsFor(TenantRole.VIEWER);

        assertThatThrownBy(() -> permissions.add(TenantPermission.TENANT_UPDATE))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @EnumSource(TenantRole.class)
    void nullPermissionReturnsFalseForAnyRole(TenantRole role) {
        assertThat(TenantRolePermissions.hasPermission(role, null)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(TenantPermission.class)
    void nullRoleReturnsFalseForAnyPermission(TenantPermission permission) {
        assertThat(TenantRolePermissions.hasPermission(null, permission)).isFalse();
    }
}
