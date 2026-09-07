package io.github.stevdrey.dokene.customer.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.stevdrey.dokene.customer.application.CustomerConflictException;
import io.github.stevdrey.dokene.customer.application.CustomerPage;
import io.github.stevdrey.dokene.customer.application.CustomerService;
import io.github.stevdrey.dokene.customer.application.PhoneNormalizer;
import io.github.stevdrey.dokene.customer.domain.Customer;
import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.customer.domain.CustomerPhone;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CustomerControllerTest {
    private CustomerService service;
    private MockMvc mvc;
    private Customer customer;

    @BeforeEach
    void setUp() {
        service = mock();
        var controller = new CustomerController(service, new PhoneNormalizer());
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new CustomerExceptionHandler()).build();
        Instant now = Instant.parse("2026-09-06T12:00:00Z");
        customer = Customer.create(new CustomerId(UUID.randomUUID()), TenantId.random(), "Ana", "notes",
                List.of(new CustomerPhone(UUID.randomUUID(), "+50688887777", true)), now);
    }

    @Test
    void createReturnsDocumentedResponseWithoutRawPhone() throws Exception {
        when(service.create(eq("Ana"), eq("notes"), any())).thenReturn(customer);

        mvc.perform(post("/api/customers").contentType(MediaType.APPLICATION_JSON).content("""
                {"displayName":"Ana","notes":"notes","phones":[{"number":"8888 7777","region":"CR","primary":true}]}
                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.id").value(customer.id().value().toString()))
                .andExpect(jsonPath("$.phones[0].e164").value("+50688887777"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("8888 7777"))));
    }

    @Test
    void updateRequiresVersionAndMapsConflictToEmpty409() throws Exception {
        mvc.perform(put("/api/customers/{id}", customer.id().value()).contentType(MediaType.APPLICATION_JSON).content("""
                {"displayName":"Ana","phones":[{"number":"8888 7777","region":"CR","primary":true}]}
                """))
                .andExpect(status().isBadRequest()).andExpect(content().string(""));

        when(service.update(eq(customer.id()), eq(0L), eq("Ana"), eq(null), any()))
                .thenThrow(new CustomerConflictException());
        mvc.perform(put("/api/customers/{id}", customer.id().value()).contentType(MediaType.APPLICATION_JSON).content("""
                {"displayName":"Ana","version":0,"phones":[{"number":"8888 7777","region":"CR","primary":true}]}
                """))
                .andExpect(status().isConflict()).andExpect(content().string(""));
    }

    @Test
    void updateMapsArchivedCustomerIllegalStateExceptionToConflict() throws Exception {
        when(service.update(eq(customer.id()), eq(0L), eq("Ana"), eq(null), any()))
                .thenThrow(new IllegalStateException("Archived customers cannot be updated"));
        mvc.perform(put("/api/customers/{id}", customer.id().value()).contentType(MediaType.APPLICATION_JSON).content("""
                {"displayName":"Ana","version":0,"phones":[{"number":"8888 7777","region":"CR","primary":true}]}
                """))
                .andExpect(status().isConflict()).andExpect(content().string(""));
    }

    @Test
    void updateAcceptsIfMatchHeaderInsteadOfBodyVersion() throws Exception {
        when(service.update(eq(customer.id()), eq(0L), eq("Ana"), eq("notes"), any())).thenReturn(customer);

        mvc.perform(put("/api/customers/{id}", customer.id().value())
                .header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON).content("""
                {"displayName":"Ana","notes":"notes","phones":[{"number":"8888 7777","region":"CR","primary":true}]}
                """))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.id").value(customer.id().value().toString()))
                .andExpect(jsonPath("$.displayName").value("Ana"));
    }

    @Test
    void archiveRequiresValidIfMatchAndDelegatesVersion() throws Exception {
        mvc.perform(delete("/api/customers/{id}", customer.id().value()))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/customers/{id}", customer.id().value()).header("If-Match", "\"0\""))
                .andExpect(status().isNoContent());
        verify(service).archive(customer.id(), 0);
    }

    @Test
    void searchNormalizesExactPhoneAndRejectsUnpairedFilterAndBounds() throws Exception {
        when(service.search(any())).thenReturn(new CustomerPage(List.of(customer), "next"));
        mvc.perform(get("/api/customers").param("phone", "8888 7777").param("region", "CR").param("limit", "10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nextCursor").value("next"));
        mvc.perform(get("/api/customers").param("phone", "8888 7777"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/customers").param("limit", "101"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/customers").param("status", "INVALID_STATUS"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/customers").param("cursor", "%%%invalid-base64%%%"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/customers").param("cursor", ""))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/customers").param("cursor", "   "))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getReturnsCustomerResponseWhenFound() throws Exception {
        when(service.get(eq(customer.id()))).thenReturn(customer);

        mvc.perform(get("/api/customers/{id}", customer.id().value()))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.id").value(customer.id().value().toString()))
                .andExpect(jsonPath("$.displayName").value("Ana"))
                .andExpect(jsonPath("$.phones[0].e164").value("+50688887777"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void getReturnsNotFoundWhenCustomerMissing() throws Exception {
        when(service.get(any())).thenThrow(new io.github.stevdrey.dokene.customer.application.CustomerNotFoundException());

        mvc.perform(get("/api/customers/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));
    }

    @Test
    void updateReturnsUpdatedCustomerResponse() throws Exception {
        when(service.update(eq(customer.id()), eq(0L), eq("Ana"), eq("notes"), any())).thenReturn(customer);

        mvc.perform(put("/api/customers/{id}", customer.id().value()).contentType(MediaType.APPLICATION_JSON).content("""
                {"displayName":"Ana","notes":"notes","version":0,"phones":[{"number":"8888 7777","region":"CR","primary":true}]}
                """))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.id").value(customer.id().value().toString()))
                .andExpect(jsonPath("$.displayName").value("Ana"));
    }

    @Test
    void updateReturnsNotFoundWhenCustomerMissing() throws Exception {
        when(service.update(any(), org.mockito.ArgumentMatchers.anyLong(), any(), any(), any()))
                .thenThrow(new io.github.stevdrey.dokene.customer.application.CustomerNotFoundException());

        mvc.perform(put("/api/customers/{id}", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content("""
                {"displayName":"Ana","version":0,"phones":[{"number":"8888 7777","region":"CR","primary":true}]}
                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void archiveReturnsNotFoundWhenCustomerMissing() throws Exception {
        org.mockito.Mockito.doThrow(new io.github.stevdrey.dokene.customer.application.CustomerNotFoundException())
                .when(service).archive(any(), org.mockito.ArgumentMatchers.anyLong());

        mvc.perform(delete("/api/customers/{id}", UUID.randomUUID()).header("If-Match", "\"0\""))
                .andExpect(status().isNotFound());
    }

    @Test
    void archiveRejectsMalformedIfMatchHeaders() throws Exception {
        mvc.perform(delete("/api/customers/{id}", customer.id().value()).header("If-Match", "not-a-number"))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/customers/{id}", customer.id().value()).header("If-Match", "-5"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void mapsTenantAccessDeniedExceptionToForbidden() throws Exception {
        when(service.get(any())).thenThrow(new io.github.stevdrey.dokene.tenant.application.TenantAccessDeniedException());

        mvc.perform(get("/api/customers/{id}", customer.id().value()))
                .andExpect(status().isForbidden())
                .andExpect(content().string(""));
    }
}
