package io.github.stevdrey.dokene.followup.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.followup.application.FollowUpService;
import io.github.stevdrey.dokene.followup.domain.CustomerFollowUpPolicy;
import io.github.stevdrey.dokene.followup.domain.TenantFollowUpPolicy;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class FollowUpControllerTest {

    private FollowUpService service;
    private MockMvc mvc;
    private final UUID customerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = mock();
        mvc = MockMvcBuilders.standaloneSetup(new FollowUpController(service))
                .setControllerAdvice(new FollowUpExceptionHandler()).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"01\"", "\"0001\"", "\"00\"", "\"bad\"", "\"\"", "1", "\"1.0\"", "\"-1\""})
    void configureTenantRejectsNoncanonicalAndMalformedEtags(String invalidEtag) throws Exception {
        mvc.perform(put("/api/follow-up-policy")
                .header("If-Match", invalidEtag)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"cadenceDays\":14,\"timeZone\":\"UTC\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void configureTenantAcceptsCanonicalEtags() throws Exception {
        TenantId tenantId = TenantId.random();
        when(service.configureTenant(14, ZoneId.of("UTC"), 0))
                .thenReturn(new TenantFollowUpPolicy(tenantId, 14, ZoneId.of("UTC"), 1));

        mvc.perform(put("/api/follow-up-policy")
                .header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"cadenceDays\":14,\"timeZone\":\"UTC\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""));

        verify(service).configureTenant(14, ZoneId.of("UTC"), 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"01\"", "\"00\"", "\"007\""})
    void configureCustomerRejectsNoncanonicalEtags(String invalidEtag) throws Exception {
        mvc.perform(put("/api/customers/{id}/follow-up-policy", customerId)
                .header("If-Match", invalidEtag)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"cadenceDays\":7}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void configureCustomerAcceptsCanonicalEtag() throws Exception {
        TenantId tenantId = TenantId.random();
        CustomerId cId = new CustomerId(customerId);
        when(service.configureCustomer(eq(cId), eq(7), eq(null), eq(1L)))
                .thenReturn(new CustomerFollowUpPolicy(tenantId, cId, 7, null, null, null, 2));

        mvc.perform(put("/api/customers/{id}/follow-up-policy", customerId)
                .header("If-Match", "\"1\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"cadenceDays\":7}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"01\"", "\"00\""})
    void snoozeRejectsNoncanonicalEtags(String invalidEtag) throws Exception {
        mvc.perform(put("/api/customers/{id}/follow-up-snooze", customerId)
                .header("If-Match", invalidEtag)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"until\":\"2026-09-12\"}"))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"01\"", "\"00\""})
    void recordManualFollowUpRejectsNoncanonicalEtags(String invalidEtag) throws Exception {
        mvc.perform(post("/api/customers/{id}/manual-follow-ups", customerId)
                .header("If-Match", invalidEtag)
                .header("Idempotency-Key", "valid-key-1"))
                .andExpect(status().isBadRequest());
    }
}
