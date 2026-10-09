// ABOUTME: Makes the ADR 0023 §1 dependency direction a build failure (AC-11 of spec 0001).
// ABOUTME: A new module adds its rule here; messaging.domain stays free of Spring, JDBC and other modules.
package io.github.stevdrey.dokene.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import io.github.stevdrey.dokene.followup.archfixture.UpstreamDependsOnMessagingFixture;
import io.github.stevdrey.dokene.messaging.domain.MessageStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ModuleDependencyArchitectureTest {
    private static final String ROOT = "io.github.stevdrey.dokene";
    private static JavaClasses production;

    @BeforeAll
    static void importProductionClasses() {
        production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);
    }

    private static final ArchRule UPSTREAM_NEVER_DEPENDS_ON_MESSAGING = noClasses().that().resideInAnyPackage(
                    ROOT + ".followup..", ROOT + ".customer..", ROOT + ".purchase..", ROOT + ".ai..",
                    ROOT + ".tenant..", ROOT + ".audit.domain..", ROOT + ".audit.persistence..")
            .should().dependOnClassesThat().resideInAPackage(ROOT + ".messaging..")
            .because("ADR 0023 §1: followup, customer, purchase, ai, tenant and the audit core never depend on messaging");

    private static final ArchRule MESSAGING_NEVER_DEPENDS_ON_INTEGRATION = noClasses().that()
            .resideInAPackage(ROOT + ".messaging..")
            .should().dependOnClassesThat().resideInAPackage(ROOT + ".integration..")
            .because("ADR 0023 §1: integration implements messaging ports, never the reverse");

    private static final ArchRule MESSAGING_DOMAIN_IS_PURE = noClasses().that()
            .resideInAPackage(ROOT + ".messaging.domain..")
            .should().dependOnClassesThat(
                    com.tngtech.archunit.base.DescribedPredicate.describe(
                            "anything but java.*, messaging.domain and ai.domain",
                            javaClass -> !javaClass.getPackageName().startsWith("java.")
                                    && !javaClass.getPackageName().startsWith(ROOT + ".messaging.domain")
                                    && !javaClass.getPackageName().startsWith(ROOT + ".ai.domain")))
            .because("spec 0001: the aggregate is pure; Spring, JDBC and other modules stay in application and persistence");

    @Test
    void nothingUpstreamOfMessagingDependsOnIt() {
        UPSTREAM_NEVER_DEPENDS_ON_MESSAGING.check(production);
    }

    @Test
    void messagingNeverDependsOnIntegration() {
        MESSAGING_NEVER_DEPENDS_ON_INTEGRATION.check(production);
    }

    @Test
    void messagingDomainIsPlainJavaPlusAiDomain() {
        MESSAGING_DOMAIN_IS_PURE.check(production);
    }

    /** Negative proof (spec 0001 AC-11): the package patterns really match, so a violating fixture is reported. */
    @Test
    void anIntentionalFollowUpToMessagingImportFailsTheRule() {
        JavaClasses fixture = new ClassFileImporter()
                .importClasses(UpstreamDependsOnMessagingFixture.class, MessageStatus.class);

        assertThat(UPSTREAM_NEVER_DEPENDS_ON_MESSAGING.evaluate(fixture).hasViolation()).isTrue();
        assertThat(UPSTREAM_NEVER_DEPENDS_ON_MESSAGING.evaluate(production).hasViolation()).isFalse();
    }
}
