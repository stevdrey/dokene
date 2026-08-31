package io.github.stevdrey.dokene.tenant.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class TenantPermissionTest {

    @Test
    void parsesValidPermissionNameCaseInsensitively() {
        assertThat(TenantPermission.parse("customer_read")).isEqualTo(TenantPermission.CUSTOMER_READ);
        assertThat(TenantPermission.parse("CUSTOMER_READ")).isEqualTo(TenantPermission.CUSTOMER_READ);
        assertThat(TenantPermission.parse("  Tenant_Archive  ")).isEqualTo(TenantPermission.TENANT_ARCHIVE);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t", "\n"})
    void parseRejectsBlankPermissionNames(String name) {
        assertThatThrownBy(() -> TenantPermission.parse(name))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Permission name is required");
    }

    @Test
    void parseRejectsUnknownPermissionNames() {
        assertThatThrownBy(() -> TenantPermission.parse("NON_EXISTENT_PERMISSION"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
