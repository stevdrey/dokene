package io.github.stevdrey.dokene.tenant.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TenantTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-23T00:00:00Z");

    @Test
    void createsAnActiveTenantWithNormalizedDisplayName() {
        Tenant tenant = Tenant.create(new TenantId(UUID.randomUUID()), "\u0085Main workspace\u0085", CREATED_AT);

        assertThat(tenant.status()).isEqualTo(TenantStatus.ACTIVE);
        assertThat(tenant.displayName()).isEqualTo("Main workspace");
        assertThat(tenant.createdAt()).isEqualTo(CREATED_AT);
        assertThat(tenant.updatedAt()).isEqualTo(CREATED_AT);
        assertThat(tenant.revision()).isEmpty();
    }

    @Test
    void rejectsMissingOrInvalidTenantFields() {
        assertThatIllegalArgumentException().isThrownBy(() -> new TenantId(null));
        assertThatIllegalArgumentException().isThrownBy(() -> Tenant.create(null, "Workspace", CREATED_AT));
        assertThatIllegalArgumentException().isThrownBy(() -> Tenant.create(new TenantId(UUID.randomUUID()), "   ", CREATED_AT));
        assertThatIllegalArgumentException().isThrownBy(() -> Tenant.create(new TenantId(UUID.randomUUID()), "\u3000", CREATED_AT));
        assertThatIllegalArgumentException().isThrownBy(() -> Tenant.create(new TenantId(UUID.randomUUID()), "\u00A0", CREATED_AT));
        assertThatIllegalArgumentException().isThrownBy(() -> Tenant.create(new TenantId(UUID.randomUUID()), "\u2007", CREATED_AT));
        assertThatIllegalArgumentException().isThrownBy(() -> Tenant.create(new TenantId(UUID.randomUUID()), "\u202F", CREATED_AT));
        assertThatIllegalArgumentException().isThrownBy(() -> Tenant.create(new TenantId(UUID.randomUUID()), "\u0085", CREATED_AT));
        assertThatIllegalArgumentException().isThrownBy(() -> Tenant.create(new TenantId(UUID.randomUUID()), "Acme\0West", CREATED_AT));
        assertThatIllegalArgumentException().isThrownBy(() -> Tenant.create(
                new TenantId(UUID.randomUUID()), "x".repeat(Tenant.DISPLAY_NAME_MAX_LENGTH + 1), CREATED_AT
        ));
    }

    @Test
    void countsDisplayNameLengthByUnicodeCodePoints() {
        String maximumLengthName = "😀".repeat(Tenant.DISPLAY_NAME_MAX_LENGTH);

        Tenant tenant = Tenant.create(new TenantId(UUID.randomUUID()), maximumLengthName, CREATED_AT);

        assertThat(tenant.displayName()).isEqualTo(maximumLengthName);
        assertThatIllegalArgumentException().isThrownBy(() -> Tenant.create(
                new TenantId(UUID.randomUUID()), "😀".repeat(Tenant.DISPLAY_NAME_MAX_LENGTH + 1), CREATED_AT
        ));
    }

    @Test
    void allowsTheTenantLifecycleTransitions() {
        Tenant tenant = Tenant.create(new TenantId(UUID.randomUUID()), "Workspace", CREATED_AT);
        Instant suspendedAt = CREATED_AT.plusSeconds(60);
        Instant activeAt = suspendedAt.plusSeconds(60);
        Instant archivedAt = activeAt.plusSeconds(60);

        tenant.suspend(suspendedAt);
        assertThat(tenant.status()).isEqualTo(TenantStatus.SUSPENDED);
        assertThat(tenant.updatedAt()).isEqualTo(suspendedAt);

        tenant.activate(activeAt);
        assertThat(tenant.status()).isEqualTo(TenantStatus.ACTIVE);

        tenant.archive(archivedAt);
        assertThat(tenant.status()).isEqualTo(TenantStatus.ARCHIVED);
        assertThat(tenant.updatedAt()).isEqualTo(archivedAt);
    }

    @Test
    void allowsArchivingASuspendedTenant() {
        Tenant tenant = Tenant.create(new TenantId(UUID.randomUUID()), "Workspace", CREATED_AT);
        tenant.suspend(CREATED_AT.plusSeconds(60));

        tenant.archive(CREATED_AT.plusSeconds(120));

        assertThat(tenant.status()).isEqualTo(TenantStatus.ARCHIVED);
    }

    @Test
    void rejectsInvalidAndTerminalTenantTransitions() {
        Tenant tenant = Tenant.create(new TenantId(UUID.randomUUID()), "Workspace", CREATED_AT);

        assertThatIllegalStateException().isThrownBy(() -> tenant.activate(CREATED_AT.plusSeconds(60)));

        tenant.archive(CREATED_AT.plusSeconds(60));

        assertThatIllegalStateException().isThrownBy(() -> tenant.suspend(CREATED_AT.plusSeconds(120)));
        assertThatIllegalStateException().isThrownBy(() -> tenant.activate(CREATED_AT.plusSeconds(120)));
        assertThatIllegalStateException().isThrownBy(() -> tenant.archive(CREATED_AT.plusSeconds(120)));
    }

    @Test
    void rejectsTransitionTimesBeforeTheCurrentState() {
        Tenant tenant = Tenant.create(new TenantId(UUID.randomUUID()), "Workspace", CREATED_AT);
        tenant.suspend(CREATED_AT.plusSeconds(60));

        assertThatIllegalArgumentException().isThrownBy(() -> tenant.activate(CREATED_AT.plusSeconds(30)));
    }
}
