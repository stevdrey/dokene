// ABOUTME: Pins when a proof of phone number ownership survives a change and when it is cleared (issue 210).
// ABOUTME: A proof binds the enabled phone number id, business account and credential revision; nothing else voids it.
package io.github.stevdrey.dokene.integration.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WhatsAppIntegrationTest {
    private static final String PHONE = "100000000000001";
    private static final String WABA = "200000000000001";
    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-10-01T11:00:00Z");

    private final WhatsAppIntegration created = WhatsAppIntegration.create(new TenantId(UUID.randomUUID()), PHONE,
            WABA, "+50688887777", true, "wa-token", T0);

    @Test
    void aNewMappingIsUnverifiedUntilItsOwnershipIsProven() {
        assertThat(created.ownershipVerified()).isFalse();
        assertThat(created.ownershipVerifiedAt()).isNull();

        WhatsAppIntegration verified = created.verifyOwnership(T1);
        assertThat(verified.ownershipVerified()).isTrue();
        assertThat(verified.ownershipVerifiedAt()).isEqualTo(T1);
        assertThat(verified.credentialRevision()).isEqualTo(created.credentialRevision());
        assertThat(verified.withVersion(4).ownershipVerifiedAt()).isEqualTo(T1);
    }

    @Test
    void changingTheNumberTheBusinessAccountOrTheCredentialClearsTheProof() {
        WhatsAppIntegration verified = created.verifyOwnership(T1);

        assertThat(verified.update("100000000000002", WABA, "+50688887777", true, "wa-token", T1)
                .ownershipVerified()).isFalse();
        assertThat(verified.update(PHONE, "200000000000002", "+50688887777", true, "wa-token", T1)
                .ownershipVerified()).isFalse();
        assertThat(verified.update(PHONE, WABA, "+50688887777", true, "wa-token-two", T1)
                .ownershipVerified()).isFalse();
        assertThat(verified.rotateCredential(T1).ownershipVerified()).isFalse();
    }

    @Test
    void disablingClearsTheProofAndADisabledIntegrationCannotBeVerified() {
        WhatsAppIntegration disabled = created.verifyOwnership(T1)
                .update(PHONE, WABA, "+50688887777", false, "wa-token", T1);
        assertThat(disabled.ownershipVerified()).isFalse();
        assertThatThrownBy(() -> disabled.verifyOwnership(T1)).isInstanceOf(IllegalStateException.class);
        // Re-enabling does not bring the proof back.
        assertThat(disabled.update(PHONE, WABA, "+50688887777", true, "wa-token", T1).ownershipVerified()).isFalse();
    }

    @Test
    void changingOnlyTheDisplayNumberKeepsTheProof() {
        WhatsAppIntegration verified = created.verifyOwnership(T1);
        assertThat(verified.update(PHONE, WABA, null, true, "wa-token", T1).ownershipVerifiedAt()).isEqualTo(T1);
    }

    @Test
    void recognisesMetaIdentifiersOnly() {
        assertThat(WhatsAppIntegration.isMetaId(PHONE)).isTrue();
        for (String invalid : java.util.Arrays.asList(null, "", "1234", "+50688887777", "12345a", "1".repeat(33))) {
            assertThat(WhatsAppIntegration.isMetaId(invalid)).isFalse();
        }
    }
}
