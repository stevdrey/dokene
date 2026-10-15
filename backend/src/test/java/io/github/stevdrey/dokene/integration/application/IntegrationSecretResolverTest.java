// ABOUTME: Proves secret material is owned, wiped after scoped use and after close, and never printed (ADR 0024 §3).
// ABOUTME: Also pins the bare-key reference grammar and the tenant and deployment scopes.
package io.github.stevdrey.dokene.integration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.Deployment;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.ResolvedSecret;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.SecretPurpose;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.SecretReference;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IntegrationSecretResolverTest {
    private static final char[] EXPECTED = "s3cr3t".toCharArray();

    @Test
    void takesOwnershipOfTheCallersBuffer() {
        char[] input = "s3cr3t".toCharArray();

        var secret = new ResolvedSecret(input);

        assertThat(input).containsOnly('\0');
        boolean matches = secret.withValue(buffer -> Arrays.equals(buffer, EXPECTED));
        assertThat(matches).isTrue();
    }

    @Test
    void theBufferHandedToTheCallbackIsWipedOnReturn() {
        var secret = new ResolvedSecret("s3cr3t".toCharArray());
        var seen = new AtomicReference<char[]>();

        boolean matches = secret.withValue(buffer -> {
            seen.set(buffer);
            return Arrays.equals(buffer, EXPECTED);
        });

        assertThat(matches).isTrue();
        assertThat(seen.get()).containsOnly('\0');
    }

    @Test
    void theBufferIsWipedWhenTheCallbackThrows() {
        var secret = new ResolvedSecret("s3cr3t".toCharArray());
        var seen = new AtomicReference<char[]>();

        assertThatThrownBy(() -> secret.withValue(buffer -> {
            seen.set(buffer);
            throw new IllegalStateException("provider call failed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(seen.get()).containsOnly('\0');
    }

    @Test
    void closeWipesTheSecretAndRefusesFurtherUse() {
        var secret = new ResolvedSecret("s3cr3t".toCharArray());

        secret.close();

        assertThatThrownBy(() -> secret.withValue(buffer -> buffer.length))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void neverPrintsTheValueAndRejectsEmptySecrets() {
        assertThat(new ResolvedSecret("s3cr3t".toCharArray())).hasToString("ResolvedSecret[REDACTED]");
        assertThatThrownBy(() -> new ResolvedSecret(new char[0])).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void referencesAreBareKeys() {
        assertThat(new SecretReference("whatsapp_access-token").value()).isEqualTo("whatsapp_access-token");
        assertThat(new SecretReference("x".repeat(128)).value()).hasSize(128);
    }

    /** A tenant-supplied key must not name a scheme, path or another namespace. */
    @ParameterizedTest
    @ValueSource(strings = {"", "has spaces", "env:DOKENE_DATASOURCE_PASSWORD", "../other-tenant/token",
            "tenants/other/token", "token.v2", "secret-manager://prod/app"})
    void rejectsReferencesThatCouldEscapeTheirScope(String value) {
        assertThatThrownBy(() -> new SecretReference(value)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SecretReference("x".repeat(129))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tenantScopeRequiresATenantAndAPurpose() {
        assertThatThrownBy(() -> new Tenant(null, SecretPurpose.WHATSAPP_ACCESS_TOKEN))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Tenant(TenantId.random(), null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Deployment(null)).isInstanceOf(NullPointerException.class);
    }

    /** Spec 0006 AC-1: a sender cannot even ask for a webhook secret, and the deployment never holds a tenant token. */
    @Test
    void eachScopeAdmitsOnlyItsOwnPurposes() {
        TenantId tenantId = TenantId.random();

        assertThat(new Tenant(tenantId, SecretPurpose.WHATSAPP_ACCESS_TOKEN).purpose())
                .isEqualTo(SecretPurpose.WHATSAPP_ACCESS_TOKEN);
        assertThat(new Deployment(SecretPurpose.WEBHOOK_VERIFY_TOKEN).purpose())
                .isEqualTo(SecretPurpose.WEBHOOK_VERIFY_TOKEN);
        assertThat(new Deployment(SecretPurpose.APP_SECRET).purpose()).isEqualTo(SecretPurpose.APP_SECRET);

        assertThatThrownBy(() -> new Tenant(tenantId, SecretPurpose.WEBHOOK_VERIFY_TOKEN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Tenant(tenantId, SecretPurpose.APP_SECRET))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Deployment(SecretPurpose.WHATSAPP_ACCESS_TOKEN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void thePurposeEnumIsClosed() {
        assertThat(SecretPurpose.values()).containsExactly(SecretPurpose.WHATSAPP_ACCESS_TOKEN,
                SecretPurpose.WEBHOOK_VERIFY_TOKEN, SecretPurpose.APP_SECRET);
    }
}
