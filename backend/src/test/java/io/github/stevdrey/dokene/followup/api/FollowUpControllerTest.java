package io.github.stevdrey.dokene.followup.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.followup.application.FollowUpService;
import io.github.stevdrey.dokene.followup.application.FollowUpQueuePage;
import io.github.stevdrey.dokene.followup.domain.CustomerFollowUpPolicy;
import io.github.stevdrey.dokene.followup.domain.FollowUpDismissal;
import io.github.stevdrey.dokene.followup.domain.FollowUpQueueItem;
import io.github.stevdrey.dokene.followup.domain.FollowUpReason;
import io.github.stevdrey.dokene.followup.domain.FollowUpStatus;
import io.github.stevdrey.dokene.followup.domain.FollowUpTimingSource;
import io.github.stevdrey.dokene.followup.domain.TenantFollowUpPolicy;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
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

    @Test
    void recordManualFollowUpRejectsOversizedNotesBeforeServiceInvocation() throws Exception {
        mvc.perform(post("/api/customers/{id}/manual-follow-ups", customerId)
                .header("If-Match", "\"0\"")
                .header("Idempotency-Key", "valid-key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"notes\":\"" + "x".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void dismissRejectsOversizedNotesBeforeServiceInvocation() throws Exception {
        mvc.perform(post("/api/customers/{id}/follow-up-dismissals", customerId)
                .header("If-Match", "\"0\"")
                .header("Idempotency-Key", "valid-key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"notes\":\"" + "x".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void dueQueueReturnsPaginatedItemsAndFiltersByStatus() throws Exception {
        var queueItem = new FollowUpQueueItem(new CustomerId(customerId), "Jane Doe", "+50688881234",
                FollowUpStatus.OVERDUE, List.of(FollowUpReason.OVERDUE), LocalDate.of(2026, 9, 1),
                FollowUpTimingSource.LAST_PURCHASE, 2L, 30, Instant.parse("2026-08-01T00:00:00Z"),
                null, null, Instant.parse("2026-09-10T12:00:00Z"));
        when(service.dueQueue(any()))
                .thenReturn(new FollowUpQueuePage(List.of(queueItem), "cursor-token-1"));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/follow-up-queue")
                .param("status", "OVERDUE")
                .param("limit", "25"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].customerId").value(customerId.toString()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].status").value("OVERDUE"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].timingSource").value("LAST_PURCHASE"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.nextCursor").value("cursor-token-1"));
    }

    @Test
    void dismissRecordsDismissalWithETagAndNotes() throws Exception {
        TenantId tenantId = TenantId.random();
        CustomerId cId = new CustomerId(customerId);
        var dismissal = new FollowUpDismissal(UUID.randomUUID(), tenantId, cId, LocalDate.of(2026, 9, 10),
                3L, Instant.now(), new IdentityId(UUID.randomUUID()), TenantMembershipId.random(), "customer requested");
        when(service.dismiss(eq(cId), eq(2L), eq("dismiss-key-1"), eq("customer requested")))
                .thenReturn(new io.github.stevdrey.dokene.followup.application.FollowUpDismissalResult(dismissal, true));

        mvc.perform(post("/api/customers/{id}/follow-up-dismissals", customerId)
                .header("If-Match", "\"2\"")
                .header("Idempotency-Key", "dismiss-key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"notes\":\"customer requested\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"3\""))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.notes").value("customer requested"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.policyVersion").value(3));
    }

    @Test
    void dismissReplayReturnsOk() throws Exception {
        TenantId tenantId = TenantId.random();
        CustomerId cId = new CustomerId(customerId);
        var dismissal = new FollowUpDismissal(UUID.randomUUID(), tenantId, cId, LocalDate.of(2026, 9, 10),
                3L, Instant.now(), new IdentityId(UUID.randomUUID()), TenantMembershipId.random(), null);
        when(service.dismiss(eq(cId), eq(2L), eq("dismiss-key-1"), eq(null)))
                .thenReturn(new io.github.stevdrey.dokene.followup.application.FollowUpDismissalResult(dismissal, false));

        mvc.perform(post("/api/customers/{id}/follow-up-dismissals", customerId)
                .header("If-Match", "\"2\"")
                .header("Idempotency-Key", "dismiss-key-1"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"3\""));
    }
}
