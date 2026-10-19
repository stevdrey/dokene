// ABOUTME: Proves both outbound kill switches through the real safety control and the dispatch service (spec 0008 AC-7):
// ABOUTME: P3-KILL-01 to 04, 08 and 09. Every refusal leaves the message APPROVED with no attempt row and no provider call.
package io.github.stevdrey.dokene.tenant.security;

import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.context;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedMembership;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.stevdrey.dokene.audit.application.AuditExecutionContext;
import io.github.stevdrey.dokene.audit.security.AuditRequestFilter;
import io.github.stevdrey.dokene.customer.application.ContactPolicyService;
import io.github.stevdrey.dokene.customer.application.CustomerService;
import io.github.stevdrey.dokene.customer.application.CustomerService.PhoneInput;
import io.github.stevdrey.dokene.customer.domain.ConsentStatus;
import io.github.stevdrey.dokene.customer.domain.ContactChannel;
import io.github.stevdrey.dokene.customer.domain.ContactIntentSource;
import io.github.stevdrey.dokene.customer.domain.Customer;
import io.github.stevdrey.dokene.followup.application.FollowUpService;
import io.github.stevdrey.dokene.integration.application.WhatsAppIntegrationRepository;
import io.github.stevdrey.dokene.integration.domain.WhatsAppIntegration;
import io.github.stevdrey.dokene.messaging.application.ApprovalCommand;
import io.github.stevdrey.dokene.messaging.application.OutboundDispatchService;
import io.github.stevdrey.dokene.messaging.application.OutboundMessageCommandService;
import io.github.stevdrey.dokene.messaging.application.OutboundMessageRepository;
import io.github.stevdrey.dokene.messaging.application.OutboundSwitchProperties;
import io.github.stevdrey.dokene.messaging.application.SendMessageCommand;
import io.github.stevdrey.dokene.messaging.application.SubmitMessageCommand;
import io.github.stevdrey.dokene.messaging.domain.MessageStatus;
import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.messaging.domain.MessagingRefusedException;
import io.github.stevdrey.dokene.messaging.domain.OutboundMessage;
import io.github.stevdrey.dokene.purchase.application.PurchaseService;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import io.github.stevdrey.dokene.tenant.security.MappedGeneralFollowUpGateConfiguration.StubTemplateResolver;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Unlike the other dispatch tests this one does not import {@link MappedGeneralFollowUpGateConfiguration}, so the real
 * {@code WhatsAppOutboundSafetyControl} answers the policy gate; only the template resolver and the provider are
 * stubbed. The global switch is fixed per Spring context, so each value gets its own nested class.
 */
class OutboundKillSwitchIntegrationTest {
    private static final String BODY = "Hola, queríamos saber cómo le ha ido con su compra. Quedamos atentos.";
    private static final String WABA_ID = "200000000000001";
    private static final AtomicLong NEXT_PHONE_ID = new AtomicLong(
            300_000_000_000_000L + (System.nanoTime() % 1_000_000_000L) * 100);

    @TestConfiguration(proxyBeanMethods = false)
    static class RealControlConfiguration {
        @Bean
        @Primary
        StubTemplateResolver stubTemplateResolver() {
            return new StubTemplateResolver();
        }
    }

    abstract static class Cases {
        @DynamicPropertySource
        static void configure(DynamicPropertyRegistry registry) throws Exception {
            TenantSecurityIntegrationFixture.configure(registry);
            registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
        }

        @Autowired OutboundDispatchService dispatch;
        @Autowired OutboundMessageCommandService commands;
        @Autowired OutboundMessageRepository messages;
        @Autowired ScriptedMessagingProvider provider;
        @Autowired StubTemplateResolver templates;
        @Autowired OutboundSwitchProperties globalSwitch;
        @Autowired WhatsAppIntegrationRepository integrations;
        @Autowired FollowUpService followUps;
        @Autowired ContactPolicyService contacts;
        @Autowired PurchaseService purchases;
        @Autowired CustomerService customers;
        @Autowired TenantRepository tenants;
        @Autowired TenantMembershipRepository memberships;
        @Autowired TenantContextProvider contexts;
        @Autowired AuditExecutionContext auditExecution;
        @Autowired WebApplicationContext webApplicationContext;
        @Autowired AuditRequestFilter auditRequestFilter;

