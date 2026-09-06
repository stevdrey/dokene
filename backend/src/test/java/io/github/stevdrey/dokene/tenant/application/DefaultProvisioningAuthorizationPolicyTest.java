package io.github.stevdrey.dokene.tenant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DefaultProvisioningAuthorizationPolicyTest {

    private final IdentityId identity = new IdentityId(UUID.randomUUID());

    @Test
    void allowsProvisioningWhenEnabledAndNoAllowlist() {
        DefaultProvisioningAuthorizationPolicy policy = new DefaultProvisioningAuthorizationPolicy(true, List.of());

        assertThat(policy.isAllowed(identity)).isTrue();
    }

    @Test
    void deniesProvisioningWhenDisabledGlobally() {
        DefaultProvisioningAuthorizationPolicy policy = new DefaultProvisioningAuthorizationPolicy(false, List.of());

        assertThat(policy.isAllowed(identity)).isFalse();
    }

    @Test
    void allowsProvisioningWhenIdentityIsInAllowlist() {
        DefaultProvisioningAuthorizationPolicy policy = new DefaultProvisioningAuthorizationPolicy(
                true, List.of(identity.value().toString(), UUID.randomUUID().toString())
        );

        assertThat(policy.isAllowed(identity)).isTrue();
    }

    @Test
    void deniesProvisioningWhenIdentityIsNotInAllowlist() {
        DefaultProvisioningAuthorizationPolicy policy = new DefaultProvisioningAuthorizationPolicy(
                true, List.of(UUID.randomUUID().toString())
        );

        assertThat(policy.isAllowed(identity)).isFalse();
    }

    @Test
    void requiresIdentityId() {
        DefaultProvisioningAuthorizationPolicy policy = new DefaultProvisioningAuthorizationPolicy(true, List.of());

        assertThatThrownBy(() -> policy.isAllowed(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Identity ID is required");
    }
}
