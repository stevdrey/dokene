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
import io.github.stevdrey.dokene.followup.domain.ManualFollowUpCompletion;
import io.github.stevdrey.dokene.followup.domain.TenantFollowUpPolicy;
import io.github.stevdrey.dokene.purchase.application.PurchaseRepository;
import io.github.stevdrey.dokene.tenant.application.TenantAuthorizationService;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
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
        when(customers.findById(tenantId, customerId)).thenReturn(Optional.of(customer));
        when(policies.tenantPolicy(tenantId)).thenReturn(new TenantFollowUpPolicy(tenantId, 30, tenantZone, 0));

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
                eq("idem-key-1"), eq(fixedInstant), eq(identityId), eq(membershipId)))
                .thenReturn(new ManualFollowUpResult(completion, true));

        ManualFollowUpResult result = service.recordManualFollowUp(customerId, 0, "idem-key-1");

        assertThat(result.created()).isTrue();
        assertThat(result.completion()).isEqualTo(completion);

        ArgumentCaptor<LocalDate> dateCaptor = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<Instant> instantCaptor = ArgumentCaptor.forClass(Instant.class);

        verify(policies).recordManualFollowUp(eq(tenantId), eq(customerId), dateCaptor.capture(), eq(0L),
                eq("idem-key-1"), instantCaptor.capture(), eq(identityId), eq(membershipId));

        assertThat(dateCaptor.getValue()).isEqualTo(expectedDate);
        assertThat(instantCaptor.getValue()).isEqualTo(fixedInstant);
        assertThat(instantCaptor.getValue().atZone(tenantZone).toLocalDate()).isEqualTo(dateCaptor.getValue());
    }

    @Test
    void snoozeRejectsPastDateBasedOnTenantZone() {
        LocalDate yesterday = fixedInstant.atZone(tenantZone).toLocalDate().minusDays(1);
        assertThatThrownBy(() -> service.snooze(customerId, yesterday, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Snooze date cannot be in the past");
    }
}