        MockMvc mvc;
        Side a;
        Side b;

        /** One tenant with an owner who sends, an admin who flips the switch, a customer and an integration row. */
        record Side(Tenant tenant, TenantContext owner, IdentityId admin, Customer customer, UUID contactId,
                WhatsAppIntegration integration) {
        }

        @BeforeEach
        void setUp() throws Exception {
            mvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                    .addFilters(auditRequestFilter).apply(springSecurity()).build();
            templates.mapped = true;
            provider.reset();
            a = side("Kill switch A ");
            b = side("Kill switch B ");
        }

        private Side side(String name) throws Exception {
            Instant now = Instant.now();
            Tenant tenant = seedTenant(tenants, contexts, name + UUID.randomUUID(), now);
            TenantContext owner = context(seedMembership(memberships, contexts, tenant.id(),
                    new IdentityId(UUID.randomUUID()), TenantRole.OWNER, now));
            IdentityId admin = new IdentityId(UUID.randomUUID());
            seedMembership(memberships, contexts, tenant.id(), admin, TenantRole.ADMIN, now);
            inContext(owner, () -> followUps.configureTenant(30, ZoneId.of("UTC"), 0));
            Customer customer = eligibleCustomer(owner, name + "customer");
            // Enabled and proven before approval, so the approval binds the real sender; the switch stays off.
            WhatsAppIntegration created = WhatsAppIntegration.create(tenant.id(),
                    Long.toString(NEXT_PHONE_ID.incrementAndGet()), WABA_ID, null, true, "wa-token-ok", now);
            WhatsAppIntegration proven = contexts.callWithTenantId(tenant.id(), () -> {
                integrations.insert(created);
                WhatsAppIntegration verified = created.verifyOwnership(now);
                return verified.withVersion(integrations.update(verified, 0).orElseThrow());
            });
            return new Side(tenant, owner, admin, customer, customer.phones().getFirst().id(), proven);
        }

        void assertRefusedWithoutEffects(Side side, OutboundMessage approved, MessagingErrorCode code)
                throws Exception {
            int callsBefore = provider.calls().size();
            OutboundMessage before = current(side, approved);
            assertThatThrownBy(() -> inContext(side.owner(), () -> dispatch.send(send(before,
                    "deny-" + UUID.randomUUID()))))
                    .isInstanceOf(MessagingRefusedException.class)
                    .extracting(exception -> ((MessagingRefusedException) exception).code()).isEqualTo(code);
            OutboundMessage after = current(side, approved);
            assertThat(after.status()).as(code.name()).isEqualTo(MessageStatus.APPROVED);
            assertThat(after.version()).as(code.name()).isEqualTo(before.version());
            assertThat(inContext(side.owner(), () -> messages.findAttempts(after.tenantId(), after.id())))
                    .as(code.name()).isEmpty();
            assertThat(provider.calls()).as(code.name()).hasSize(callsBefore);
        }

        OutboundMessage sendAndExpectSent(Side side, OutboundMessage approved) throws Exception {
            int callsBefore = provider.calls().size();
            OutboundMessage sent = inContext(side.owner(), () -> dispatch.send(send(approved,
                    "send-" + UUID.randomUUID()))).message();
            assertThat(sent.status()).isEqualTo(MessageStatus.SENT);
            assertThat(provider.calls()).hasSize(callsBefore + 1);
            return sent;
        }

        /** The tenant switch through its route, as an admin would flip it; answers the new ETag version. */
        long flip(Side side, long version, boolean enabled) throws Exception {
            ResultActions result = mvc.perform(put("/api/integrations/whatsapp/outbound").with(csrf())
                    .with(authentication(new UsernamePasswordAuthenticationToken(new TestPrincipal(side.admin()),
                            "test", List.of())))
                    .header("X-Tenant-Id", side.tenant().id().value()).header("If-Match", "\"" + version + "\"")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":" + enabled + "}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.outboundEnabled").value(enabled));
            String etag = result.andReturn().getResponse().getHeader("ETag");
            result.andExpect(header().exists("ETag"));
            return Long.parseLong(etag.substring(1, etag.length() - 1));
        }

