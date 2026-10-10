package io.github.stevdrey.dokene.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class AuditEventTest {
    @ParameterizedTest
    @EnumSource(AuditDenialReason.class)
    void noTenantContextReasonIsExclusiveToGlobalDenials(AuditDenialReason reason) {
        Runnable global = () -> new AuditEvent(UUID.randomUUID(), Instant.now(), null, null, null,
                AuditEventType.AUTHORIZATION_DENIED, null, AuditOutcome.DENIED, UUID.randomUUID(),
                new AuditMetadata.AuthorizationDenied(null, reason));
        Runnable attributed = () -> new AuditEvent(UUID.randomUUID(), Instant.now(), new TenantId(UUID.randomUUID()),
                new IdentityId(UUID.randomUUID()), new TenantMembershipId(UUID.randomUUID()),
                AuditEventType.AUTHORIZATION_DENIED, null, AuditOutcome.DENIED, UUID.randomUUID(),
                new AuditMetadata.AuthorizationDenied(null, reason));
        if (reason == AuditDenialReason.NO_TENANT_CONTEXT) {
            assertThatCode(global::run).doesNotThrowAnyException();
            assertThatThrownBy(attributed::run).isInstanceOf(IllegalArgumentException.class);
        } else {
            assertThatCode(attributed::run).doesNotThrowAnyException();
            assertThatThrownBy(global::run).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @ParameterizedTest
    @EnumSource(AiAuditOutcome.class)
    void aiInvocationOutcomeEventsMapEachOutcomeToItsAuditOutcome(AiAuditOutcome ai) {
        AiAuditDetail detail = switch (ai) {
            case GENERATED, MODEL_REFUSED -> AiAuditDetail.NONE;
            case GATE_REJECTED -> AiAuditDetail.DISALLOWED_ACTION;
            case FAILED -> AiAuditDetail.TIMEOUT;
        };
        AuditOutcome expected = switch (ai) {
            case GENERATED, MODEL_REFUSED -> AuditOutcome.SUCCESS;
            case GATE_REJECTED -> AuditOutcome.DENIED;
            case FAILED -> AuditOutcome.FAILURE;
        };
        for (AuditOutcome candidate : AuditOutcome.values()) {
            Runnable create = () -> aiEvent(candidate, new AuditMetadata.AiInvocation(
                    AiAuditOperation.MESSAGE_DRAFT, ai, detail));
            if (candidate == expected) {
                assertThatCode(create::run).doesNotThrowAnyException();
            } else {
                assertThatThrownBy(create::run).isInstanceOf(IllegalArgumentException.class);
            }
        }
    }

    @Test
    void aiInvocationMetadataEnforcesConsistentClosedDetails() {
        assertThatThrownBy(() -> new AuditMetadata.AiInvocation(AiAuditOperation.NEXT_BEST_ACTION,
                AiAuditOutcome.GENERATED, AiAuditDetail.TIMEOUT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditMetadata.AiInvocation(AiAuditOperation.NEXT_BEST_ACTION,
                AiAuditOutcome.FAILED, AiAuditDetail.NONE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditMetadata.AiInvocation(AiAuditOperation.NEXT_BEST_ACTION,
                AiAuditOutcome.FAILED, AiAuditDetail.STALE_STATE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditMetadata.AiInvocation(AiAuditOperation.NEXT_BEST_ACTION,
                AiAuditOutcome.GATE_REJECTED, AiAuditDetail.TIMEOUT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditMetadata.AiInvocation(null, AiAuditOutcome.GENERATED, AiAuditDetail.NONE))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void aiInvocationEventRequiresTenantAttributionAndCustomerTarget() {
        AuditMetadata ai = new AuditMetadata.AiInvocation(AiAuditOperation.NEXT_BEST_ACTION,
                AiAuditOutcome.GENERATED, AiAuditDetail.NONE);
        assertThatThrownBy(() -> new AuditEvent(UUID.randomUUID(), Instant.now(), null, null, null,
                AuditEventType.AI_INVOCATION_OUTCOME, new AuditTarget(AuditTarget.Type.CUSTOMER, UUID.randomUUID()),
                AuditOutcome.SUCCESS, UUID.randomUUID(), ai)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditEvent(UUID.randomUUID(), Instant.now(), new TenantId(UUID.randomUUID()),
                new IdentityId(UUID.randomUUID()), new TenantMembershipId(UUID.randomUUID()),
                AuditEventType.AI_INVOCATION_OUTCOME, new AuditTarget(AuditTarget.Type.PURCHASE, UUID.randomUUID()),
                AuditOutcome.SUCCESS, UUID.randomUUID(), ai)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditEvent(UUID.randomUUID(), Instant.now(), new TenantId(UUID.randomUUID()),
                new IdentityId(UUID.randomUUID()), new TenantMembershipId(UUID.randomUUID()),
                AuditEventType.AI_INVOCATION_OUTCOME, new AuditTarget(AuditTarget.Type.CUSTOMER, UUID.randomUUID()),
                AuditOutcome.SUCCESS, UUID.randomUUID(), new AuditMetadata.CustomerMutation()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static AuditEvent aiEvent(AuditOutcome outcome, AuditMetadata metadata) {
        return new AuditEvent(UUID.randomUUID(), Instant.now(), new TenantId(UUID.randomUUID()),
                new IdentityId(UUID.randomUUID()), new TenantMembershipId(UUID.randomUUID()),
                AuditEventType.AI_INVOCATION_OUTCOME, new AuditTarget(AuditTarget.Type.CUSTOMER, UUID.randomUUID()),
                outcome, UUID.randomUUID(), metadata);
    }

    @Test
    void normalizesTimestampToDatabasePrecisionAndRequiresCorrelation() {
        AuditEvent event = denial(Instant.parse("2026-09-01T01:02:03.123456789Z"), UUID.randomUUID(), null, null, null);
        assertThat(event.timestamp()).isEqualTo(Instant.parse("2026-09-01T01:02:03.123456Z"));
        assertThatThrownBy(() -> denial(Instant.now(), null, null, null, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsPartialAttributionAndInvalidEventShapes() {
        assertThatThrownBy(() -> denial(Instant.now(), UUID.randomUUID(), new TenantId(UUID.randomUUID()), null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditEvent(UUID.randomUUID(), Instant.now(), null, null, null,
                AuditEventType.MEMBERSHIP_ROLE_CHANGED, new AuditTarget(AuditTarget.Type.MEMBERSHIP, UUID.randomUUID()),
                AuditOutcome.SUCCESS, UUID.randomUUID(), new AuditMetadata.MembershipRoleChanged(TenantRole.VIEWER, TenantRole.ADMIN)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditEvent(UUID.randomUUID(), Instant.now(), null, null, null,
                AuditEventType.AUTHORIZATION_DENIED, null, AuditOutcome.DENIED, UUID.randomUUID(),
                new AuditMetadata.AuthorizationDenied(null, AuditDenialReason.CROSS_TENANT_RESOURCE)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditMetadata.MembershipRoleChanged(TenantRole.OWNER, TenantRole.ADMIN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditMetadata.MembershipRoleChanged(TenantRole.ADMIN, TenantRole.ADMIN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsOnlyPiiFreeCustomerMutationShape() {
        TenantId tenant = TenantId.random();
        AuditEvent event = new AuditEvent(UUID.randomUUID(), Instant.now(), tenant,
                new IdentityId(UUID.randomUUID()), TenantMembershipId.random(), AuditEventType.CUSTOMER_CREATED,
                new AuditTarget(AuditTarget.Type.CUSTOMER, UUID.randomUUID()), AuditOutcome.SUCCESS,
                UUID.randomUUID(), new AuditMetadata.CustomerMutation());

        assertThat(event.metadata()).isInstanceOf(AuditMetadata.CustomerMutation.class);
        assertThatThrownBy(() -> new AuditEvent(UUID.randomUUID(), Instant.now(), tenant,
                new IdentityId(UUID.randomUUID()), TenantMembershipId.random(), AuditEventType.CUSTOMER_UPDATED,
                new AuditTarget(AuditTarget.Type.MEMBERSHIP, UUID.randomUUID()), AuditOutcome.SUCCESS,
                UUID.randomUUID(), new AuditMetadata.CustomerMutation())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsOnlyPiiFreePurchaseMutationShape() {
        TenantId tenant = TenantId.random();
        AuditEvent event = new AuditEvent(UUID.randomUUID(), Instant.now(), tenant,
                new IdentityId(UUID.randomUUID()), TenantMembershipId.random(), AuditEventType.PURCHASE_RECORDED,
                new AuditTarget(AuditTarget.Type.PURCHASE, UUID.randomUUID()), AuditOutcome.SUCCESS,
                UUID.randomUUID(), new AuditMetadata.PurchaseMutation());
        assertThat(event.metadata()).isInstanceOf(AuditMetadata.PurchaseMutation.class);
        assertThatThrownBy(() -> new AuditEvent(UUID.randomUUID(), Instant.now(), tenant,
                new IdentityId(UUID.randomUUID()), TenantMembershipId.random(), AuditEventType.PURCHASE_CORRECTED,
                new AuditTarget(AuditTarget.Type.CUSTOMER, UUID.randomUUID()), AuditOutcome.SUCCESS,
                UUID.randomUUID(), new AuditMetadata.PurchaseMutation())).isInstanceOf(IllegalArgumentException.class);
    }

    private AuditEvent denial(Instant timestamp, UUID correlation, TenantId tenant, IdentityId actor, TenantMembershipId membership) {
        return new AuditEvent(UUID.randomUUID(), timestamp, tenant, actor, membership, AuditEventType.AUTHORIZATION_DENIED,
                null, AuditOutcome.DENIED, correlation, new AuditMetadata.AuthorizationDenied(null, AuditDenialReason.NO_TENANT_CONTEXT));
    }

    @Test
    void messageTransitionEventsRequireAMessageTargetClosedMetadataAndAMatchingSourceStatus() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        IdentityId actorId = new IdentityId(UUID.randomUUID());
        TenantMembershipId membershipId = new TenantMembershipId(UUID.randomUUID());
        AuditTarget message = new AuditTarget(AuditTarget.Type.MESSAGE, UUID.randomUUID());
        var approved = new AuditMetadata.MessageTransition(AuditMessageStatus.PENDING_APPROVAL,
                AuditMessageStatus.APPROVED, null, null);

        assertThatCode(() -> new AuditEvent(UUID.randomUUID(), Instant.now(), tenantId, actorId, membershipId,
                AuditEventType.MESSAGE_APPROVED, message, AuditOutcome.SUCCESS, UUID.randomUUID(), approved))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> new AuditEvent(UUID.randomUUID(), Instant.now(), tenantId, actorId, membershipId,
                AuditEventType.MESSAGE_SUBMITTED, message, AuditOutcome.SUCCESS, UUID.randomUUID(), approved))
                .as("submitted has no source status").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditEvent(UUID.randomUUID(), Instant.now(), tenantId, actorId, membershipId,
                AuditEventType.MESSAGE_APPROVED, new AuditTarget(AuditTarget.Type.CUSTOMER, UUID.randomUUID()),
                AuditOutcome.SUCCESS, UUID.randomUUID(), approved))
                .as("target must be the message").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditEvent(UUID.randomUUID(), Instant.now(), tenantId, actorId, membershipId,
                AuditEventType.MESSAGE_APPROVED, message, AuditOutcome.DENIED, UUID.randomUUID(), approved))
                .as("transitions are successes").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditEvent(UUID.randomUUID(), Instant.now(), tenantId, actorId, membershipId,
                AuditEventType.MESSAGE_APPROVED, message, AuditOutcome.SUCCESS, UUID.randomUUID(),
                new AuditMetadata.IntegrationToggle(true)))
                .as("metadata must be a transition").isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyDeliveryUpdatesMayBeAttributedToTheTenantAlone() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        AuditTarget message = new AuditTarget(AuditTarget.Type.MESSAGE, UUID.randomUUID());
        var delivered = new AuditMetadata.MessageTransition(AuditMessageStatus.SENT, AuditMessageStatus.DELIVERED,
                null, null);

        assertThatCode(() -> new AuditEvent(UUID.randomUUID(), Instant.now(), tenantId, null, null,
                AuditEventType.MESSAGE_DELIVERY_UPDATED, message, AuditOutcome.SUCCESS, UUID.randomUUID(), delivered))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> new AuditEvent(UUID.randomUUID(), Instant.now(), tenantId, null, null,
                AuditEventType.MESSAGE_SENT, message, AuditOutcome.SUCCESS, UUID.randomUUID(),
                new AuditMetadata.MessageTransition(AuditMessageStatus.SENDING, AuditMessageStatus.SENT, null, 1)))
                .as("a member transition needs full attribution").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditEvent(UUID.randomUUID(), Instant.now(), null, null, null,
                AuditEventType.MESSAGE_DELIVERY_UPDATED, message, AuditOutcome.SUCCESS, UUID.randomUUID(), delivered))
                .as("the tenant is still required").isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void integrationAndTemplateMappingEventsCarryOnlyTheToggle() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        IdentityId actorId = new IdentityId(UUID.randomUUID());
        TenantMembershipId membershipId = new TenantMembershipId(UUID.randomUUID());

        assertThatCode(() -> new AuditEvent(UUID.randomUUID(), Instant.now(), tenantId, actorId, membershipId,
                AuditEventType.TEMPLATE_MAPPING_UPDATED, new AuditTarget(AuditTarget.Type.TEMPLATE_MAPPING, UUID.randomUUID()),
                AuditOutcome.SUCCESS, UUID.randomUUID(), new AuditMetadata.IntegrationToggle(false)))
                .doesNotThrowAnyException();
        assertThatCode(() -> new AuditEvent(UUID.randomUUID(), Instant.now(), tenantId, actorId, membershipId,
                AuditEventType.OUTBOUND_KILL_SWITCH_CHANGED, new AuditTarget(AuditTarget.Type.INTEGRATION, UUID.randomUUID()),
                AuditOutcome.SUCCESS, UUID.randomUUID(), new AuditMetadata.IntegrationToggle(true)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> new AuditEvent(UUID.randomUUID(), Instant.now(), tenantId, actorId, membershipId,
                AuditEventType.INTEGRATION_UPDATED, new AuditTarget(AuditTarget.Type.TEMPLATE_MAPPING, UUID.randomUUID()),
                AuditOutcome.SUCCESS, UUID.randomUUID(), new AuditMetadata.IntegrationToggle(true)))
                .as("integration events target an integration").isInstanceOf(IllegalArgumentException.class);
    }
}
