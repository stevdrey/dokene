package io.github.stevdrey.dokene.ai.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import io.github.stevdrey.dokene.ai.domain.SemanticAction;
import io.github.stevdrey.dokene.ai.domain.TrustedFollowUpReason;

/** Provider-bound allowlisted data. Free-form fields are data, never instructions. */
public record RecommendationContext(TrustedFacts trusted, UntrustedText untrusted) {
    public static final int MAX_TEXT_LENGTH = UntrustedText.MAX_TEXT_LENGTH;
    public static final int MAX_TOTAL_TEXT_LENGTH = 2_500;
    public static final int MAX_PURCHASES = 5;

    public RecommendationContext {
        Objects.requireNonNull(trusted, "Trusted facts are required");
        Objects.requireNonNull(untrusted, "Untrusted text is required");
        if (untrusted.totalTextLength() > MAX_TOTAL_TEXT_LENGTH) {
            throw new RecommendationContextException(RecommendationContextException.Reason.TOO_LARGE);
        }
        if (trusted.purchaseDates().size() != untrusted.purchaseDescriptions().size()) {
            throw new RecommendationContextException(RecommendationContextException.Reason.UNSUPPORTED);
        }
    }

    public record TrustedFacts(LocalDate tenantDate, String followUpStatus, List<TrustedFollowUpReason> followUpReasons,
            int effectiveCadenceDays, LocalDate dueDate, boolean contactEligible,
            List<Instant> purchaseDates, List<SemanticAction> allowedActions) {
        public TrustedFacts {
            Objects.requireNonNull(tenantDate, "Tenant date is required");
            Objects.requireNonNull(followUpStatus, "Follow-up status is required");
            Objects.requireNonNull(dueDate, "Due date is required");
            followUpReasons = List.copyOf(followUpReasons);
            purchaseDates = List.copyOf(purchaseDates);
            allowedActions = List.copyOf(allowedActions);
            if (!contactEligible
                    || (!"DUE".equals(followUpStatus) && !"OVERDUE".equals(followUpStatus))
                    || ("DUE".equals(followUpStatus) && (!dueDate.equals(tenantDate) || !List.of(TrustedFollowUpReason.DUE_TODAY).equals(followUpReasons)))
                    || ("OVERDUE".equals(followUpStatus) && (!dueDate.isBefore(tenantDate) || !List.of(TrustedFollowUpReason.OVERDUE).equals(followUpReasons)))
                    || effectiveCadenceDays <= 0
                    || purchaseDates.size() > MAX_PURCHASES
                    || allowedActions.isEmpty()
                    || allowedActions.size() > SemanticAction.values().length
                    || Set.copyOf(allowedActions).size() != allowedActions.size()) {
                throw new RecommendationContextException(RecommendationContextException.Reason.UNSUPPORTED);
            }
        }
    }

    public record UntrustedText(String displayName, String notes, List<String> purchaseDescriptions) {
        public static final int MAX_TEXT_LENGTH = 500;

        public UntrustedText {
            validate(displayName, true);
            validate(notes, false);
            purchaseDescriptions = List.copyOf(purchaseDescriptions);
            if (purchaseDescriptions.size() > MAX_PURCHASES) {
                throw new RecommendationContextException(RecommendationContextException.Reason.TOO_LARGE);
            }
            purchaseDescriptions.forEach(value -> validate(value, true));
        }

        public int totalTextLength() {
            return displayName.length()
                    + (notes == null ? 0 : notes.length())
                    + purchaseDescriptions.stream().mapToInt(String::length).sum();
        }

        private static void validate(String value, boolean required) {
            if ((required && (value == null || value.isBlank())) || (value != null && value.length() > MAX_TEXT_LENGTH)) {
                throw new RecommendationContextException(RecommendationContextException.Reason.TOO_LARGE);
            }
        }
    }
}
