// ABOUTME: Pins the purchase If-Match boundary to the shared strong numeric ETag grammar (ADR 0023 §4.1):
// ABOUTME: lenient legacy forms are a 400 that never reaches the service.
package io.github.stevdrey.dokene.purchase.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.purchase.application.PurchaseService;
import io.github.stevdrey.dokene.purchase.domain.PurchaseId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PurchaseControllerTest {
    private PurchaseService service;
    private MockMvc mvc;
    private UUID customerId;
    private UUID purchaseId;

    @BeforeEach
    void setUp() {
        service = mock();
        mvc = MockMvcBuilders.standaloneSetup(new PurchaseController(service))
                .setControllerAdvice(new PurchaseExceptionHandler()).build();
        customerId = UUID.randomUUID();
        purchaseId = UUID.randomUUID();
    }

    @Test
    void voidsWithOneStrongNumericEtag() throws Exception {
        mvc.perform(delete("/api/customers/{customerId}/purchases/{purchaseId}", customerId, purchaseId)
                .header("If-Match", "\"3\""))
                .andExpect(status().isNoContent());
        verify(service).voidPurchase(new CustomerId(customerId), new PurchaseId(purchaseId), 3L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"3", "\"03\"", "\"+3\"", "W/\"3\"", " \"3\" ", "\"3\", \"4\"", "*", "\"-1\""})
    void refusesEveryOtherIfMatchWithoutCallingTheService(String ifMatch) throws Exception {
        mvc.perform(delete("/api/customers/{customerId}/purchases/{purchaseId}", customerId, purchaseId)
                .header("If-Match", ifMatch))
                .andExpect(status().isBadRequest());
        verify(service, never()).voidPurchase(any(), any(), anyLong());
    }
}
