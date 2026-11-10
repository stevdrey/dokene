// ABOUTME: The customer, contact policy, eligibility, body and rendered-content guards shared by submit (T1), approval
// ABOUTME: (T2) and the last-moment send gate (issue 138). Callers hold the customer lock; refusals carry a closed code only.
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.ai.domain.DraftSafetyValidator;
import io.github.stevdrey.dokene.ai.domain.DraftVariables;
import io.github.stevdrey.dokene.ai.domain.MessageDraft;
import io.github.stevdrey.dokene.ai.domain.RecommendationConfidence;
import io.github.stevdrey.dokene.ai.domain.RecommendationValidationException;
import io.github.stevdrey.dokene.ai.domain.SemanticAction;
import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import io.github.stevdrey.dokene.customer.application.ContactPolicyRepository;
import io.github.stevdrey.dokene.customer.domain.ConsentStatus;
import io.github.stevdrey.dokene.customer.domain.ContactChannel;
import io.github.stevdrey.dokene.customer.domain.ContactPolicy;
import io.github.stevdrey.dokene.customer.domain.Customer;
import io.github.stevdrey.dokene.customer.domain.CustomerStatus;
import io.github.stevdrey.dokene.followup.application.DraftGroundingAssembler;
import io.github.stevdrey.dokene.followup.application.FollowUpService;
import io.github.stevdrey.dokene.followup.domain.FollowUpStatus;
import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.messaging.domain.MessagingRefusedException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class MessageSendGuards {
    // Fixed, context free rationale: the validator checks it with the body and it must never fabricate facts.
    private static final String OPERATOR_RATIONALE = "Mensaje enviado por el operador.";

    private final ContactPolicyRepository contactPolicies;
    private final FollowUpService followUps;
    private final DraftGroundingAssembler grounding;

    public MessageSendGuards(ContactPolicyRepository contactPolicies, FollowUpService followUps,
            DraftGroundingAssembler grounding) {
        this.contactPolicies = Objects.requireNonNull(contactPolicies);
        this.followUps = Objects.requireNonNull(followUps);
        this.grounding = Objects.requireNonNull(grounding);
    }

    /**
     * T1 guard steps 3 to 8 (customer, contact policy, eligibility, policy version when given, body). T2 and the
     * send gate skip the policy version check. The template step is the binding's: submit checks it with
     * {@link ApprovalBindingAssembler#requireRenderable}, approval and send while binding.
     */
    public void check(Customer customer, UUID contactId, SemanticAction action, SemanticTemplateIntent intent,
            String body, String locale, Optional<Long> policyVersionIfMatch) {
        if (customer.status() != CustomerStatus.ACTIVE) {
            throw refuse(MessagingErrorCode.CUSTOMER_ARCHIVED);
        }
        ContactPolicy policy = contactPolicies.find(customer);
        if (policy.doNotContact()) {
            throw refuse(MessagingErrorCode.DO_NOT_CONTACT);
        }
        boolean granted = policy.consents().stream().anyMatch(consent -> consent.contactId().equals(contactId)
                && consent.channel() == ContactChannel.WHATSAPP && consent.status() == ConsentStatus.GRANTED);
        if (!granted) {
            throw refuse(MessagingErrorCode.NO_CONTACT_CONSENT);
        }
        var snapshot = followUps.evaluateSnapshot(customer.id());
        FollowUpStatus status = snapshot.evaluation().status();
        if (status != FollowUpStatus.DUE && status != FollowUpStatus.OVERDUE) {
            throw refuse(MessagingErrorCode.FOLLOW_UP_INELIGIBLE);
        }
        if (policyVersionIfMatch.isPresent() && policyVersionIfMatch.get() != snapshot.policyVersion()) {
            throw refuse(MessagingErrorCode.STALE_VERSION);
        }
        var context = grounding.assemble(customer.id())
                .orElseThrow(() -> refuse(MessagingErrorCode.FOLLOW_UP_INELIGIBLE));
        MessageDraft draft;
        try {
            draft = new MessageDraft(action, intent, AmbiguousSurnames.neutralized(body, customer.displayName()),
                    DraftVariables.empty(), locale, List.of(), List.of(),
                    OPERATOR_RATIONALE, RecommendationConfidence.of(1.0));
        } catch (RecommendationValidationException exception) {
            throw refuse(MessagingErrorCode.BODY_REJECTED);
        }
        if (DraftSafetyValidator.validate(draft, context.allowedContextText(), context.grounding()).isPresent()) {
            throw refuse(MessagingErrorCode.BODY_REJECTED);
        }
    }

    /**
     * The body guard applied to what the provider would receive (issue 326), on every path that renders a template:
     * each parameter value, which may come from tenant records rather than the reviewed body, and the fixed body with
     * those values filled in. Refuses with {@code BODY_REJECTED} and never quotes a value. The customer's own name is
     * read through {@link AmbiguousSurnames}, as in the body guard.
     */
    public void checkRendered(Customer customer, String locale, List<String> parameterValues, String renderedBody) {
        Map<String, String> texts = new LinkedHashMap<>();
        for (int index = 0; index < parameterValues.size(); index++) {
            texts.put("parameter " + (index + 1),
                    AmbiguousSurnames.neutralized(parameterValues.get(index), customer.displayName()));
        }
        texts.put("rendered body", AmbiguousSurnames.neutralized(renderedBody, customer.displayName()));
        if (DraftSafetyValidator.validateRenderedText(texts, locale, grounding.renderedContentGrounding(customer))
                .isPresent()) {
            throw refuse(MessagingErrorCode.BODY_REJECTED);
        }
    }

    private static MessagingRefusedException refuse(MessagingErrorCode code) {
        return new MessagingRefusedException(code);
    }
}
