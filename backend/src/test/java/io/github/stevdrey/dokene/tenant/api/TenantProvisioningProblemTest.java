// ABOUTME: Tests the refusal answers of POST /api/tenants over MockMvc (spec 0021): the policy refusal carries the
// ABOUTME: closed problem+json body, and the 400, 409 and bare 403 answers keep their empty bodies.
package io.github.stevdrey.dokene.tenant.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.stevdrey.dokene.audit.application.AuditRecorder;
import io.github.stevdrey.dokene.tenant.application.IdempotencyConflictException;
import io.github.stevdrey.dokene.tenant.application.MembershipInvitationService;
import io.github.stevdrey.dokene.tenant.application.ProvisioningNotPermittedException;
import io.github.stevdrey.dokene.tenant.application.TenantAccessDeniedException;
import io.github.stevdrey.dokene.tenant.application.TenantContextResolver;
import io.github.stevdrey.dokene.tenant.application.TenantMembershipDiscovery;
import io.github.stevdrey.dokene.tenant.application.WorkspaceProvisioningService;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.security.AuthenticatedTenantIdentity;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TenantProvisioningProblemTest {

    private final IdentityId identityId = new IdentityId(UUID.randomUUID());
    private WorkspaceProvisioningService provisioningService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        provisioningService = mock();
        TenantController controller = new TenantController(
                provisioningService, mock(TenantMembershipDiscovery.class), mock(TenantContextResolver.class),
                mock(AuditRecorder.class), mock(MembershipInvitationService.class));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        AuthenticatedTenantIdentity identity = () -> identityId;
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(identity, null));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** covers: AC-1, AC-4, AC-12 */
    @Test
    void thePolicyRefusalAnswersTheClosedProblemBodyEvenWhenTheCallerAcceptsOnlyJson() throws Exception {
        when(provisioningService.provisionWorkspace(any(), any(), any()))
                .thenThrow(new ProvisioningNotPermittedException());

        provision()
                .andExpect(status().isForbidden())
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(content().json("""
                        {"type":"about:blank","title":"Forbidden","status":403,"code":"PROVISIONING_NOT_PERMITTED"}
                        """, JsonCompareMode.STRICT));
    }

    /** covers: AC-12 */
    @Test
    void anotherTenantAccessDenialKeepsItsEmptyBody() throws Exception {
        when(provisioningService.provisionWorkspace(any(), any(), any()))
                .thenThrow(new TenantAccessDeniedException("other denial"));

        provision().andExpect(status().isForbidden()).andExpect(content().string(""));
    }

    /** covers: AC-12 */
    @Test
    void theConflictAndInvalidInputAnswersKeepTheirEmptyBodies() throws Exception {
        when(provisioningService.provisionWorkspace(any(), any(), any()))
                .thenThrow(new IdempotencyConflictException("conflict"));
        provision().andExpect(status().isConflict()).andExpect(content().string(""));

        mvc.perform(post("/api/tenants").accept(MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"Acme\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(""));
    }

    private ResultActions provision() throws Exception {
        return mvc.perform(post("/api/tenants")
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Acme\"}"));
    }
}
