// ABOUTME: Deterministic follow up timing: hard constraints, then snooze, explicit date or cadence from the anchor.
// ABOUTME: The anchor (latest action or purchase date in the tenant zone) is reported in every branch (spec 0003).
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
import java.util.List;
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
        Anchor anchor = anchor(customerPolicy, tenantPolicy, lastPurchaseAt);

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
                    null, FollowUpTimingSource.NONE, cadence, lastPurchaseAt, anchor.date());
        }

        LocalDate dueDate;
        FollowUpTimingSource source;
        if (customerPolicy.snoozedUntil() != null && !customerPolicy.snoozedUntil().isBefore(today)) {
            dueDate = customerPolicy.snoozedUntil();
            source = FollowUpTimingSource.SNOOZE;
        } else if (customerPolicy.explicitNextDate() != null) {
            dueDate = customerPolicy.explicitNextDate();
            source = FollowUpTimingSource.EXPLICIT_DATE;
        } else if (anchor.date() == null) {
            return result(customer, FollowUpStatus.INELIGIBLE, List.of(FollowUpReason.NO_PURCHASE_HISTORY),
                    now, today, tenantPolicy, null, FollowUpTimingSource.NONE, cadence, null, null);
        } else {
            dueDate = anchor.date().plusDays(cadence);
            source = anchor.source();
        }

        if (source == FollowUpTimingSource.SNOOZE) {
            return result(customer, FollowUpStatus.NOT_YET_DUE, List.of(FollowUpReason.SNOOZED), now, today,
                    tenantPolicy, dueDate, source, cadence, lastPurchaseAt, anchor.date());
        }
        if (today.isBefore(dueDate)) {
            FollowUpReason reason = source == FollowUpTimingSource.EXPLICIT_DATE
                    ? FollowUpReason.EXPLICIT_DATE_NOT_DUE
                    : FollowUpReason.CADENCE_NOT_DUE;
            return result(customer, FollowUpStatus.NOT_YET_DUE, List.of(reason), now, today,
                    tenantPolicy, dueDate, source, cadence, lastPurchaseAt, anchor.date());
        }
        FollowUpStatus status = today.equals(dueDate) ? FollowUpStatus.DUE : FollowUpStatus.OVERDUE;
        FollowUpReason reason = status == FollowUpStatus.DUE ? FollowUpReason.DUE_TODAY : FollowUpReason.OVERDUE;
        return result(customer, status, List.of(reason), now, today, tenantPolicy, dueDate, source,
                cadence, lastPurchaseAt, anchor.date());
    }

    private record Anchor(LocalDate date, FollowUpTimingSource source) {
    }

    /**
     * The latest of the three action dates and the last valid purchase date in the tenant zone; on a tie an action
     * date wins over the purchase, and among actions the earlier source in manual, dismissal, outbound order wins.
     */
    private static Anchor anchor(CustomerFollowUpPolicy customerPolicy, TenantFollowUpPolicy tenantPolicy,
            Instant lastPurchaseAt) {
        LocalDate lastPurchaseDate = lastPurchaseAt == null ? null
                : lastPurchaseAt.atZone(tenantPolicy.zoneId()).toLocalDate();
        LocalDate lastActionDate = null;
        FollowUpTimingSource actionSource = null;
        LocalDate[] actionDates = {customerPolicy.lastManualFollowUpDate(), customerPolicy.lastDismissedDate(),
                customerPolicy.lastOutboundMessageDate()};
        FollowUpTimingSource[] actionSources = {FollowUpTimingSource.LAST_MANUAL_FOLLOW_UP,
                FollowUpTimingSource.LAST_DISMISSAL, FollowUpTimingSource.LAST_OUTBOUND_MESSAGE};
        for (int i = 0; i < actionDates.length; i++) {
            if (actionDates[i] != null && (lastActionDate == null || actionDates[i].isAfter(lastActionDate))) {
                lastActionDate = actionDates[i];
                actionSource = actionSources[i];
            }
        }
        if (lastActionDate != null && (lastPurchaseDate == null || !lastActionDate.isBefore(lastPurchaseDate))) {
            return new Anchor(lastActionDate, actionSource);
        }
        if (lastPurchaseDate != null) {
            return new Anchor(lastPurchaseDate, FollowUpTimingSource.LAST_PURCHASE);
        }
        return new Anchor(null, FollowUpTimingSource.NONE);
    }

    private FollowUpEvaluation result(Customer customer, FollowUpStatus status, List<FollowUpReason> reasons,
            Instant now, LocalDate today, TenantFollowUpPolicy tenantPolicy, LocalDate dueDate,
            FollowUpTimingSource source, int cadence, Instant lastPurchaseAt, LocalDate anchorDate) {
        return new FollowUpEvaluation(customer.id(), status, reasons, now, today, tenantPolicy.zoneId(), dueDate,
                source, cadence, lastPurchaseAt, anchorDate);
    }
}
