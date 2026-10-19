// ABOUTME: Unit tests for the PUT /api/integrations/whatsapp/outbound route of WhatsAppIntegrationController.
// ABOUTME: Pins the precondition rules, the one member body, the response headers and the closed error codes (AC-4, AC-5).
package io.github.stevdrey.dokene.integration.api;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.stevdrey.dokene.integration.application.IntegrationRefusedException;
import io.github.stevdrey.dokene.integration.application.WhatsAppIntegrationService;
import io.github.stevdrey.dokene.integration.application.WhatsAppIntegrationService.IntegrationView;
import io.github.stevdrey.dokene.integration.domain.WhatsAppIntegration;
import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WhatsAppIntegrationControllerTest {
    private static final String ROUTE = "/api/integrations/whatsapp/outbound";
    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");

    private WhatsAppIntegrationService service;
    private MockMvc mvc;
    private WhatsAppIntegration stored;

    @BeforeEach
    void setUp() {
        service = mock(WhatsAppIntegrationService.class);
        mvc = MockMvcBuilders.standaloneSetup(new WhatsAppIntegrationController(service))
                .setControllerAdvice(new IntegrationExceptionHandler()).build();
        stored = WhatsAppIntegration.create(TenantId.random(), "100000000000001", "200000000000001",
                "+50688887777", true, "wa-token", T0).withVersion(3);
    }

    /** covers: AC-4, AC-5 */
    @Test
    void aStrongIfMatchAndAOneMemberBodyFlipTheSwitchAndAnswerTheStoredRow() throws Exception {
        WhatsAppIntegration flipped = stored.withOutboundEnabled(true, T0).withVersion(4);
        when(service.setOutboundEnabled(3, true)).thenReturn(view(flipped));

        mvc.perform(outbound("\"3\"", "{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"4\""))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.outboundEnabled").value(true))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.webhookVerified").value(false))
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.secretRef").value("wa-token"));

        verify(service).setOutboundEnabled(3, true);
    }

    /** covers: AC-5 */
    @Test
    void theTargetValueComesFromTheBodyNotTheQueryString() throws Exception {
        when(service.setOutboundEnabled(3, false)).thenReturn(view(stored));

        mvc.perform(outbound("\"3\"", "{\"enabled\":false}").queryParam("enabled", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outboundEnabled").value(false));

        verify(service).setOutboundEnabled(3, false);
    }

    /** covers: AC-5 */
    @Test
    void aMissingIfMatchIsInvalidInputBeforeTheServiceIsAsked() throws Exception {
        mvc.perform(put(ROUTE).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        verify(service, never()).setOutboundEnabled(anyLong(), anyBoolean());
    }

    /** covers: AC-5 */
    @Test
    void ifNoneMatchIsNeverAcceptedOnTheSwitchRoute() throws Exception {
        mvc.perform(put(ROUTE).header("If-None-Match", "*").contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}"))
                .andExpect(status().isBadRequest());
        mvc.perform(outbound("\"3\"", "{\"enabled\":true}").header("If-None-Match", "*"))
                .andExpect(status().isBadRequest());

        verify(service, never()).setOutboundEnabled(anyLong(), anyBoolean());
    }

    /** covers: AC-5 */
    @ParameterizedTest
    @ValueSource(strings = {"\"01\"", "\"00\"", "\"bad\"", "\"\"", "3", "\"3.0\"", "\"-1\"", "\"+1\"", "W/\"3\"",
            "\"3\", \"4\"", "*", "\"99999999999999999999\""})
    void nonCanonicalOrWeakEtagsAreInvalidInput(String ifMatch) throws Exception {
        mvc.perform(outbound(ifMatch, "{\"enabled\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        verify(service, never()).setOutboundEnabled(anyLong(), anyBoolean());
    }

    /** covers: AC-5 */
    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"enabled\":null}", "{\"enabled\":\"true\"}", "{\"enabled\":1}",
            "{\"enabled\":true,\"x\":1}", "[]", "true", "\"enabled\"", "null"})
    void anythingButAOneMemberBooleanBodyIsInvalidInput(String body) throws Exception {
        mvc.perform(outbound("\"3\"", body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        verify(service, never()).setOutboundEnabled(anyLong(), anyBoolean());
    }

    /** covers: AC-5 */
    @Test
    void aStaleVersionIsAConflictWithTheClosedCode() throws Exception {
        when(service.setOutboundEnabled(2, true))
                .thenThrow(new IntegrationRefusedException(MessagingErrorCode.STALE_VERSION));

        mvc.perform(outbound("\"2\"", "{\"enabled\":true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"));
    }

    /** covers: AC-5 */
    @Test
    void aTenantWithoutAnIntegrationIsNotFound() throws Exception {
        when(service.setOutboundEnabled(0, true))
                .thenThrow(new IntegrationRefusedException(MessagingErrorCode.NOT_FOUND));

        mvc.perform(outbound("\"0\"", "{\"enabled\":true}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    private static MockHttpServletRequestBuilder outbound(String ifMatch, String body) {
        return put(ROUTE).header("If-Match", ifMatch).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static IntegrationView view(WhatsAppIntegration integration) {
        return new IntegrationView(integration, integration.outboundEnabled(), integration.ownershipVerified());
    }
}