        OutboundMessage approvedMessage(Side side) throws Exception {
            long policyVersion = inContext(side.owner(), () -> followUps.evaluateSnapshot(side.customer().id()))
                    .policyVersion();
            OutboundMessage pending = inContext(side.owner(), () -> commands.submit(new SubmitMessageCommand(
                    side.customer().id().value(), side.contactId(), "GENERAL_CHECK_IN", "GENERAL_FOLLOW_UP", BODY,
                    "es-419", "MANUAL", Long.toString(policyVersion), "submit-" + UUID.randomUUID()))).message();
            OutboundMessage approved = inContext(side.owner(), () -> commands.approve(new ApprovalCommand(
                    pending.id(), null, Long.toString(pending.version()), "approve-" + UUID.randomUUID()))).message();
            assertThat(approved.status()).isEqualTo(MessageStatus.APPROVED);
            return approved;
        }

        private Customer eligibleCustomer(TenantContext owner, String name) throws Exception {
            Instant now = Instant.now();
            Customer created = inContext(owner, () -> customers.create(name, null, List.of(new PhoneInput(
                    "8888" + String.format("%04d", Math.abs(UUID.randomUUID().hashCode()) % 10000), "CR", true))));
            UUID phone = created.phones().getFirst().id();
            inContext(owner, () -> contacts.changeConsent(created.id(), phone, ContactChannel.WHATSAPP,
                    ConsentStatus.GRANTED, ContactIntentSource.CUSTOMER_WRITTEN, contacts.get(created.id()).version()));
            inContext(owner, () -> purchases.record(created.id(), now.minusSeconds(60), "Anchor purchase",
                    "anchor-" + UUID.randomUUID()));
            inContext(owner, () -> followUps.configureCustomer(created.id(), null, LocalDate.now(ZoneId.of("UTC")), 0));
            return created;
        }

        OutboundMessage current(Side side, OutboundMessage message) throws Exception {
            return inContext(side.owner(), () -> messages.findById(message.tenantId(), message.id())).orElseThrow();
        }

        static SendMessageCommand send(OutboundMessage message, String key) {
            return new SendMessageCommand(message.id(), Long.toString(message.version()), key);
        }

        <T> T inContext(TenantContext context, Callable<T> operation) throws Exception {
            return auditExecution.callWithCorrelation(UUID.randomUUID(),
                    () -> contexts.callWithContext(context, operation::call));
        }

