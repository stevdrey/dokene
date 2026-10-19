// ABOUTME: Context runner proof of spec 0006 AC-2, AC-3 and AC-6: source selection, ordered startup validation,
// ABOUTME: relaxed binding of indexed entries, redacted properties and a cause chain that never carries a value.
package io.github.stevdrey.dokene.integration.secrets;

import static io.github.stevdrey.dokene.integration.testsupport.SecretCanaries.containsAny;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.Deployment;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.ResolvedSecret;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.SecretPurpose;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.SecretReference;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.Tenant;
import io.github.stevdrey.dokene.integration.application.SecretUnavailableException;
import io.github.stevdrey.dokene.integration.application.SecretUnavailableException.Category;
import io.github.stevdrey.dokene.integration.secrets.IntegrationSecretsProperties.Entry;
import io.github.stevdrey.dokene.integration.secrets.IntegrationSecretsProperties.Local;
import io.github.stevdrey.dokene.integration.testsupport.LogCapture;
import io.github.stevdrey.dokene.integration.testsupport.SecretCanaries;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class IntegrationSecretsConfigurationTest {
    private static final String ENTRIES = "dokene.integration.secrets.local.entries";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(IntegrationSecretsConfiguration.class);

    private final UUID tenantA = UUID.randomUUID();
    private final String tokenRef = SecretCanaries.ref();
    private final String verifyRef = SecretCanaries.ref();
    private final String token = SecretCanaries.value();
    private final String verifyToken = SecretCanaries.value();
    /** Values and references must never appear anywhere; the tenant id may appear in a WARN line but never in a startup failure. */
    private final List<String> canaries = new ArrayList<>(List.of(tokenRef, verifyRef, token, verifyToken));
    private final List<String> startupCanaries = new ArrayList<>(List.of(tokenRef, verifyRef, token, verifyToken,
            tenantA.toString()));

    private LogCapture logs;

    @BeforeEach
    void setUp() {
        logs = new LogCapture();
    }

    @AfterEach
    void tearDown() {
        boolean leaked = containsAny(logs.text(), canaries);
        logs.close();
        assertThat(leaked).isFalse();
    }

    @Test
    void noSourceGivesTheDisabledResolverThatRefusesWithSourceDisabled() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(IntegrationSecretResolver.class);
            IntegrationSecretResolver resolver = context.getBean(IntegrationSecretResolver.class);
            assertThat(resolver).isInstanceOf(DisabledIntegrationSecretResolver.class);
            assertThat(denial(resolver, tenantScope(), tokenRef).category()).isEqualTo(Category.SOURCE_DISABLED);
        });
        runner.withPropertyValues("dokene.integration.secrets.source=").run(context ->
                assertThat(context.getBean(IntegrationSecretResolver.class))
                        .isInstanceOf(DisabledIntegrationSecretResolver.class));
    }

    @Test
    void localResolvesOneTenantEntryAndOneDeploymentEntry() {
        runner.withPropertyValues(local(tenantEntry(0, tenantA, tokenRef, token),
                deploymentEntry(1, verifyRef, "WEBHOOK_VERIFY_TOKEN", verifyToken))).run(context -> {
            assertThat(context).hasSingleBean(IntegrationSecretResolver.class);
            IntegrationSecretResolver resolver = context.getBean(IntegrationSecretResolver.class);
            assertThat(resolver).isInstanceOf(LocalIntegrationSecretResolver.class);
            try (ResolvedSecret secret = resolver.resolve(tenantScope(), new SecretReference(tokenRef))) {
                assertThat(secret.<String>withValue(String::new)).isEqualTo(token);
            }
            try (ResolvedSecret secret = resolver.resolve(new Deployment(SecretPurpose.WEBHOOK_VERIFY_TOKEN),
                    new SecretReference(verifyRef))) {
                assertThat(secret.<String>withValue(String::new)).isEqualTo(verifyToken);
            }
            assertThat(denial(resolver, new Tenant(TenantId.random(), SecretPurpose.WHATSAPP_ACCESS_TOKEN), tokenRef)
                    .category()).isEqualTo(Category.MISSING);
        });
    }

    @Test
    void theSourceIsMatchedCaseInsensitivelyAndAnEmptyEntryListIsValid() {
        runner.withPropertyValues("dokene.integration.secrets.source=LOCAL").run(context -> {
            assertThat(context).hasNotFailed();
            IntegrationSecretResolver resolver = context.getBean(IntegrationSecretResolver.class);
            assertThat(resolver).isInstanceOf(LocalIntegrationSecretResolver.class);
            assertThat(denial(resolver, tenantScope(), tokenRef).category()).isEqualTo(Category.MISSING);
        });
    }

    /** The context runner trims property values, so the padded source goes in through a raw map source. */
    @Test
    void theSourceIsNotTrimmed() {
        runner.withInitializer(context -> context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("padded-source", Map.of("dokene.integration.secrets.source", " local"))))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootCause(context.getStartupFailure())).isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("dokene.integration.secrets.source");
                });
    }

    @Test
    void anUnknownSourceFailsStartupWithoutEchoingTheValue() {
        String offending = "vault-" + SecretCanaries.value();
        canaries.add(offending);
        startupCanaries.add(offending);

        runner.withPropertyValues("dokene.integration.secrets.source=" + offending).run(context -> {
            assertThat(context).hasFailed();
            Throwable root = rootCause(context.getStartupFailure());
            assertThat(root).isInstanceOf(IllegalStateException.class);
            assertThat(root.getMessage()).contains("dokene.integration.secrets.source").contains("local");
            assertThat(containsAny(causeChainText(context.getStartupFailure()), startupCanaries)).isFalse();
        });
    }

    @Test
    void aBlankSourceIgnoresEntriesEntirelyEvenInvalidOnes() {
        runner.withPropertyValues(
                ENTRIES + "[0].scope=nonsense",
                ENTRIES + "[0].value=" + token).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(IntegrationSecretResolver.class))
                    .isInstanceOf(DisabledIntegrationSecretResolver.class);
            assertThat(context.getBean(IntegrationSecretsProperties.class).local().entries()).hasSize(1);
        });
    }

    /** The context runner's property helper trims, so the padded value goes in through a raw map source. */
    @Test
    void valuesAreBoundVerbatim() {
        String padded = "  " + token + " \t";
        Map<String, Object> raw = Map.of(
                "dokene.integration.secrets.source", "local",
                ENTRIES + "[0].scope", "tenant",
                ENTRIES + "[0].tenant-id", tenantA.toString(),
                ENTRIES + "[0].ref", tokenRef,
                ENTRIES + "[0].purpose", "WHATSAPP_ACCESS_TOKEN",
                ENTRIES + "[0].value", padded);
        runner.withInitializer(context -> context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("padded", raw))).run(context -> {
            IntegrationSecretResolver resolver = context.getBean(IntegrationSecretResolver.class);
            try (ResolvedSecret secret = resolver.resolve(tenantScope(), new SecretReference(tokenRef))) {
                assertThat(secret.<String>withValue(String::new)).isEqualTo(padded);
            }
        });
    }

    /** The environment variable shape documented in .env.example binds through Spring's relaxed rules. */
    @Test
    void indexedEnvironmentVariablesBindToTheEntries() {
        Map<String, Object> environment = Map.of(
                "DOKENE_INTEGRATION_SECRETS_SOURCE", "local",
                "DOKENE_INTEGRATION_SECRETS_LOCAL_ENTRIES_0_SCOPE", "tenant",
                "DOKENE_INTEGRATION_SECRETS_LOCAL_ENTRIES_0_TENANT_ID", tenantA.toString(),
                "DOKENE_INTEGRATION_SECRETS_LOCAL_ENTRIES_0_REF", tokenRef,
                "DOKENE_INTEGRATION_SECRETS_LOCAL_ENTRIES_0_PURPOSE", "WHATSAPP_ACCESS_TOKEN",
                "DOKENE_INTEGRATION_SECRETS_LOCAL_ENTRIES_0_VALUE", token);
        runner.withInitializer(context -> context.getEnvironment().getPropertySources()
                .addFirst(new SystemEnvironmentPropertySource("test-systemEnvironment", environment))).run(context -> {
            assertThat(context).hasNotFailed();
            IntegrationSecretResolver resolver = context.getBean(IntegrationSecretResolver.class);
            try (ResolvedSecret secret = resolver.resolve(tenantScope(), new SecretReference(tokenRef))) {
                assertThat(secret.<String>withValue(String::new)).isEqualTo(token);
            }
        });
    }

    @Test
    void boundPropertiesNeverPrintTheirValues() {
        runner.withPropertyValues(local(tenantEntry(0, tenantA, tokenRef, token))).run(context -> {
            IntegrationSecretsProperties properties = context.getBean(IntegrationSecretsProperties.class);
            assertThat(properties).hasToString("IntegrationSecretsProperties[REDACTED]");
            assertThat(properties.local()).hasToString("IntegrationSecretsProperties.Local[REDACTED]");
            assertThat(properties.local().entries().getFirst())
                    .hasToString("IntegrationSecretsProperties.Entry[REDACTED]");
        });
        assertThat(new IntegrationSecretsProperties(null, null).local().entries()).isEmpty();
        assertThat(new Local(null).entries()).isEmpty();
        assertThat(new Entry("tenant", tenantA.toString(), tokenRef, "WHATSAPP_ACCESS_TOKEN", token))
                .hasToString("IntegrationSecretsProperties.Entry[REDACTED]");
    }

    @Test
    void anUnknownScopeFailsStartup() {
        assertStartupRefused("entries[0].scope is invalid", local(
                entry(0, "Tenant", tenantA.toString(), tokenRef, "WHATSAPP_ACCESS_TOKEN", token)));
        assertStartupRefused("entries[0].scope is invalid", local(
                new String[] {ENTRIES + "[0].ref=" + tokenRef, ENTRIES + "[0].value=" + token}));
    }

    @Test
    void aTenantEntryWithoutATenantIdFailsStartup() {
        assertStartupRefused("entries[0].tenant-id is invalid", local(
                entry(0, "tenant", null, tokenRef, "WHATSAPP_ACCESS_TOKEN", token)));
    }

    @Test
    void aMalformedTenantIdFailsStartupWithoutEchoingIt() {
        String malformed = "not-a-uuid-" + SecretCanaries.value();
        canaries.add(malformed);
        startupCanaries.add(malformed);
        assertStartupRefused("entries[0].tenant-id is invalid", local(
                entry(0, "tenant", malformed, tokenRef, "WHATSAPP_ACCESS_TOKEN", token)));
    }

    @Test
    void aDeploymentEntryWithATenantIdFailsStartup() {
        assertStartupRefused("entries[0].tenant-id is invalid", local(
                entry(0, "deployment", tenantA.toString(), verifyRef, "WEBHOOK_VERIFY_TOKEN", verifyToken)));
    }

    @Test
    void aMalformedReferenceFailsStartup() {
        String malformed = "env:" + SecretCanaries.value();
        canaries.add(malformed);
        startupCanaries.add(malformed);
        assertStartupRefused("entries[0].ref is invalid", local(
                entry(0, "tenant", tenantA.toString(), malformed, "WHATSAPP_ACCESS_TOKEN", token)));
        assertStartupRefused("entries[0].ref is invalid", local(
                entry(0, "tenant", tenantA.toString(), null, "WHATSAPP_ACCESS_TOKEN", token)));
    }

    @Test
    void aPurposeTheScopeDoesNotAdmitFailsStartup() {
        assertStartupRefused("entries[0].purpose is invalid", local(
                entry(0, "tenant", tenantA.toString(), tokenRef, "WEBHOOK_VERIFY_TOKEN", token)));
        assertStartupRefused("entries[0].purpose is invalid", local(
                entry(0, "deployment", null, verifyRef, "WHATSAPP_ACCESS_TOKEN", verifyToken)));
        assertStartupRefused("entries[0].purpose is invalid", local(
                entry(0, "deployment", null, verifyRef, "whatsapp_access_token", verifyToken)));
        assertStartupRefused("entries[0].purpose is invalid", local(
                entry(0, "deployment", null, verifyRef, null, verifyToken)));
    }

    @Test
    void aBlankValueFailsStartup() {
        assertStartupRefused("entries[0].value is invalid", local(
                entry(0, "tenant", tenantA.toString(), tokenRef, "WHATSAPP_ACCESS_TOKEN", "   ")));
        assertStartupRefused("entries[0].value is invalid", local(
                entry(0, "tenant", tenantA.toString(), tokenRef, "WHATSAPP_ACCESS_TOKEN", null)));
    }

    @Test
    void aDuplicateKeyFailsStartupNamingTheLaterEntry() {
        assertStartupRefused("entries[1].ref is a duplicate", local(
                tenantEntry(0, tenantA, tokenRef, token),
                tenantEntry(1, tenantA, tokenRef, verifyToken)));
        assertStartupRefused("entries[2].ref is a duplicate", local(
                deploymentEntry(0, verifyRef, "WEBHOOK_VERIFY_TOKEN", verifyToken),
                tenantEntry(1, tenantA, verifyRef, token),
                deploymentEntry(2, verifyRef, "APP_SECRET", token)));
    }

    @Test
    void theFirstViolationInFieldOrderWinsAndLaterEntriesAreNamedByIndex() {
        assertStartupRefused("entries[1].tenant-id is invalid", local(
                tenantEntry(0, tenantA, tokenRef, token),
                entry(1, "tenant", null, "bad ref", "nope", "")));
    }

    private void assertStartupRefused(String expectedSuffix, String[] properties) {
        runner.withPropertyValues(properties).run(context -> {
            assertThat(context).hasFailed();
            Throwable root = rootCause(context.getStartupFailure());
            assertThat(root).isInstanceOf(InvalidSecretConfigurationException.class);
            assertThat(root.getMessage()).isEqualTo("dokene.integration.secrets.local." + expectedSuffix);
            assertThat(containsAny(causeChainText(context.getStartupFailure()), startupCanaries)).isFalse();
        });
    }

    private Tenant tenantScope() {
        return new Tenant(new TenantId(tenantA), SecretPurpose.WHATSAPP_ACCESS_TOKEN);
    }

    private static SecretUnavailableException denial(IntegrationSecretResolver resolver, Tenant scope, String ref) {
        try (ResolvedSecret ignored = resolver.resolve(scope, new SecretReference(ref))) {
            throw new AssertionError("expected a denial");
        } catch (SecretUnavailableException denial) {
            return denial;
        }
    }

    private static String[] local(String[]... entries) {
        List<String> properties = new ArrayList<>();
        properties.add("dokene.integration.secrets.source=local");
        for (String[] entry : entries) {
            properties.addAll(List.of(entry));
        }
        return properties.toArray(String[]::new);
    }

    private static String[] tenantEntry(int index, UUID tenantId, String ref, String value) {
        return entry(index, "tenant", tenantId.toString(), ref, "WHATSAPP_ACCESS_TOKEN", value);
    }

    private static String[] deploymentEntry(int index, String ref, String purpose, String value) {
        return entry(index, "deployment", null, ref, purpose, value);
    }

    /** A null field is left unset so the binder produces null, which is how a missing variable arrives. */
    private static String[] entry(int index, String scope, String tenantId, String ref, String purpose,
            String value) {
        List<String> properties = new ArrayList<>();
        String prefix = ENTRIES + "[" + index + "].";
        if (scope != null) {
            properties.add(prefix + "scope=" + scope);
        }
        if (tenantId != null) {
            properties.add(prefix + "tenant-id=" + tenantId);
        }
        if (ref != null) {
            properties.add(prefix + "ref=" + ref);
        }
        if (purpose != null) {
            properties.add(prefix + "purpose=" + purpose);
        }
        if (value != null) {
            properties.add(prefix + "value=" + value);
        }
        return properties.toArray(String[]::new);
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static String causeChainText(Throwable failure) {
        StringBuilder text = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            text.append(current.getClass().getName()).append(": ").append(current.getMessage()).append('\n');
            if (current.getCause() == current) {
                break;
            }
        }
        return text.toString();
    }
}
