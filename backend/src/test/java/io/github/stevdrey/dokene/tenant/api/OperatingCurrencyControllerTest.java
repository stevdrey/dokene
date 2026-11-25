// ABOUTME: Pins the operating currency setting contract (issue 452): strict body and If-Match, 409 on a stale revision,
// ABOUTME: TENANT_UPDATE before any write, an audit row per real change and none for an unchanged value.
package io.github.stevdrey.dokene.tenant.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.stevdrey.dokene.audit.application.AuditRecorder;
import io.github.stevdrey.dokene.tenant.application.OperatingCurrencyService;
import io.github.stevdrey.dokene.tenant.application.TenantAccessDeniedException;
import io.github.stevdrey.dokene.tenant.application.TenantAuthorizationService;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import io.github.stevdrey.dokene.tenant.domain.TenantStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OperatingCurrencyControllerTest {

    private static final Instant CREATED_AT = Instant.parse("2026-11-01T00:00:00Z");
    private static final TenantId TENANT_ID = new TenantId(UUID.randomUUID());

    private final TenantRepository tenants = mock();
    private final TenantAuthorizationService authorization = mock();
    private final TenantContextProvider contexts = mock();
    private final AuditRecorder audit = mock();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(contexts.requireCurrent()).thenReturn(new TenantContext(TENANT_ID, new IdentityId(UUID.randomUUID()),
                new TenantMembershipId(UUID.randomUUID()), TenantRole.ADMIN));
        Clock clock = Clock.fixed(CREATED_AT.plusSeconds(3600), ZoneOffset.UTC);
        mvc = MockMvcBuilders.standaloneSetup(new OperatingCurrencyController(
                new OperatingCurrencyService(tenants, authorization, contexts, audit, clock))).build();
    }

    private void stored(String currency, long revision) {
        when(tenants.findById(TENANT_ID)).thenReturn(Optional.of(Tenant.restore(TENANT_ID, "Workspace",
                TenantStatus.ACTIVE, currency == null ? null : Currency.getInstance(currency), CREATED_AT,
                CREATED_AT, revision)));
    }

    @Test
    void readsTheUnsetCurrencyWithTheSupportedCodesAndTheRevisionAsETag() throws Exception {
        stored(null, 3);

        mvc.perform(get("/api/operating-currency"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"3\""))
                .andExpect(jsonPath("$.operatingCurrency").value((Object) null))
                .andExpect(jsonPath("$.supportedCurrencies.length()").value(Tenant.SUPPORTED_OPERATING_CURRENCIES.size()))
                .andExpect(jsonPath("$.supportedCurrencies[?(@ == 'CAD')]").exists());
        verify(authorization).requirePermission(TenantPermission.TENANT_READ);
    }

    @Test
    void changesTheCurrencyUnderTheReadRevisionAndAuditsIt() throws Exception {
        stored(null, 3);
        when(tenants.save(any())).thenAnswer(invocation -> {
            Tenant saved = invocation.getArgument(0);
            saved.synchronizeRevision(4);
            return saved;
        });

        mvc.perform(put("/api/operating-currency").header("If-Match", "\"3\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"operatingCurrency\":\"USD\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"4\""))
                .andExpect(jsonPath("$.operatingCurrency").value("USD"));

        ArgumentCaptor<Tenant> saved = ArgumentCaptor.forClass(Tenant.class);
        verify(tenants).save(saved.capture());
        assertThat(saved.getValue().operatingCurrency()).contains(Currency.getInstance("USD"));
        verify(authorization).requirePermission(TenantPermission.TENANT_UPDATE);
        verify(audit).tenantOperatingCurrencyChanged();
    }

    @Test
    void clearsTheCurrencyWithNull() throws Exception {
        stored("USD", 5);
        when(tenants.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        mvc.perform(put("/api/operating-currency").header("If-Match", "\"5\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"operatingCurrency\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operatingCurrency").value((Object) null));
        verify(audit).tenantOperatingCurrencyChanged();
    }

    @Test
    void anUnchangedValueWritesNothingAndRecordsNoAuditRow() throws Exception {
        stored("CRC", 2);

        mvc.perform(put("/api/operating-currency").header("If-Match", "\"2\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"operatingCurrency\":\"CRC\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""));
        verify(tenants, never()).save(any());
        verifyNoInteractions(audit);
    }

    @Test
    void aStaleOrConcurrentlyChangedRevisionIsAConflict() throws Exception {
        stored(null, 3);
        mvc.perform(put("/api/operating-currency").header("If-Match", "\"2\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"operatingCurrency\":\"USD\"}"))
                .andExpect(status().isConflict());
        verify(tenants, never()).save(any());

        when(tenants.save(any())).thenThrow(new OptimisticLockingFailureException("concurrent"));
        mvc.perform(put("/api/operating-currency").header("If-Match", "\"3\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"operatingCurrency\":\"USD\"}"))
                .andExpect(status().isConflict());
        verifyNoInteractions(audit);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"operatingCurrency\":\"AUD\"}", "{\"operatingCurrency\":\"usd\"}",
            "{\"operatingCurrency\":\"\"}", "{\"operatingCurrency\":1}", "{\"operatingCurrency\":true}", "{}",
            "{\"operatingCurrency\":\"USD\",\"extra\":1}", "[]", "\"USD\"", "null", "not json"})
    void refusesAnythingButOneSupportedCodeOrNull(String body) throws Exception {
        stored(null, 3);
        mvc.perform(put("/api/operating-currency").header("If-Match", "\"3\"")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verify(tenants, never()).save(any());
        verifyNoInteractions(audit);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"03\"", "W/\"3\"", "*", "3", "\"3\", \"4\""})
    void refusesAMissingOrNonCanonicalIfMatch(String ifMatch) throws Exception {
        stored(null, 3);
        mvc.perform(put("/api/operating-currency").header("If-Match", ifMatch)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"operatingCurrency\":\"USD\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/operating-currency")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"operatingCurrency\":\"USD\"}"))
                .andExpect(status().isBadRequest());
        verify(tenants, never()).save(any());
    }

    @Test
    void aCallerWithoutTenantUpdateChangesNothing() throws Exception {
        stored(null, 3);
        doThrow(new TenantAccessDeniedException("Forbidden"))
                .when(authorization).requirePermission(TenantPermission.TENANT_UPDATE);

        mvc.perform(put("/api/operating-currency").header("If-Match", "\"3\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"operatingCurrency\":\"USD\"}"))
                .andExpect(status().isForbidden());
        verify(tenants, never()).save(any());
        verifyNoInteractions(audit);
    }
}
