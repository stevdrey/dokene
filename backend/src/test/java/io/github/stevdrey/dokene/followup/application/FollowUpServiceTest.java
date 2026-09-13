package io.github.stevdrey.dokene.followup.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.stevdrey.dokene.audit.application.AuditRecorder;
import io.github.stevdrey.dokene.customer.application.ContactPolicyRepository;
import io.github.stevdrey.dokene.customer.application.CustomerRepository;
import io.github.stevdrey.dokene.customer.domain.Customer;
import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.followup.domain.CustomerFollowUpPolicy;
import io.github.stevdrey.dokene.followup.domain.FollowUpDismissal;
import io.github.stevdrey.dokene.followup.domain.ManualFollowUpCompletion;
import io.github.stevdrey.dokene.followup.domain.TenantFollowUpPolicy;
import io.github.stevdrey.dokene.purchase.application.PurchaseRepository;
import io.github.stevdrey.dokene.tenant.application.TenantAuthorizationService;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class FollowUpServiceTest {

    private CustomerRepository customers;
    private ContactPolicyRepository contacts;
    private PurchaseRepository purchases;
    private FollowUpPolicyRepository policies;
    private AuditRecorder audit;
    private TenantAuthorizationService authorization;
    private TenantContextProvider contexts;

    private final TenantId tenantId = TenantId.random();
    private final CustomerId customerId = new CustomerId(UUID.randomUUID());
    private final IdentityId identityId = new IdentityId(UUID.randomUUID());
    private final TenantMembershipId membershipId = TenantMembershipId.random();
    private final ZoneId tenantZone = ZoneId.of("America/Costa_Rica");
    private final Instant fixedInstant = Instant.parse("2026-09-10T05:59:59Z"); // 23:59:59 in America/Costa_Rica (UTC-6)
    private final Clock clock = Clock.fixed(fixedInstant, ZoneOffset.UTC);

    private FollowUpService service;
    private Customer customer;

    @BeforeEach
    void setUp() {
        customers = mock();
        contacts = mock();
        purchases = mock();
        policies = mock();
        audit = mock();
        authorization = mock();
        contexts = mock();

        TenantContext context = new TenantContext(tenantId, identityId, membershipId, TenantRole.ADMIN);
        when(contexts.requireCurrent()).thenReturn(context);

        customer = mock(Customer.class);
        when(customer.id()).thenReturn(customerId);
        when(customer.tenantId()).thenReturn(tenantId);
        when(customer.status()).thenReturn(io.github.stevdrey.dokene.customer.domain.CustomerStatus.ACTIVE);
        var phone = io.github.stevdrey.dokene.customer.domain.CustomerPhone.create("+15551234567", true);
        when(customer.phones()).thenReturn(List.of(phone));
        when(customers.findById(tenantId, customerId)).thenReturn(Optional.of(customer));
        when(customers.findByIdForUpdate(tenantId, customerId)).thenReturn(Optional.of(customer));
        when(policies.tenantPolicy(tenantId)).thenReturn(new TenantFollowUpPolicy(tenantId, 30, tenantZone, 0));

        LocalDate today = fixedInstant.atZone(tenantZone).toLocalDate();
        var contactPolicy = new io.github.stevdrey.dokene.customer.domain.ContactPolicy(customerId, 1, false, null, null,
                List.of(new io.github.stevdrey.dokene.customer.domain.ContactConsent(phone.id(),
                        io.github.stevdrey.dokene.customer.domain.ContactChannel.WHATSAPP,
                        io.github.stevdrey.dokene.customer.domain.ConsentStatus.GRANTED,
                        io.github.stevdrey.dokene.customer.domain.ContactIntentSource.CUSTOMER_WRITTEN,
                        fixedInstant.minusSeconds(3600))));
        when(contacts.find(customer)).thenReturn(contactPolicy);

        var dueCustomerPolicy = new CustomerFollowUpPolicy(tenantId, customerId, 30, today, null, null, 0);
        when(policies.customerPolicy(tenantId, customerId)).thenReturn(dueCustomerPolicy);

        service = new FollowUpService(customers, contacts, purchases, policies,
                authorization, contexts, clock, audit);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 3651, 10000})
    void configureCustomerRejectsCadenceOutsideAllowedBounds(int invalidCadence) {
        assertThatThrownBy(() -> service.configureCustomer(customerId, invalidCadence, null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cadence must be between 1 and 3650 days");
    }

    @Test
    void configureCustomerAllowsValidCadenceAndNullCadence() {
        CustomerFollowUpPolicy mockPolicy = new CustomerFollowUpPolicy(tenantId, customerId, 14, null, null, null, 1);
        when(policies.updateCustomerPolicy(tenantId, customerId, 14, null, 0)).thenReturn(mockPolicy);

        CustomerFollowUpPolicy result = service.configureCustomer(customerId, 14, null, 0);
        assertThat(result).isEqualTo(mockPolicy);

        when(policies.updateCustomerPolicy(tenantId, customerId, null, null, 1)).thenReturn(mockPolicy);
        CustomerFollowUpPolicy resultNull = service.configureCustomer(customerId, null, null, 1);
        assertThat(resultNull).isEqualTo(mockPolicy);
    }

    @Test
    void configureCustomerRejectsNegativeExpectedVersion() {
        assertThatThrownBy(() -> service.configureCustomer(customerId, 14, null, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Version cannot be negative");
    }

    @Test
    void recordManualFollowUpDerivesDateAndTimestampFromSingleInstant() {
        LocalDate expectedDate = fixedInstant.atZone(tenantZone).toLocalDate();
        ManualFollowUpCompletion completion = new ManualFollowUpCompletion(UUID.randomUUID(), tenantId, customerId,
                expectedDate, 1, fixedInstant, identityId, membershipId);
        when(policies.recordManualFollowUp(eq(tenantId), eq(customerId), eq(expectedDate), eq(0L),
                eq("idem-key-1"), eq(fixedInstant), eq(identityId), eq(membershipId), eq(null)))
                .thenReturn(new ManualFollowUpResult(completion, true));

        ManualFollowUpResult result = service.recordManualFollowUp(customerId, 0, "idem-key-1");

        assertThat(result.created()).isTrue();
        assertThat(result.completion()).isEqualTo(completion);

        ArgumentCaptor<LocalDate> dateCaptor = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<Instant> instantCaptor = ArgumentCaptor.forClass(Instant.class);

        verify(policies).recordManualFollowUp(eq(tenantId), eq(customerId), dateCaptor.capture(), eq(0L),
                eq("idem-key-1"), instantCaptor.capture(), eq(identityId), eq(membershipId), eq(null));

        assertThat(dateCaptor.getValue()).isEqualTo(expectedDate);
        assertThat(instantCaptor.getValue()).isEqualTo(fixedInstant);
        assertThat(instantCaptor.getValue().atZone(tenantZone).toLocalDate()).isEqualTo(dateCaptor.getValue());
    }

    @Test
    void recordManualFollowUpRejectsIneligibleCustomer() {
        // Customer has no granted consent
        when(contacts.find(customer)).thenReturn(new io.github.stevdrey.dokene.customer.domain.ContactPolicy(customerId,
                1, false, null, null, List.of()));
        assertThatThrownBy(() -> service.recordManualFollowUp(customerId, 0, "idem-key-1"))
                .isInstanceOf(FollowUpConflictException.class);
    }

    @Test
    void recordManualFollowUpAllowsEligibleCustomerWithoutPurchaseHistory() {
        LocalDate expectedDate = fixedInstant.atZone(tenantZone).toLocalDate();
        when(policies.customerPolicy(tenantId, customerId)).thenReturn(
                new CustomerFollowUpPolicy(tenantId, customerId, null, null, null, null, 0));
        var completion = new ManualFollowUpCompletion(UUID.randomUUID(), tenantId, customerId,
                expectedDate, 1, fixedInstant, identityId, membershipId);
        when(policies.recordManualFollowUp(eq(tenantId), eq(customerId), eq(expectedDate), eq(0L),
                eq("idem-key-no-purchase"), eq(fixedInstant), eq(identityId), eq(membershipId), eq(null)))
                .thenReturn(new ManualFollowUpResult(completion, true));

        var result = service.recordManualFollowUp(customerId, 0, "idem-key-no-purchase");

        assertThat(result).isEqualTo(new ManualFollowUpResult(completion, true));
    }

    @Test
    void dismissRecordsDismissalAndEmitsAudit() {
        LocalDate expectedDate = fixedInstant.atZone(tenantZone).toLocalDate();
        var dismissal = new FollowUpDismissal(UUID.randomUUID(), tenantId, customerId,
                expectedDate, 1, fixedInstant, identityId, membershipId, "not needed");
        when(policies.recordDismissal(tenantId, customerId, expectedDate, 0L, "dismiss-key-1",
                fixedInstant, identityId, membershipId, "not needed"))
                .thenReturn(new FollowUpDismissalResult(dismissal, true));

        var result = service.dismiss(customerId, 0L, "dismiss-key-1", "not needed");
        assertThat(result.created()).isTrue();
        assertThat(result.dismissal()).isEqualTo(dismissal);

        verify(audit).followUpMutated(io.github.stevdrey.dokene.audit.domain.AuditTarget.Type.CUSTOMER,
                customerId.value(), io.github.stevdrey.dokene.audit.domain.AuditEventType.FOLLOW_UP_DISMISSED);
    }

    @Test
    void dismissRejectsIneligibleOrNotYetDueCustomer() {
        // Ineligible (DNC)
        when(contacts.find(customer)).thenReturn(new io.github.stevdrey.dokene.customer.domain.ContactPolicy(customerId,
                1, true, null, null, List.of()));
        assertThatThrownBy(() -> service.dismiss(customerId, 0L, "dismiss-key-1", null))
                .isInstanceOf(FollowUpConflictException.class);

        // Not yet due
        LocalDate tomorrow = fixedInstant.atZone(tenantZone).toLocalDate().plusDays(1);
        UUID phoneId = customer.phones().getFirst().id();
        when(contacts.find(customer)).thenReturn(new io.github.stevdrey.dokene.customer.domain.ContactPolicy(customerId,
                1, false, null, null, List.of(new io.github.stevdrey.dokene.customer.domain.ContactConsent(
                        phoneId, io.github.stevdrey.dokene.customer.domain.ContactChannel.WHATSAPP,
                        io.github.stevdrey.dokene.customer.domain.ConsentStatus.GRANTED,
                        io.github.stevdrey.dokene.customer.domain.ContactIntentSource.CUSTOMER_WRITTEN,
                        fixedInstant.minusSeconds(3600)))));
        when(policies.customerPolicy(tenantId, customerId)).thenReturn(
                new CustomerFollowUpPolicy(tenantId, customerId, 30, tomorrow, null, null, 0));
        assertThatThrownBy(() -> service.dismiss(customerId, 0L, "dismiss-key-1", null))
                .isInstanceOf(FollowUpConflictException.class);
    }

    @Test
    void snoozeRejectsPastDateBasedOnTenantZone() {
        LocalDate yesterday = fixedInstant.atZone(tenantZone).toLocalDate().minusDays(1);
        assertThatThrownBy(() -> service.snooze(customerId, yesterday, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Snooze date cannot be in the past");
    }

    @Test
    void snoozeRejectsIneligibleOrNotYetDueCustomer() {
        // Ineligible (revoked consent)
        when(contacts.find(customer)).thenReturn(new io.github.stevdrey.dokene.customer.domain.ContactPolicy(customerId,
                1, false, null, null, List.of()));
        assertThatThrownBy(() -> service.snooze(customerId, fixedInstant.atZone(tenantZone).toLocalDate().plusDays(5), 0))
                .isInstanceOf(FollowUpConflictException.class);
    }

    @Test
    void dueQueueRequiresPermissionAndDelegatesToRepository() {
        var query = new FollowUpQueueQuery(null, null, 50);
        var expectedPage = new FollowUpQueuePage(List.of(), null);
        when(policies.findDueQueue(tenantId, query, fixedInstant)).thenReturn(expectedPage);

        var page = service.dueQueue(query);
        assertThat(page).isEqualTo(expectedPage);
        verify(authorization).requirePermission(TenantPermission.FOLLOWUP_READ);
    }

    @Test
    void dispositionsAcquireCustomerLockForUpdate() {
        when(policies.snooze(eq(tenantId), eq(customerId), any(), eq(0L)))
                .thenReturn(new CustomerFollowUpPolicy(tenantId, customerId, 30, null, null, null, 1L));
        LocalDate futureDate = fixedInstant.atZone(tenantZone).toLocalDate().plusDays(5);
        service.snooze(customerId, futureDate, 0L);
        verify(customers).findByIdForUpdate(tenantId, customerId);

        when(policies.recordDismissal(eq(tenantId), eq(customerId), any(), eq(0L), any(), any(), any(), any(), any()))
                .thenReturn(new FollowUpDismissalResult(mock(FollowUpDismissal.class), true));
        service.dismiss(customerId, 0L, "key-dismiss", "note");
        verify(customers, org.mockito.Mockito.times(2)).findByIdForUpdate(tenantId, customerId);

        when(policies.recordManualFollowUp(eq(tenantId), eq(customerId), any(), eq(0L), any(), any(), any(), any(), any()))
                .thenReturn(new ManualFollowUpResult(mock(ManualFollowUpCompletion.class), true));
        service.recordManualFollowUp(customerId, 0L, "key-manual", "note");
        verify(customers, org.mockito.Mockito.times(3)).findByIdForUpdate(tenantId, customerId);
    }
}
