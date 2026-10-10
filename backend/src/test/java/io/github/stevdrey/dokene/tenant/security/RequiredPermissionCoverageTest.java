package io.github.stevdrey.dokene.tenant.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.stevdrey.dokene.tenant.application.RequiredPermission;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * Issue #162: authorization before request binding relies on every mutating endpoint declaring
 * {@link RequiredPermission}. An unannotated handler would silently fall back to "bind and validate first", so this test
 * fails the build when a non-read-only controller method lacks the annotation.
 */
class RequiredPermissionCoverageTest {
    private static final Set<RequestMethod> READ_ONLY = Set.of(
            RequestMethod.GET, RequestMethod.HEAD, RequestMethod.OPTIONS, RequestMethod.TRACE);

    /** Authentication-only endpoints that intentionally have no tenant permission (no tenant context exists yet). */
    private static final Set<String> AUTHENTICATION_ONLY = Set.of(
            "io.github.stevdrey.dokene.tenant.api.TenantController#provisionWorkspace");

    @Test
    void everyMutatingControllerMethodDeclaresARequiredPermission() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<String> missing = new ArrayList<>();
        int mutating = 0;
        for (BeanDefinition definition : scanner.findCandidateComponents("io.github.stevdrey.dokene")) {
            Class<?> controller = ClassUtils.forName(definition.getBeanClassName(), getClass().getClassLoader());
            if (!isProductionClass(controller)) {
                continue;
            }
            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null || !isMutating(mapping)) {
                    continue;
                }
                mutating++;
                String id = controller.getName() + "#" + method.getName();
                if (!method.isAnnotationPresent(RequiredPermission.class) && !AUTHENTICATION_ONLY.contains(id)) {
                    missing.add(id);
                }
            }
        }
        assertThat(mutating).as("scanned mutating controller methods").isGreaterThanOrEqualTo(19);
        assertThat(missing)
                .as("mutating endpoints without @RequiredPermission (add it, or justify an authentication-only exception)")
                .isEmpty();
    }

    /** Test sources define throwaway {@code @RestController}s that are not part of the shipped API. */
    private static boolean isProductionClass(Class<?> type) {
        String location = type.getProtectionDomain().getCodeSource().getLocation().getPath();
        return !location.contains("/test");
    }

    private static boolean isMutating(RequestMapping mapping) {
        // A mapping without explicit methods matches every HTTP method, including mutating ones.
        return mapping.method().length == 0 || java.util.Arrays.stream(mapping.method()).anyMatch(m -> !READ_ONLY.contains(m));
    }
}
