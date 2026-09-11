package io.github.stevdrey.dokene.followup.domain;

import io.github.stevdrey.dokene.customer.domain.ConsentStatus;
import io.github.stevdrey.dokene.customer.domain.ContactChannel;
import io.github.stevdrey.dokene.customer.domain.ContactPolicy;
import io.github.stevdrey.dokene.customer.domain.Customer;
import io.github.stevdrey.dokene.customer.domain.CustomerStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Objects;

public final class FollowUpPolicyEvaluator {
    private final Clock clock;

    public FollowUpPolicyEvaluator(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    public FollowUpEvaluation evaluate(Customer customer, ContactPolicy contactPolicy,
            TenantFollowUpPolicy tenantPolicy, CustomerFollowUpPolicy customerPolicy, Instant lastPurchaseAt) {
        Objects.requireNonNull(customer, "Customer is required");
        Objects.requireNonNull(contactPolicy, "Contact policy is required");
        Objects.requireNonNull(tenantPolicy, "Tenant follow-up policy is required");
        Objects.requireNonNull(customerPolicy, "Customer follow-up policy is required");
        if (!customer.tenantId().equals(tenantPolicy.tenantId())
                || !customer.tenantId().equals(customerPolicy.tenantId())
                || !customer.id().equals(customerPolicy.customerId())
                || !customer.id().equals(contactPolicy.customerId())) {
            throw new IllegalArgumentException("Follow-up inputs belong to different resources");
        }

        Instant now = clock.instant();
        LocalDate today = now.atZone(tenantPolicy.zoneId()).toLocalDate();
        int cadence = customerPolicy.cadenceDays() == null
                ? tenantPolicy.cadenceDays() : customerPolicy.cadenceDays();
        var hardReasons = new ArrayList<FollowUpReason>();
        if (contactPolicy.doNotContact()) hardReasons.add(FollowUpReason.DO_NOT_CONTACT);
        if (customer.status() == CustomerStatus.ARCHIVED) hardReasons.add(FollowUpReason.CUSTOMER_ARCHIVED);
        boolean eligibleContact = customer.phones().stream().anyMatch(phone -> contactPolicy.consents().stream()
                .anyMatch(consent -> consent.contactId().equals(phone.id())
                        && consent.channel() == ContactChannel.WHATSAPP
                        && consent.status() == ConsentStatus.GRANTED));
        if (!eligibleContact) hardReasons.add(FollowUpReason.NO_ELIGIBLE_CONTACT);
        if (!hardReasons.isEmpty()) {
            return result(customer, FollowUpStatus.INELIGIBLE, hardReasons, now, today, tenantPolicy,
                    null, FollowUpTimingSource.NONE, cadence, lastPurchaseAt);
        }

        LocalDate dueDate;
        FollowUpTimingSource source;
        if (customerPolicy.snoozedUntil() != null && !customerPolicy.snoozedUntil().isBefore(today)) {
            dueDate = customerPolicy.snoozedUntil();
            source = FollowUpTimingSource.SNOOZE;
        } else if (customerPolicy.explicitNextDate() != null) {
            dueDate = customerPolicy.explicitNextDate();
            source = FollowUpTimingSource.EXPLICIT_DATE;
        } else {
            LocalDate lastPurchaseDate = lastPurchaseAt == null ? null
                    : lastPurchaseAt.atZone(tenantPolicy.zoneId()).toLocalDate();
            LocalDate lastActionDate = null;
            FollowUpTimingSource actionSource = null;

            LocalDate manualDate = customerPolicy.lastManualFollowUpDate();
            LocalDate dismissedDate = customerPolicy.lastDismissedDate();

            if (manualDate != null && (dismissedDate == null || !manualDate.isBefore(dismissedDate))) {
                lastActionDate = manualDate;
                actionSource = FollowUpTimingSource.LAST_MANUAL_FOLLOW_UP;
            } else if (dismissedDate != null) {
                lastActionDate = dismissedDate;
                actionSource = FollowUpTimingSource.LAST_DISMISSAL;
            }

            LocalDate anchorDate;
            if (lastActionDate != null && (lastPurchaseDate == null || !lastActionDate.isBefore(lastPurchaseDate))) {
                anchorDate = lastActionDate;
                source = actionSource;
            } else if (lastPurchaseDate != null) {
                anchorDate = lastPurchaseDate;
                source = FollowUpTimingSource.LAST_PURCHASE;
            } else {
                return result(customer, FollowUpStatus.INELIGIBLE, java.util.List.of(FollowUpReason.NO_PURCHASE_HISTORY),
                        now, today, tenantPolicy, null, FollowUpTimingSource.NONE, cadence, null);
            }
            dueDate = anchorDate.plusDays(cadence);
        }

        if (today.isBefore(dueDate)) {
            FollowUpReason reason = source == FollowUpTimingSource.SNOOZE ? FollowUpReason.SNOOZED
                    : source == FollowUpTimingSource.EXPLICIT_DATE ? FollowUpReason.EXPLICIT_DATE_NOT_DUE
                    : FollowUpReason.CADENCE_NOT_DUE;
            return result(customer, FollowUpStatus.NOT_YET_DUE, java.util.List.of(reason), now, today,
                    tenantPolicy, dueDate, source, cadence, lastPurchaseAt);
        }
        FollowUpStatus status = today.equals(dueDate) ? FollowUpStatus.DUE : FollowUpStatus.OVERDUE;
        FollowUpReason reason = status == FollowUpStatus.DUE ? FollowUpReason.DUE_TODAY : FollowUpReason.OVERDUE;
        return result(customer, status, java.util.List.of(reason), now, today, tenantPolicy, dueDate, source,
                cadence, lastPurchaseAt);
    }

    private FollowUpEvaluation result(Customer customer, FollowUpStatus status, java.util.List<FollowUpReason> reasons,
            Instant now, LocalDate today, TenantFollowUpPolicy tenantPolicy, LocalDate dueDate,
            FollowUpTimingSource source, int cadence, Instant lastPurchaseAt) {
        return new FollowUpEvaluation(customer.id(), status, reasons, now, today, tenantPolicy.zoneId(), dueDate,
                source, cadence, lastPurchaseAt);
    }
}
