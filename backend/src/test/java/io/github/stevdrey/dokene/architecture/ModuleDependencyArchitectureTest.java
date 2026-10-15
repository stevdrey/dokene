// ABOUTME: Makes the ADR 0023 §1 dependency direction a build failure (AC-11 of spec 0001).
// ABOUTME: A new module adds its rule here; messaging.domain stays free of Spring, JDBC and other modules.
package io.github.stevdrey.dokene.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.properties.HasOwner;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import io.github.stevdrey.dokene.followup.archfixture.UpstreamDependsOnMessagingFixture;
import io.github.stevdrey.dokene.followup.archfixture.UpstreamDependsOnSecretFailureFixture;
import io.github.stevdrey.dokene.followup.archfixture.UpstreamDependsOnSecretPortFixture;
import io.github.stevdrey.dokene.followup.archfixture.UpstreamDependsOnSecretSourceFixture;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver;
import io.github.stevdrey.dokene.integration.application.SecretUnavailableException;
import io.github.stevdrey.dokene.integration.application.VerifiedWebhookEvent;
import io.github.stevdrey.dokene.integration.application.VerifiedWebhookEventHandler;
import io.github.stevdrey.dokene.integration.archfixture.FabricatedWebhookEventFixture;
import io.github.stevdrey.dokene.integration.archfixture.RawReportBypassFixture;
import io.github.stevdrey.dokene.integration.secrets.SecretSourceType;
import io.github.stevdrey.dokene.messaging.application.DeliveryStatusSink;
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

    private static final ArchRule TEMPLATE_NEVER_DEPENDS_ON_MESSAGING_OR_INTEGRATION = noClasses().that()
            .resideInAPackage(ROOT + ".template..")
            .should().dependOnClassesThat().resideInAnyPackage(ROOT + ".messaging..", ROOT + ".integration..")
            .because("ADR 0023 §1 and ADR 0024 §3: messaging consumes the template port, never the reverse");

    private static final ArchRule ONLY_VERIFIED_HANDLERS_REACH_THE_DELIVERY_SINK = noClasses()
            .that().resideOutsideOfPackage(ROOT + ".messaging..")
            .and().areNotAssignableTo(VerifiedWebhookEventHandler.class)
            .should().dependOnClassesThat().areAssignableTo(DeliveryStatusSink.class)
            .because("ADR 0024 §3: provider reports reach the sink only as a VerifiedWebhookEvent");

    private static final ArchRule ONLY_THE_WEBHOOK_VERIFIER_BUILDS_VERIFIED_EVENTS = noClasses()
            .that().resideOutsideOfPackage(ROOT + ".integration..webhook..")
            .should().callConstructorWhere(JavaCall.Predicates.target(HasOwner.Predicates.With.owner(
                    JavaClass.Predicates.equivalentTo(VerifiedWebhookEvent.class))))
            .because("ADR 0024 §3: only the webhook verifier, after signature, tenant lookup and dedup, may build one");

    private static final ArchRule SECRET_MATERIAL_NEVER_LEAVES_INTEGRATION = noClasses().that()
            .resideOutsideOfPackage(ROOT + ".integration..")
            .should().dependOnClassesThat(JavaClass.Predicates.belongToAnyOf(IntegrationSecretResolver.class)
                    .or(JavaClass.Predicates.belongToAnyOf(SecretUnavailableException.class))
                    .or(JavaClass.Predicates.resideInAPackage(ROOT + ".integration.secrets..")))
            .because("ADR 0024 §3: the secret resolver port, its failure shape and the secret sources are reachable "
                    + "only from integration");

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
    void templateNeverDependsOnMessagingOrIntegration() {
        TEMPLATE_NEVER_DEPENDS_ON_MESSAGING_OR_INTEGRATION.check(production);
    }

    @Test
    void onlyVerifiedWebhookHandlersReachTheDeliverySink() {
        ONLY_VERIFIED_HANDLERS_REACH_THE_DELIVERY_SINK.check(production);
    }

    /** Negative proof: an integration class handing a raw report to the sink is reported. */
    @Test
    void aRawReportBypassFailsTheSinkRule() {
        JavaClasses fixture = new ClassFileImporter()
                .importClasses(RawReportBypassFixture.class, DeliveryStatusSink.class);

        assertThat(ONLY_VERIFIED_HANDLERS_REACH_THE_DELIVERY_SINK.evaluate(fixture).hasViolation()).isTrue();
    }

    @Test
    void onlyTheWebhookVerifierBuildsVerifiedEvents() {
        ONLY_THE_WEBHOOK_VERIFIER_BUILDS_VERIFIED_EVENTS.check(production);
    }

    /** Negative proof: a class outside the verifier fabricating a verified event is reported. */
    @Test
    void aFabricatedVerifiedEventFailsTheConstructionRule() {
        JavaClasses fixture = new ClassFileImporter()
                .importClasses(FabricatedWebhookEventFixture.class, VerifiedWebhookEvent.class);

        assertThat(ONLY_THE_WEBHOOK_VERIFIER_BUILDS_VERIFIED_EVENTS.evaluate(fixture).hasViolation()).isTrue();
    }

    @Test
    void messagingDomainIsPlainJavaPlusAiDomain() {
        MESSAGING_DOMAIN_IS_PURE.check(production);
    }

    @Test
    void secretMaterialNeverLeavesIntegration() {
        SECRET_MATERIAL_NEVER_LEAVES_INTEGRATION.check(production);
    }

    /** Negative proof (spec 0006 AC-7): each of the rule's three predicates is exercised by its own fixture. */
    @Test
    void aClassOutsideIntegrationReachingSecretMaterialFailsTheSecretRule() {
        JavaClasses port = new ClassFileImporter()
                .importClasses(UpstreamDependsOnSecretPortFixture.class, IntegrationSecretResolver.class);
        JavaClasses failure = new ClassFileImporter()
                .importClasses(UpstreamDependsOnSecretFailureFixture.class, SecretUnavailableException.class);
        JavaClasses source = new ClassFileImporter()
                .importClasses(UpstreamDependsOnSecretSourceFixture.class, SecretSourceType.class);

        assertThat(SECRET_MATERIAL_NEVER_LEAVES_INTEGRATION.evaluate(port).hasViolation()).isTrue();
        assertThat(SECRET_MATERIAL_NEVER_LEAVES_INTEGRATION.evaluate(failure).hasViolation()).isTrue();
        assertThat(SECRET_MATERIAL_NEVER_LEAVES_INTEGRATION.evaluate(source).hasViolation()).isTrue();
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
