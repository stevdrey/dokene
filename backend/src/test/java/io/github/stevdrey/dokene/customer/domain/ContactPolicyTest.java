package io.github.stevdrey.dokene.customer.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContactPolicyTest {
    @Test
    void unknownConsentHasNoManufacturedEvidence() {
        UUID contactId = UUID.randomUUID();
        ContactConsent unknown = ContactConsent.unknown(contactId, ContactChannel.WHATSAPP);
        assertThat(unknown.status()).isEqualTo(ConsentStatus.UNKNOWN);
        assertThat(unknown.source()).isNull();
        assertThat(unknown.changedAt()).isNull();
        assertThatThrownBy(() -> new ContactConsent(contactId, ContactChannel.WHATSAPP,
                ConsentStatus.UNKNOWN, ContactIntentSource.CUSTOMER_VERBAL, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void eligibilityRequiresNoReasons() {
        assertThat(new ContactEligibility(true, List.of()).eligible()).isTrue();
        assertThat(new ContactEligibility(false, List.of(ContactEligibilityReason.DO_NOT_CONTACT)).eligible()).isFalse();
        assertThatThrownBy(() -> new ContactEligibility(true, List.of(ContactEligibilityReason.CONSENT_REVOKED)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ContactEligibility(false, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
