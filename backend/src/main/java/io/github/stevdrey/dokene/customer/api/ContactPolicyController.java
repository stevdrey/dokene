package io.github.stevdrey.dokene.customer.api;

import io.github.stevdrey.dokene.customer.application.ContactPolicyCursor;
import io.github.stevdrey.dokene.customer.application.ContactPolicyService;
import io.github.stevdrey.dokene.customer.domain.ContactChannel;
import io.github.stevdrey.dokene.customer.domain.ContactConsent;
import io.github.stevdrey.dokene.customer.domain.ContactEligibilityReason;
import io.github.stevdrey.dokene.customer.domain.ContactIntentSource;
import io.github.stevdrey.dokene.customer.domain.ContactPolicy;
import io.github.stevdrey.dokene.customer.domain.ContactPolicyEvent;
import io.github.stevdrey.dokene.customer.domain.ConsentStatus;
import io.github.stevdrey.dokene.customer.domain.CustomerId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/customers/{customerId}")
public class ContactPolicyController {
    private final ContactPolicyService policies;

    public ContactPolicyController(ContactPolicyService policies) {
        this.policies = policies;
    }

    @GetMapping("/contact-policy")
    public ResponseEntity<ContactPolicyResponse> get(@PathVariable UUID customerId) {
        return response(policies.get(new CustomerId(customerId)));
    }

    @PutMapping("/contacts/{contactId}/consents/{channel}")
    public ResponseEntity<ContactPolicyResponse> changeConsent(@PathVariable UUID customerId,
            @PathVariable UUID contactId, @PathVariable ContactChannel channel,
            @RequestHeader("If-Match") String ifMatch, @RequestBody ConsentRequest request) {
        if (request == null || request.status() == null || request.status() == ConsentStatus.UNKNOWN
                || request.source() == null) {
            throw new IllegalArgumentException("Consent status and source are required");
        }
        return response(policies.changeConsent(new CustomerId(customerId), contactId, channel,
                request.status(), request.source(), parseVersion(ifMatch)));
    }

    @PutMapping("/do-not-contact")
    public ResponseEntity<ContactPolicyResponse> changeDoNotContact(@PathVariable UUID customerId,
            @RequestHeader("If-Match") String ifMatch, @RequestBody DoNotContactRequest request) {
        if (request == null || request.enabled() == null || request.source() == null) {
            throw new IllegalArgumentException("Do-not-contact state and source are required");
        }
        return response(policies.changeDoNotContact(new CustomerId(customerId), request.enabled(),
                request.source(), parseVersion(ifMatch)));
    }

    @GetMapping("/contact-eligibility")
    public EligibilityResponse eligibility(@PathVariable UUID customerId,
            @RequestParam ContactChannel channel, @RequestParam UUID contactId) {
        var result = policies.evaluate(new CustomerId(customerId), contactId, channel);
        return new EligibilityResponse(result.eligible(), result.reasons());
    }

    @GetMapping("/contact-policy/history")
    public HistoryResponse history(@PathVariable UUID customerId,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "50") int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("History limit must be between 1 and 100");
        }
        var page = policies.history(new CustomerId(customerId),
                cursor == null ? null : ContactPolicyCursor.decode(cursor), limit);
        return new HistoryResponse(page.events().stream().map(this::eventResponse).toList(), page.nextCursor());
    }

    private ResponseEntity<ContactPolicyResponse> response(ContactPolicy policy) {
        var body = new ContactPolicyResponse(policy.customerId().value(), policy.version(), policy.doNotContact(),
                policy.doNotContactSource(), policy.doNotContactChangedAt(),
                policy.consents().stream().map(this::consentResponse).toList());
        return ResponseEntity.ok().eTag("\"" + policy.version() + "\"").body(body);
    }

    private ConsentResponse consentResponse(ContactConsent consent) {
        return new ConsentResponse(consent.contactId(), consent.channel(), consent.status(), consent.source(), consent.changedAt());
    }

    private EventResponse eventResponse(ContactPolicyEvent event) {
        return new EventResponse(event.id(), event.type(), event.contactId(), event.channel(), event.consentStatus(),
                event.doNotContact(), event.source(), event.occurredAt(), event.actorId().value(),
                event.membershipId().value(), event.policyVersion());
    }

    private long parseVersion(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("If-Match is required");
        String unquoted = value.strip();
        if (unquoted.length() >= 2 && unquoted.startsWith("\"") && unquoted.endsWith("\"")) {
            unquoted = unquoted.substring(1, unquoted.length() - 1);
        }
        try {
            long version = Long.parseLong(unquoted);
            if (version < 0) throw new IllegalArgumentException("Invalid contact policy version");
            return version;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid contact policy version");
        }
    }

    public record ConsentRequest(ConsentStatus status, ContactIntentSource source) { }
    public record DoNotContactRequest(Boolean enabled, ContactIntentSource source) { }
    public record ConsentResponse(UUID contactId, ContactChannel channel, ConsentStatus status,
                                  ContactIntentSource source, Instant changedAt) { }
    public record ContactPolicyResponse(UUID customerId, long version, boolean doNotContact,
                                        ContactIntentSource doNotContactSource, Instant doNotContactChangedAt,
                                        List<ConsentResponse> consents) { }
    public record EligibilityResponse(boolean eligible, List<ContactEligibilityReason> reasons) { }
    public record EventResponse(UUID id, ContactPolicyEvent.Type type, UUID contactId, ContactChannel channel,
                                ConsentStatus consentStatus, Boolean doNotContact, ContactIntentSource source,
                                Instant occurredAt, UUID actorId, UUID membershipId, long policyVersion) { }
    public record HistoryResponse(List<EventResponse> events, String nextCursor) { }
}