        private record TestPrincipal(IdentityId identityId) implements AuthenticatedTenantIdentity { }
    }

    @Nested
    @SpringBootTest(properties = {"dokene.ai.provider=fake", "dokene.messaging.outbound.enabled=false"})
    @Import({RealControlConfiguration.class, ScriptedMessagingProvider.Configuration.class})
    class GlobalSwitchOff extends Cases {
        /** P3-KILL-01 and P3-KILL-03: off by default, and nothing a tenant can do turns it on. */
        @Test
        void refusesEveryTenantBeforeReadingAnythingElseEvenWithTheTenantSwitchOn() throws Exception {
            assertThat(globalSwitch.enabled()).isFalse();
            OutboundMessage approvedA = approvedMessage(a);
            OutboundMessage approvedB = approvedMessage(b);

            assertRefusedWithoutEffects(a, approvedA, MessagingErrorCode.OUTBOUND_DISABLED_GLOBALLY);
            flip(a, a.integration().version(), true);
            assertRefusedWithoutEffects(a, approvedA, MessagingErrorCode.OUTBOUND_DISABLED_GLOBALLY);
            assertRefusedWithoutEffects(b, approvedB, MessagingErrorCode.OUTBOUND_DISABLED_GLOBALLY);
            assertThat(provider.calls()).isEmpty();
        }
    }

    @Nested
    @SpringBootTest(properties = {"dokene.ai.provider=fake", "dokene.messaging.outbound.enabled=true"})
    @Import({RealControlConfiguration.class, ScriptedMessagingProvider.Configuration.class})
    class GlobalSwitchOn extends Cases {
        /** P3-KILL-02: the tenant switch is per tenant, and the pass case really reaches the provider. */
        @Test
        void aTenantWithTheSwitchOffIsRefusedWhileAnotherWithItOnSends() throws Exception {
            assertThat(globalSwitch.enabled()).isTrue();
            OutboundMessage approvedA = approvedMessage(a);
            OutboundMessage approvedB = approvedMessage(b);
            flip(b, b.integration().version(), true);

            assertRefusedWithoutEffects(a, approvedA, MessagingErrorCode.OUTBOUND_DISABLED_FOR_TENANT);
            OutboundMessage sent = sendAndExpectSent(b, approvedB);
            assertThat(sent.providerMessageId()).startsWith("wamid.SYNTHETIC-");
            assertThat(provider.calls()).singleElement()
                    .satisfies(call -> assertThat(call.recipientPhone()).isEqualTo(approvedB.recipientPhone()));
            assertThat(current(a, approvedA).status()).isEqualTo(MessageStatus.APPROVED);
        }

        /** P3-KILL-08: both switches on is not enough; the integration must be enabled with a current proof. */
        @Test
        void aDisabledOrUnprovenIntegrationIsRefusedAfterBothSwitches() throws Exception {
            OutboundMessage approvedA = approvedMessage(a);
            OutboundMessage approvedB = approvedMessage(b);
            long versionA = flip(a, a.integration().version(), true);
            long versionB = flip(b, b.integration().version(), true);

            // Disabling keeps the credential revision, so the approval still matches and the gate is what refuses.
            contexts.runWithTenantId(a.tenant().id(), () -> {
                WhatsAppIntegration row = integrations.findByTenant(a.tenant().id()).orElseThrow();
                integrations.update(row.update(row.phoneNumberId(), row.businessAccountId(), null, false,
                        row.secretRef(), Instant.now()), versionA).orElseThrow();
            });
            assertRefusedWithoutEffects(a, approvedA, MessagingErrorCode.INTEGRATION_DISABLED);

            // Re-enabled but unproven: still not a sender.
            contexts.runWithTenantId(a.tenant().id(), () -> {
                WhatsAppIntegration row = integrations.findByTenant(a.tenant().id()).orElseThrow();
                integrations.update(row.update(row.phoneNumberId(), row.businessAccountId(), null, true,
                        row.secretRef(), Instant.now()), versionA + 1).orElseThrow();
            });
            assertRefusedWithoutEffects(a, approvedA, MessagingErrorCode.INTEGRATION_DISABLED);

            // Tenant B is untouched by any of it.
            assertThat(versionB).isEqualTo(b.integration().version() + 1);
            sendAndExpectSent(b, approvedB);
        }

        /** P3-KILL-04 and P3-KILL-09: a flip is read on the very next send, and a flip back reruns the whole policy. */
        @Test
        void flippingOffStopsTheNextSendAtOnceAndFlippingBackOnRunsTheWholePolicyAgain() throws Exception {
            OutboundMessage approved = approvedMessage(a);
            long version = flip(a, a.integration().version(), true);
            version = flip(a, version, false);
            assertRefusedWithoutEffects(a, approved, MessagingErrorCode.OUTBOUND_DISABLED_FOR_TENANT);

            // While the switch was off the customer asked not to be contacted; switching on does not skip that step.
            inContext(a.owner(), () -> contacts.changeDoNotContact(a.customer().id(), true,
                    ContactIntentSource.CUSTOMER_VERBAL, contacts.get(a.customer().id()).version()));
            version = flip(a, version, true);
            assertRefusedWithoutEffects(a, approved, MessagingErrorCode.DO_NOT_CONTACT);

            inContext(a.owner(), () -> contacts.changeDoNotContact(a.customer().id(), false,
                    ContactIntentSource.CUSTOMER_VERBAL, contacts.get(a.customer().id()).version()));
            OutboundMessage sent = sendAndExpectSent(a, approved);
            assertThat(sent.attemptCount()).isEqualTo(1);
            // A send is final: flipping off afterwards cannot retract it and the message stays SENT.
            flip(a, version, false);
            assertThat(current(a, sent).status()).isEqualTo(MessageStatus.SENT);
        }
    }
}
