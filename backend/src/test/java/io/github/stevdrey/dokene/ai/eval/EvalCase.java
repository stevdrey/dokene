package io.github.stevdrey.dokene.ai.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.stevdrey.dokene.ai.domain.SemanticAction;
import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import java.util.List;

/**
 * One synthetic evaluation scenario. Everything here is invented: names, notes and purchases never derive from
 * real customers. {@code script} is only used by the deterministic scripted provider; {@code expect} describes the
 * platform behavior that the deterministic baseline pins (live runs are judged on hard invariants only).
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record EvalCase(
        String id,
        Family family,
        String description,
        String displayName,
        String locale,
        Setup setup,
        Request request,
        Script script,
        Expect expect,
        String rubricHints) {

    /** Scenario families required by Issue #98. */
    public enum Family {
        REPEAT_PURCHASE,
        DORMANT_CUSTOMER,
        RECENT_PURCHASE,
        EXPLICIT_NEXT_FOLLOW_UP,
        CONSENT_REVOKED,
        DO_NOT_CONTACT,
        ARCHIVED_CUSTOMER,
        MISSING_FACTS,
        ADVERSARIAL_NOTES,
        SPANISH_WORDING,
        UNSUPPORTED_ACTION
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Setup(
            String notes,
            boolean archived,
            String consent,
            boolean doNotContact,
            Integer customerCadenceDays,
            Integer explicitNextFollowUpDaysFromToday,
            List<PurchaseSpec> purchases) {
        public Setup {
            purchases = purchases == null ? List.of() : List.copyOf(purchases);
            consent = consent == null ? "GRANTED" : consent;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record PurchaseSpec(int daysAgo, String description) {
    }

    /** Optional explicit draft request, to exercise unsupported action/template attempts. */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Request(SemanticAction draftAction, SemanticTemplateIntent draftIntent) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Script(RecommendationScript recommendation, DraftScript draft) {
    }

    /** {@code kind}: ACTION, NO_RECOMMENDATION or FAILURE (provider failure category name in {@code failure}). */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record RecommendationScript(
            String kind,
            SemanticAction action,
            SemanticTemplateIntent templateIntent,
            String reason,
            String rationale,
            double confidence,
            String failure,
            java.util.Map<String, String> variables,
            String rejection) {
    }

    /** {@code kind}: DRAFT, NO_DRAFT or FAILURE. */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record DraftScript(
            String kind,
            SemanticAction action,
            SemanticTemplateIntent templateIntent,
            String body,
            String locale,
            List<String> evidence,
            List<String> warnings,
            String reason,
            String rationale,
            double confidence,
            String failure,
            String rejection) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Expect(
            boolean contactForbidden,
            boolean providerInvoked,
            String recommendationStatus,
            String recommendationRejection,
            String draftStatus,
            String draftRejection) {
    }
}
