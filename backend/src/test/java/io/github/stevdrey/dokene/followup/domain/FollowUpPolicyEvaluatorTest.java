package io.github.stevdrey.dokene.followup.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.stevdrey.dokene.customer.domain.ConsentStatus;
import io.github.stevdrey.dokene.customer.domain.ContactChannel;
import io.github.stevdrey.dokene.customer.domain.ContactConsent;
import io.github.stevdrey.dokene.customer.domain.ContactIntentSource;
import io.github.stevdrey.dokene.customer.domain.ContactPolicy;
import io.github.stevdrey.dokene.customer.domain.Customer;
import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.customer.domain.CustomerPhone;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FollowUpPolicyEvaluatorTest {
    private static final Instant NOW = Instant.parse("2026-03-08T07:30:00Z");
    private final TenantId tenantId = new TenantId(UUID.randomUUID());
    private final CustomerId customerId = new CustomerId(UUID.randomUUID());
    private final CustomerPhone phone = CustomerPhone.create("+15551234567", true);
    private final Customer customer = Customer.create(customerId, tenantId, "Customer", null,
            List.of(phone), Instant.parse("2026-01-01T00:00:00Z"));
    private final ContactPolicy contactPolicy = new ContactPolicy(customerId, 1, false, null, null,
            List.of(new ContactConsent(phone.id(), ContactChannel.WHATSAPP, ConsentStatus.GRANTED,
                    ContactIntentSource.CUSTOMER_WRITTEN, Instant.parse("2026-01-01T00:00:00Z"))));
    private final TenantFollowUpPolicy tenantPolicy =
            new TenantFollowUpPolicy(tenantId, 30, ZoneId.of("America/New_York"));
    private final FollowUpPolicyEvaluator evaluator =
            new FollowUpPolicyEvaluator(Clock.fixed(NOW, ZoneId.of("UTC")));

    @Test
    void isDeterministicAndUsesTenantCalendarAcrossDst() {
        var customerPolicy = CustomerFollowUpPolicy.empty(tenantId, customerId);
        Instant purchase = Instant.parse("2026-02-06T23:30:00Z");
        var first = evaluator.evaluate(customer, contactPolicy, tenantPolicy, customerPolicy, purchase);
        var second = evaluator.evaluate(customer, contactPolicy, tenantPolicy, customerPolicy, purchase);
        assertThat(first).isEqualTo(second);
        assertThat(first.status()).isEqualTo(FollowUpStatus.DUE);
        assertThat(first.tenantDate()).isEqualTo(LocalDate.of(2026, 3, 8));
        assertThat(first.timingSource()).isEqualTo(FollowUpTimingSource.LAST_PURCHASE);
    }

    @Test
    void snoozeOverridesExplicitDateAndCustomerCadenceOverridesTenantCadence() {
        var policy = new CustomerFollowUpPolicy(tenantId, customerId, 7,
                LocalDate.of(2026, 3, 7), LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 1));
        var result = evaluator.evaluate(customer, contactPolicy, tenantPolicy, policy,
                Instant.parse("2025-01-01T00:00:00Z"));
        assertThat(result.status()).isEqualTo(FollowUpStatus.NOT_YET_DUE);
        assertThat(result.reasons()).containsExactly(FollowUpReason.SNOOZED);
        assertThat(result.timingSource()).isEqualTo(FollowUpTimingSource.SNOOZE);
        assertThat(result.effectiveCadenceDays()).isEqualTo(7);
    }

    @Test
    void noPurchaseWithoutAnotherAnchorIsIneligible() {
        var result = evaluator.evaluate(customer, contactPolicy, tenantPolicy,
                CustomerFollowUpPolicy.empty(tenantId, customerId), null);
        assertThat(result.status()).isEqualTo(FollowUpStatus.INELIGIBLE);
        assertThat(result.reasons()).containsExactly(FollowUpReason.NO_PURCHASE_HISTORY);
    }

    @Test
    void contactRestrictionOverridesOverdueTiming() {
        var blocked = new ContactPolicy(customerId, 2, true, ContactIntentSource.CUSTOMER_VERBAL,
                NOW, contactPolicy.consents());
        var policy = new CustomerFollowUpPolicy(tenantId, customerId, null,
                LocalDate.of(2020, 1, 1), null, null);
        var result = evaluator.evaluate(customer, blocked, tenantPolicy, policy, null);
        assertThat(result.status()).isEqualTo(FollowUpStatus.INELIGIBLE);
        assertThat(result.reasons()).containsExactly(FollowUpReason.DO_NOT_CONTACT);
        assertThat(result.eligible()).isFalse();
    }

    @Test
    void explicitDateAllowsCustomerWithoutPurchasesAndReportsOverdue() {
        var policy = new CustomerFollowUpPolicy(tenantId, customerId, null,
                LocalDate.of(2026, 3, 7), null, null);
        var result = evaluator.evaluate(customer, contactPolicy, tenantPolicy, policy, null);
        assertThat(result.status()).isEqualTo(FollowUpStatus.OVERDUE);
        assertThat(result.reasons()).containsExactly(FollowUpReason.OVERDUE);
        assertThat(result.timingSource()).isEqualTo(FollowUpTimingSource.EXPLICIT_DATE);
        assertThat(result.eligible()).isTrue();
    }

    @Test
    void changingTenantZoneCanChangeDueClassificationAtTheSameInstant() {
        var policy = new CustomerFollowUpPolicy(tenantId, customerId, null,
                LocalDate.of(2026, 3, 8), null, null);
        var newYork = evaluator.evaluate(customer, contactPolicy, tenantPolicy, policy, null);
        var honoluluPolicy = new TenantFollowUpPolicy(tenantId, 30, ZoneId.of("Pacific/Honolulu"));
        var honolulu = evaluator.evaluate(customer, contactPolicy, honoluluPolicy, policy, null);
        assertThat(newYork.status()).isEqualTo(FollowUpStatus.DUE);
        assertThat(honolulu.status()).isEqualTo(FollowUpStatus.NOT_YET_DUE);
        assertThat(honolulu.tenantDate()).isEqualTo(LocalDate.of(2026, 3, 7));
    }

    @Test
    void missingConsentIsAHardConstraint() {
        var unknown = new ContactPolicy(customerId, 0, false, null, null,
                List.of(ContactConsent.unknown(phone.id(), ContactChannel.WHATSAPP)));
        var policy = new CustomerFollowUpPolicy(tenantId, customerId, null,
                LocalDate.of(2020, 1, 1), null, null);
        var result = evaluator.evaluate(customer, unknown, tenantPolicy, policy, null);
        assertThat(result.status()).isEqualTo(FollowUpStatus.INELIGIBLE);
        assertThat(result.reasons()).containsExactly(FollowUpReason.NO_ELIGIBLE_CONTACT);
    }
}
