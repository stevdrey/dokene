package io.github.stevdrey.dokene.tenant.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import io.github.stevdrey.dokene.tenant.application.RequiredPermission;
import io.github.stevdrey.dokene.tenant.application.TenantAccessDeniedException;
import io.github.stevdrey.dokene.tenant.application.TenantAuthorizationService;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

class RequiredPermissionInterceptorTest {
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private TenantAuthorizationService authorization;
    private RequiredPermissionInterceptor interceptor;

    @BeforeEach
    void setUp() {
        authorization = mock(TenantAuthorizationService.class);
        interceptor = new RequiredPermissionInterceptor(authorization);
    }

    @Test
    void ignoresHandlersWithoutTheAnnotation() throws Exception {
        assertThat(interceptor.preHandle(request, response, handler("unannotated"))).isTrue();
        verifyNoInteractions(authorization);
    }

    @Test
    void ignoresNonControllerHandlers() {
        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        verifyNoInteractions(authorization);
    }

    @Test
    void requiresTheDeclaredPermission() throws Exception {
        assertThat(interceptor.preHandle(request, response, handler("single"))).isTrue();
        verify(authorization).requirePermission(TenantPermission.CUSTOMER_WRITE);
        verifyNoMoreInteractions(authorization);
    }

    @Test
    void requiresEveryDeclaredPermissionInOrder() throws Exception {
        assertThat(interceptor.preHandle(request, response, handler("multiple"))).isTrue();
        InOrder order = inOrder(authorization);
        order.verify(authorization).requirePermission(TenantPermission.MESSAGE_DRAFT);
        order.verify(authorization).requirePermission(TenantPermission.FOLLOWUP_EVALUATE);
        verifyNoMoreInteractions(authorization);
    }

    @Test
    void firstDenialPropagatesAndSkipsTheRemainingPermissions() throws Exception {
        doThrow(new TenantAccessDeniedException("Forbidden"))
                .when(authorization).requirePermission(TenantPermission.MESSAGE_DRAFT);

        assertThatThrownBy(() -> interceptor.preHandle(request, response, handler("multiple")))
                .isInstanceOf(TenantAccessDeniedException.class);

        verify(authorization).requirePermission(TenantPermission.MESSAGE_DRAFT);
        verifyNoMoreInteractions(authorization);
    }

    private static HandlerMethod handler(String name) throws Exception {
        return new HandlerMethod(new StubController(), StubController.class.getDeclaredMethod(name));
    }

    static class StubController {
        void unannotated() { }

        @RequiredPermission(TenantPermission.CUSTOMER_WRITE)
        void single() { }

        @RequiredPermission({TenantPermission.MESSAGE_DRAFT, TenantPermission.FOLLOWUP_EVALUATE})
        void multiple() { }
    }
}
