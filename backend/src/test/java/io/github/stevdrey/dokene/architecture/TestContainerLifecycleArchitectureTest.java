// ABOUTME: Keeps Testcontainers and HTTP stub classes from leaving a cached Spring context behind a stopped database or
// ABOUTME: stub (issues 447, 449). Such a context logs connection failures naming the host into whatever class runs next.
package io.github.stevdrey.dokene.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.testcontainers.junit.jupiter.Testcontainers;

class TestContainerLifecycleArchitectureTest {

    @Test
    void everyTestcontainersClassClosesItsContextWhenTheClassEnds() {
        List<String> violations = new ArrayList<>();
        for (JavaClass type : new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.ONLY_INCLUDE_TESTS)
                .importPackages("io.github.stevdrey.dokene")) {
            // Nested classes are fixtures (like the one below), never run as test classes of their own.
            if (type.isTopLevelClass() && type.isAnnotatedWith(Testcontainers.class)) {
                violations.addAll(violations(type.reflect()));
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void everySpringBootTestThatOwnsAnHttpStubClosesItsContextWhenTheClassEnds() {
        // A class owns a stub when it or a nested fixture starts its own server; a shared stub that never stops is not one.
        Set<JavaClass> stubOwners = new LinkedHashSet<>();
        for (JavaClass type : new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.ONLY_INCLUDE_TESTS)
                .importPackages("io.github.stevdrey.dokene")) {
            if (type.getDirectDependenciesFromSelf().stream()
                    .anyMatch(dependency -> dependency.getTargetClass().isEquivalentTo(HttpServer.class))) {
                JavaClass topLevel = type;
                while (topLevel.getEnclosingClass().isPresent()) {
                    topLevel = topLevel.getEnclosingClass().get();
                }
                if (topLevel.isAnnotatedWith(SpringBootTest.class)) {
                    stubOwners.add(topLevel);
                }
            }
        }
        assertThat(stubOwners).extracting(JavaClass::getSimpleName)
                .contains("ApprovedSendJourneyIntegrationTest", "EnglishTemplateSendJourneyIntegrationTest");
        assertThat(stubOwners).flatExtracting(type -> violations(type.reflect())).isEmpty();
    }

    @Test
    void theCheckReportsAMissingDirtiesContext() {
        assertThat(violations(LeakingFixture.class))
                .containsExactly(LeakingFixture.class.getName() + " has no @DirtiesContext");
    }

    @Test
    void theCheckReportsAContextDirtiedOnlyBeforeTheClassOrEachTest() {
        assertThat(violations(BeforeClassFixture.class))
                .containsExactly(BeforeClassFixture.class.getName() + " dirties its context BEFORE_CLASS, not after");
        assertThat(violations(BeforeEachTestFixture.class)).containsExactly(
                BeforeEachTestFixture.class.getName() + " dirties its context BEFORE_EACH_TEST_METHOD, not after");
    }

    @Test
    void theCheckAcceptsAContextDirtiedAfterTheClassOrEachTest() {
        assertThat(violations(DefaultModeFixture.class)).isEmpty();
        assertThat(violations(AfterClassFixture.class)).isEmpty();
        assertThat(violations(AfterEachTestFixture.class)).isEmpty();
    }

    /**
     * A static {@code @Container} stops when the class ends, so its context must close after the last test too. JUnit stops the container
     * when it closes the class's extension store, after Spring's after-class step has closed a dirtied context.
     */
    static List<String> violations(Class<?> type) {
        DirtiesContext dirties = type.getAnnotation(DirtiesContext.class);
        if (dirties == null) {
            return List.of(type.getName() + " has no @DirtiesContext");
        }
        // A context dirtied only before the class (or each test) survives the last test and outlives the container.
        return switch (dirties.classMode()) {
            case AFTER_CLASS, AFTER_EACH_TEST_METHOD -> List.of();
            case BEFORE_CLASS, BEFORE_EACH_TEST_METHOD ->
                    List.of(type.getName() + " dirties its context " + dirties.classMode() + ", not after");
        };
    }

    @SpringBootTest
    @Testcontainers
    static class LeakingFixture {
    }

    @Testcontainers
    @DirtiesContext(classMode = ClassMode.BEFORE_CLASS)
    static class BeforeClassFixture {
    }

    @Testcontainers
    @DirtiesContext(classMode = ClassMode.BEFORE_EACH_TEST_METHOD)
    static class BeforeEachTestFixture {
    }

    @Testcontainers
    @DirtiesContext
    static class DefaultModeFixture {
    }

    @Testcontainers
    @DirtiesContext(classMode = ClassMode.AFTER_CLASS)
    static class AfterClassFixture {
    }

    @Testcontainers
    @DirtiesContext(classMode = ClassMode.AFTER_EACH_TEST_METHOD)
    static class AfterEachTestFixture {
    }
}
