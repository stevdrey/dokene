package io.github.stevdrey.dokene.tenant.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.stevdrey.dokene.tenant.application.MembershipNotFoundException;
import io.github.stevdrey.dokene.tenant.application.MembershipService;
import io.github.stevdrey.dokene.tenant.application.TenantAccessDeniedException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MembershipControllerTest {

    private MembershipService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(MembershipService.class);
        MembershipController controller = new MembershipController(service);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void mapsMembershipNotFoundExceptionToNotFound() throws Exception {
        doThrow(new MembershipNotFoundException()).when(service).revokeMembership(any());

        mvc.perform(delete("/api/memberships/{identityId}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void mapsOptimisticLockingFailureExceptionToConflict() throws Exception {
        doThrow(new OptimisticLockingFailureException("stale version"))
                .when(service).changeRole(any(), any());

        mvc.perform(put("/api/memberships/{identityId}/role", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "role": "OPERATOR"
                                }
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void mapsIllegalStateExceptionToConflict() throws Exception {
        doThrow(new IllegalStateException("already exists"))
                .when(service).addMembership(any(), any());

        mvc.perform(post("/api/memberships")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "identityId": "%s",
                                  "role": "OPERATOR"
                                }
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isConflict());
    }

    @Test
    void mapsIllegalArgumentExceptionToBadRequest() throws Exception {
        doThrow(new IllegalArgumentException("invalid"))
                .when(service).addMembership(any(), any());

        mvc.perform(post("/api/memberships")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "identityId": "%s",
                                  "role": "OPERATOR"
                                }
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void mapsTenantAccessDeniedExceptionToForbidden() throws Exception {
        doThrow(new TenantAccessDeniedException())
                .when(service).addMembership(any(), any());

        mvc.perform(post("/api/memberships")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "identityId": "%s",
                                  "role": "OPERATOR"
                                }
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isForbidden());
    }
}
