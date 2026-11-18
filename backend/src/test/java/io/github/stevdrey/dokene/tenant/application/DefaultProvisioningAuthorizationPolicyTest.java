package io.github.stevdrey.dokene.tenant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DefaultProvisioningAuthorizationPolicyTest {

    private final IdentityId identity = new IdentityId(UUID.randomUUID());
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ProvisioningConfiguration.class, DefaultProvisioningAuthorizationPolicy.class);

    @Test
    void deniesProvisioningByDefault() {
        contextRunner.run(context -> {
            DefaultProvisioningAuthorizationPolicy policy = context.getBean(DefaultProvisioningAuthorizationPolicy.class);

            assertThat(policy.isAllowed(identity)).isFalse();
        });
    }

    @Test
    void allowsEveryIdentityInOpenMode() {
        DefaultProvisioningAuthorizationPolicy policy = policy(true, List.of(), true);

        assertThat(policy.isAllowed(identity)).isTrue();
        assertThat(policy.isAllowed(new IdentityId(UUID.randomUUID()))).isTrue();
    }

    @Test
    void deniesProvisioningWhenDisabledGlobally() {
        assertThat(policy(false, List.of(), false).isAllowed(identity)).isFalse();
    }

    @Test
    void deniesProvisioningWhenDisabledWhateverTheAllowlistAndTheOpenSettingSay() {
        assertThat(policy(false, List.of(identity.value().toString()), false).isAllowed(identity)).isFalse();
        assertThat(policy(false, List.of(), true).isAllowed(identity)).isFalse();
        assertThat(policy(false, List.of(identity.value().toString()), true).isAllowed(identity)).isFalse();
    }

    @Test
    void allowsProvisioningWhenIdentityIsInAllowlist() {
        DefaultProvisioningAuthorizationPolicy policy = policy(
                true, List.of(identity.value().toString(), UUID.randomUUID().toString()), false
        );

        assertThat(policy.isAllowed(identity)).isTrue();
    }

    @Test
    void deniesProvisioningWhenIdentityIsNotInAllowlist() {
        DefaultProvisioningAuthorizationPolicy policy = policy(true, List.of(UUID.randomUUID().toString()), false);

        assertThat(policy.isAllowed(identity)).isFalse();
    }

    @Test
    void requiresIdentityId() {
        DefaultProvisioningAuthorizationPolicy policy = policy(true, List.of(), true);

        assertThatThrownBy(() -> policy.isAllowed(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Identity ID is required");
    }

    private DefaultProvisioningAuthorizationPolicy policy(
            boolean enabled, List<String> allowedIdentities, boolean allowAllIdentities
    ) {
        return new DefaultProvisioningAuthorizationPolicy(
                new ProvisioningProperties(enabled, allowedIdentities, allowAllIdentities, 3)
        );
    }
}
