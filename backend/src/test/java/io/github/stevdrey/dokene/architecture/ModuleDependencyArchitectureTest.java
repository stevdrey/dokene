// ABOUTME: Makes the ADR 0023 §1 dependency direction a build failure (AC-11 of spec 0001).
// ABOUTME: A new module adds its rule here; messaging.domain stays free of Spring, JDBC and other modules.
package io.github.stevdrey.dokene.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.followup.archfixture.UpstreamDependsOnMessagingFixture;
import io.github.stevdrey.dokene.integration.local.archfixture.LocalDependsOnCustomerFixture;
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

    private static final ArchRule WEB_IS_A_LEAF = noClasses().that()
            .resideInAPackage(ROOT + ".web..")
            .should().dependOnClassesThat(
                    com.tngtech.archunit.base.DescribedPredicate.describe(
                            "anything but java.*, jakarta.*, Spring, Jackson and web itself",
                            javaClass -> !isInfrastructure(javaClass.getPackageName())
                                    && !javaClass.getPackageName().startsWith(ROOT + ".web")))
            .because("spec 0002: the shared HTTP helpers depend on no module, so every api package can use them");

    private static final ArchRule MESSAGING_API_STAYS_THIN = noClasses().that()
            .resideInAPackage(ROOT + ".messaging.api..")
            .should().dependOnClassesThat(
                    com.tngtech.archunit.base.DescribedPredicate.describe(
                            "anything but infrastructure, messaging.application, messaging.domain, ai.domain, "
                                    + "tenant.application, tenant.domain and web",
                            javaClass -> !isInfrastructure(javaClass.getPackageName())
                                    && !javaClass.getPackageName().startsWith(ROOT + ".messaging.api")
                                    && !javaClass.getPackageName().startsWith(ROOT + ".messaging.application")
                                    && !javaClass.getPackageName().startsWith(ROOT + ".messaging.domain")
                                    && !javaClass.getPackageName().startsWith(ROOT + ".ai.domain")
                                    && !javaClass.getPackageName().startsWith(ROOT + ".tenant.application")
                                    && !javaClass.getPackageName().startsWith(ROOT + ".tenant.domain")
                                    && !javaClass.getPackageName().startsWith(ROOT + ".web")))
            .because("spec 0002 AC-10: the controller wraps the spec 0001 services and the shared web helpers only");

    private static final ArchRule INTEGRATION_LOCAL_IS_NARROW = noClasses().that()
            .resideInAPackage(ROOT + ".integration.local..")
            .should().dependOnClassesThat(
                    com.tngtech.archunit.base.DescribedPredicate.describe(
                            "anything but java.*, Spring, SLF4J, Micrometer, jakarta.annotation, integration, "
                                    + "messaging.application, messaging.domain, tenant.application and tenant.domain",
                            javaClass -> !isAllowedForLocalAdapter(javaClass.getPackageName())))
            .because("spec 0003 AC-10: the local adapter implements the messaging ports and reads no other module");

    private static boolean isAllowedForLocalAdapter(String packageName) {
        return packageName.startsWith("java.") || packageName.startsWith("org.springframework.")
                || packageName.startsWith("org.slf4j") || packageName.startsWith("io.micrometer")
                || packageName.startsWith("jakarta.annotation")
                || packageName.startsWith(ROOT + ".integration")
                || packageName.startsWith(ROOT + ".messaging.application")
                || packageName.startsWith(ROOT + ".messaging.domain")
                || packageName.startsWith(ROOT + ".tenant.application")
                || packageName.startsWith(ROOT + ".tenant.domain");
    }

    /** Packages that are not modules of this application: the JDK, the servlet API, Spring, Jackson, Micrometer, SLF4J. */
    private static boolean isInfrastructure(String packageName) {
        return packageName.startsWith("java.") || packageName.startsWith("jakarta.")
                || packageName.startsWith("org.springframework.") || packageName.startsWith("com.fasterxml.")
                || packageName.startsWith("tools.jackson.") || packageName.startsWith("io.micrometer.")
                || packageName.startsWith("org.slf4j.");
    }

    @Test
    void webDependsOnNoModule() {
        WEB_IS_A_LEAF.check(production);
    }

    @Test
    void messagingApiDependsOnlyOnItsServicesAndTheSharedWebHelpers() {
        MESSAGING_API_STAYS_THIN.check(production);
    }

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

    @Test
    void localAdapterDependsOnlyOnThePortsAndTheTenantContext() {
        INTEGRATION_LOCAL_IS_NARROW.check(production);
    }

    /** Negative proof (spec 0003 AC-10): an integration.local import of customer or followup is reported. */
    @Test
    void anIntentionalLocalAdapterImportOfCustomerFailsTheRule() {
        JavaClasses fixture = new ClassFileImporter()
                .importClasses(LocalDependsOnCustomerFixture.class, CustomerId.class);

        assertThat(INTEGRATION_LOCAL_IS_NARROW.evaluate(fixture).hasViolation()).isTrue();
        assertThat(INTEGRATION_LOCAL_IS_NARROW.evaluate(production).hasViolation()).isFalse();
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
