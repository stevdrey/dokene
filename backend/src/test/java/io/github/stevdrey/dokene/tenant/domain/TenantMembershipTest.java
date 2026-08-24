package io.github.stevdrey.dokene.tenant.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TenantMembershipTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-23T00:00:00Z");

    @Test
    void createsInvitedAndActiveMembershipsExplicitly() {
        TenantMembership invited = TenantMembership.invite(
                new TenantMembershipId(UUID.randomUUID()),
                new TenantId(UUID.randomUUID()),
                new IdentityId(UUID.randomUUID()),
                TenantRole.OPERATOR,
                CREATED_AT
        );
        TenantMembership active = TenantMembership.createActive(
                new TenantMembershipId(UUID.randomUUID()),
                new TenantId(UUID.randomUUID()),
                new IdentityId(UUID.randomUUID()),
                TenantRole.OWNER,
                CREATED_AT
        );

        assertThat(invited.status()).isEqualTo(TenantMembershipStatus.INVITED);
        assertThat(active.status()).isEqualTo(TenantMembershipStatus.ACTIVE);
        assertThat(active.updatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void rejectsMissingMembershipFields() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        IdentityId identityId = new IdentityId(UUID.randomUUID());

        assertThatIllegalArgumentException().isThrownBy(() -> new TenantMembershipId(null));
        assertThatIllegalArgumentException().isThrownBy(() -> new IdentityId(null));
        assertThatIllegalArgumentException().isThrownBy(() -> TenantMembership.invite(
                null, tenantId, identityId, TenantRole.VIEWER, CREATED_AT
        ));
        assertThatIllegalArgumentException().isThrownBy(() -> TenantMembership.invite(
                new TenantMembershipId(UUID.randomUUID()), null, identityId, TenantRole.VIEWER, CREATED_AT
        ));
        assertThatIllegalArgumentException().isThrownBy(() -> TenantMembership.invite(
                new TenantMembershipId(UUID.randomUUID()), tenantId, identityId, null, CREATED_AT
        ));
    }

    @Test
    void normalizesTimestampsToMicrosecondPrecision() {
        Instant createdAt = Instant.parse("2026-08-23T00:00:00.123456789Z");
        TenantMembership membership = TenantMembership.createActive(
                new TenantMembershipId(UUID.randomUUID()),
                new TenantId(UUID.randomUUID()),
                new IdentityId(UUID.randomUUID()),
                TenantRole.OPERATOR,
                createdAt
        );

        membership.suspend(Instant.parse("2026-08-23T00:01:00.999999900Z"));

        assertThat(membership.createdAt()).isEqualTo(Instant.parse("2026-08-23T00:00:00.123457Z"));
        assertThat(membership.updatedAt()).isEqualTo(Instant.parse("2026-08-23T00:01:01Z"));
    }

    @Test
    void allowsAllMembershipLifecycleTransitions() {
        TenantMembership membership = invitedMembership();
        Instant activeAt = CREATED_AT.plusSeconds(60);
        Instant suspendedAt = activeAt.plusSeconds(60);
        Instant reactivatedAt = suspendedAt.plusSeconds(60);
        Instant revokedAt = reactivatedAt.plusSeconds(60);

        membership.activate(activeAt);
        assertThat(membership.status()).isEqualTo(TenantMembershipStatus.ACTIVE);

        membership.suspend(suspendedAt);
        assertThat(membership.status()).isEqualTo(TenantMembershipStatus.SUSPENDED);

        membership.activate(reactivatedAt);
        membership.revoke(revokedAt);

        assertThat(membership.status()).isEqualTo(TenantMembershipStatus.REVOKED);
        assertThat(membership.updatedAt()).isEqualTo(revokedAt);
    }

    @Test
    void allowsRevocationFromEveryNonTerminalStatus() {
        TenantMembership invited = invitedMembership();
        invited.revoke(CREATED_AT.plusSeconds(60));

        TenantMembership active = activeMembership();
        active.revoke(CREATED_AT.plusSeconds(60));

        TenantMembership suspended = activeMembership();
        suspended.suspend(CREATED_AT.plusSeconds(60));
        suspended.revoke(CREATED_AT.plusSeconds(120));

        assertThat(invited.status()).isEqualTo(TenantMembershipStatus.REVOKED);
        assertThat(active.status()).isEqualTo(TenantMembershipStatus.REVOKED);
        assertThat(suspended.status()).isEqualTo(TenantMembershipStatus.REVOKED);
    }

    @Test
    void rejectsInvalidAndTerminalMembershipTransitions() {
        TenantMembership membership = invitedMembership();

        assertThatIllegalStateException().isThrownBy(() -> membership.suspend(CREATED_AT.plusSeconds(60)));

        membership.revoke(CREATED_AT.plusSeconds(60));

        assertThatIllegalStateException().isThrownBy(() -> membership.activate(CREATED_AT.plusSeconds(120)));
        assertThatIllegalStateException().isThrownBy(() -> membership.suspend(CREATED_AT.plusSeconds(120)));
        assertThatIllegalStateException().isThrownBy(() -> membership.revoke(CREATED_AT.plusSeconds(120)));
    }

    @Test
    void rejectsTransitionTimesBeforeTheCurrentState() {
        TenantMembership membership = activeMembership();
        membership.suspend(CREATED_AT.plusSeconds(60));

        assertThatIllegalArgumentException().isThrownBy(() -> membership.activate(CREATED_AT.plusSeconds(30)));
    }

    private TenantMembership invitedMembership() {
        return TenantMembership.invite(
                new TenantMembershipId(UUID.randomUUID()),
                new TenantId(UUID.randomUUID()),
                new IdentityId(UUID.randomUUID()),
                TenantRole.OPERATOR,
                CREATED_AT
        );
    }

    private TenantMembership activeMembership() {
        return TenantMembership.createActive(
                new TenantMembershipId(UUID.randomUUID()),
                new TenantId(UUID.randomUUID()),
                new IdentityId(UUID.randomUUID()),
                TenantRole.OPERATOR,
                CREATED_AT
        );
    }
}
