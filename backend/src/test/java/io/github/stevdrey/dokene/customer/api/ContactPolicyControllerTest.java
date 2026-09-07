package io.github.stevdrey.dokene.customer.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.stevdrey.dokene.customer.application.ContactPolicyPage;
import io.github.stevdrey.dokene.customer.application.ContactPolicyService;
import io.github.stevdrey.dokene.customer.application.CustomerConflictException;
import io.github.stevdrey.dokene.customer.domain.ContactChannel;
import io.github.stevdrey.dokene.customer.domain.ContactConsent;
import io.github.stevdrey.dokene.customer.domain.ContactEligibility;
import io.github.stevdrey.dokene.customer.domain.ContactEligibilityReason;
import io.github.stevdrey.dokene.customer.domain.ContactIntentSource;
import io.github.stevdrey.dokene.customer.domain.ContactPolicy;
import io.github.stevdrey.dokene.customer.domain.ConsentStatus;
import io.github.stevdrey.dokene.customer.domain.CustomerId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ContactPolicyControllerTest {
    private ContactPolicyService service;
    private MockMvc mvc;
    private UUID customerId;
    private UUID contactId;
    private ContactPolicy policy;

    @BeforeEach
    void setUp() {
        service = mock();
        mvc = MockMvcBuilders.standaloneSetup(new ContactPolicyController(service))
                .setControllerAdvice(new CustomerExceptionHandler()).build();
        customerId = UUID.randomUUID();
        contactId = UUID.randomUUID();
        policy = new ContactPolicy(new CustomerId(customerId), 1, false, null, null,
                List.of(new ContactConsent(contactId, ContactChannel.WHATSAPP, ConsentStatus.GRANTED,
                        ContactIntentSource.CUSTOMER_VERBAL, Instant.parse("2026-09-06T12:00:00Z"))));
    }

    @Test
    void readsPolicyWithEtagAndExplicitConsent() throws Exception {
        when(service.get(new CustomerId(customerId))).thenReturn(policy);
        mvc.perform(get("/api/customers/{id}/contact-policy", customerId))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.consents[0].contactId").value(contactId.toString()))
                .andExpect(jsonPath("$.consents[0].status").value("GRANTED"));
    }

    @Test
    void changesConsentAndRequiresGrantOrRevocation() throws Exception {
        when(service.changeConsent(eq(new CustomerId(customerId)), eq(contactId), eq(ContactChannel.WHATSAPP),
                eq(ConsentStatus.GRANTED), eq(ContactIntentSource.CUSTOMER_WRITTEN), eq(0L))).thenReturn(policy);
        mvc.perform(put("/api/customers/{customerId}/contacts/{contactId}/consents/WHATSAPP", customerId, contactId)
                .header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"GRANTED\",\"source\":\"CUSTOMER_WRITTEN\"}"))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""));

        mvc.perform(put("/api/customers/{customerId}/contacts/{contactId}/consents/WHATSAPP", customerId, contactId)
                .header("If-Match", "1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"UNKNOWN\",\"source\":\"CUSTOMER_WRITTEN\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void changesDoNotContactAndMapsStaleVersion() throws Exception {
        when(service.changeDoNotContact(any(), eq(true), eq(ContactIntentSource.CUSTOMER_VERBAL), eq(0L)))
                .thenThrow(new CustomerConflictException());
        mvc.perform(put("/api/customers/{id}/do-not-contact", customerId).header("If-Match", "0")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true,\"source\":\"CUSTOMER_VERBAL\"}"))
                .andExpect(status().isConflict());
        mvc.perform(put("/api/customers/{id}/do-not-contact", customerId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true,\"source\":\"CUSTOMER_VERBAL\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnsEligibilityAndValidatesHistoryCursorAndLimit() throws Exception {
        when(service.evaluate(new CustomerId(customerId), contactId, ContactChannel.WHATSAPP))
                .thenReturn(new ContactEligibility(false, List.of(ContactEligibilityReason.CONSENT_UNKNOWN)));
        when(service.history(new CustomerId(customerId), null, 50)).thenReturn(new ContactPolicyPage(List.of(), null));
        mvc.perform(get("/api/customers/{id}/contact-eligibility", customerId)
                .param("channel", "WHATSAPP").param("contactId", contactId.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.eligible").value(false))
                .andExpect(jsonPath("$.reasons[0]").value("CONSENT_UNKNOWN"));
        mvc.perform(get("/api/customers/{id}/contact-policy/history", customerId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.events").isEmpty());
        mvc.perform(get("/api/customers/{id}/contact-policy/history", customerId).param("cursor", "bad"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/customers/{id}/contact-policy/history", customerId).param("limit", "101"))
                .andExpect(status().isBadRequest());
    }
}
