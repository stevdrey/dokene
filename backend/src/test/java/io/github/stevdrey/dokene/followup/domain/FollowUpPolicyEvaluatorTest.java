package io.github.stevdrey.dokene.followup.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void acceptsRegionalIanaZonesAndUtcButRejectsFixedOffsets() {
        assertThat(new TenantFollowUpPolicy(tenantId, 30, ZoneId.of("America/Costa_Rica")).zoneId().getId())
                .isEqualTo("America/Costa_Rica");
        assertThat(new TenantFollowUpPolicy(tenantId, 30, ZoneId.of("UTC")).zoneId().getId()).isEqualTo("UTC");
        assertThatThrownBy(() -> new TenantFollowUpPolicy(tenantId, 30, ZoneId.of("+02:00")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TenantFollowUpPolicy(tenantId, 30, ZoneId.of("GMT+02:00")))
                .isInstanceOf(IllegalArgumentException.class);
    }
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
    void sameDaySnoozeRemainsNotYetDue() {
        LocalDate tenantToday = NOW.atZone(tenantPolicy.zoneId()).toLocalDate();
        var policy = new CustomerFollowUpPolicy(tenantId, customerId, 7,
                tenantToday.minusDays(1), tenantToday, null);

        var result = evaluator.evaluate(customer, contactPolicy, tenantPolicy, policy, null);

        assertThat(result.status()).isEqualTo(FollowUpStatus.NOT_YET_DUE);
        assertThat(result.reasons()).containsExactly(FollowUpReason.SNOOZED);
        assertThat(result.nextFollowUpDate()).isEqualTo(tenantToday);
        assertThat(result.timingSource()).isEqualTo(FollowUpTimingSource.SNOOZE);
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

    @Test
    void dismissalAdvancesCadenceAndSetsDismissalTimingSource() {
        // Today is 2026-03-08 in America/New_York (NOW is 2026-03-08T07:30:00Z)
        // Dismissed on 2026-03-01 with 14-day customer cadence: due on 2026-03-15 (NOT_YET_DUE)
        var notYetDuePolicy = new CustomerFollowUpPolicy(tenantId, customerId, 14,
                null, null, null, LocalDate.of(2026, 3, 1));
        var notYetDue = evaluator.evaluate(customer, contactPolicy, tenantPolicy, notYetDuePolicy, null);
        assertThat(notYetDue.status()).isEqualTo(FollowUpStatus.NOT_YET_DUE);
        assertThat(notYetDue.reasons()).containsExactly(FollowUpReason.CADENCE_NOT_DUE);
        assertThat(notYetDue.timingSource()).isEqualTo(FollowUpTimingSource.LAST_DISMISSAL);
        assertThat(notYetDue.nextFollowUpDate()).isEqualTo(LocalDate.of(2026, 3, 15));

        // Dismissed on 2026-02-22 with 14-day cadence: due on 2026-03-08 (DUE today)
        var dueTodayPolicy = new CustomerFollowUpPolicy(tenantId, customerId, 14,
                null, null, null, LocalDate.of(2026, 2, 22));
        var dueToday = evaluator.evaluate(customer, contactPolicy, tenantPolicy, dueTodayPolicy, null);
        assertThat(dueToday.status()).isEqualTo(FollowUpStatus.DUE);
        assertThat(dueToday.reasons()).containsExactly(FollowUpReason.DUE_TODAY);
        assertThat(dueToday.timingSource()).isEqualTo(FollowUpTimingSource.LAST_DISMISSAL);

        // Dismissed on 2026-02-20 with 14-day cadence: due on 2026-03-06 (OVERDUE)
        var overduePolicy = new CustomerFollowUpPolicy(tenantId, customerId, 14,
                null, null, null, LocalDate.of(2026, 2, 20));
        var overdue = evaluator.evaluate(customer, contactPolicy, tenantPolicy, overduePolicy, null);
        assertThat(overdue.status()).isEqualTo(FollowUpStatus.OVERDUE);
        assertThat(overdue.reasons()).containsExactly(FollowUpReason.OVERDUE);
        assertThat(overdue.timingSource()).isEqualTo(FollowUpTimingSource.LAST_DISMISSAL);
    }

    @Test
    void latestAnchorWinsBetweenManualFollowUpDismissalAndPurchase() {
        // Dismissal (2026-02-25) is newer than manual follow-up (2026-02-01) and purchase (2026-01-15)
        var dismissalNewer = new CustomerFollowUpPolicy(tenantId, customerId, 30,
                null, null, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 25));
        var resultDismissal = evaluator.evaluate(customer, contactPolicy, tenantPolicy, dismissalNewer,
                Instant.parse("2026-01-15T00:00:00Z"));
        assertThat(resultDismissal.timingSource()).isEqualTo(FollowUpTimingSource.LAST_DISMISSAL);

        // New purchase (2026-03-01) is newer than dismissal (2026-02-15)
        var purchaseNewer = new CustomerFollowUpPolicy(tenantId, customerId, 30,
                null, null, null, LocalDate.of(2026, 2, 15));
        var resultPurchase = evaluator.evaluate(customer, contactPolicy, tenantPolicy, purchaseNewer,
                Instant.parse("2026-03-01T00:00:00Z"));
        assertThat(resultPurchase.timingSource()).isEqualTo(FollowUpTimingSource.LAST_PURCHASE);

        // Manual follow-up (2026-03-02) is newer than dismissal (2026-02-15) and purchase (2026-02-01)
        var manualNewer = new CustomerFollowUpPolicy(tenantId, customerId, 30,
                null, null, LocalDate.of(2026, 3, 2), LocalDate.of(2026, 2, 15));
        var resultManual = evaluator.evaluate(customer, contactPolicy, tenantPolicy, manualNewer,
                Instant.parse("2026-02-01T00:00:00Z"));
        assertThat(resultManual.timingSource()).isEqualTo(FollowUpTimingSource.LAST_MANUAL_FOLLOW_UP);
    }
}
