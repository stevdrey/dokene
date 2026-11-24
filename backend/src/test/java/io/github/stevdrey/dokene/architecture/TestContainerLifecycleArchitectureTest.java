// ABOUTME: Keeps Testcontainers classes from leaving a cached Spring context behind a stopped database (issue 447).
// ABOUTME: Such a context's pool logs connection failures naming the host into whatever class runs next.
package io.github.stevdrey.dokene.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
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
    void theCheckReportsAMissingDirtiesContext() {
        assertThat(violations(LeakingFixture.class))
                .containsExactly(LeakingFixture.class.getName() + " has no @DirtiesContext");
    }

    /**
     * A static {@code @Container} stops when the class ends, so its context must close too. JUnit stops the container
     * when it closes the class's extension store, after Spring's after-class step has closed a dirtied context.
     */
    static List<String> violations(Class<?> type) {
        return type.isAnnotationPresent(DirtiesContext.class)
                ? List.of()
                : List.of(type.getName() + " has no @DirtiesContext");
    }

    @SpringBootTest
    @Testcontainers
    static class LeakingFixture {
    }
}
