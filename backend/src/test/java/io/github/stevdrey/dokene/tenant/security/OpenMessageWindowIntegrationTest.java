// ABOUTME: Spec 0013 (issue 198): only messages the provider has not accepted yet block a new message to the customer.
// ABOUTME: A lost delivery report no longer strands the customer, while exactly once and the frequency guards still hold.
package io.github.stevdrey.dokene.tenant.security;

import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.context;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedMembership;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.audit.application.AuditExecutionContext;
import io.github.stevdrey.dokene.customer.application.ContactPolicyService;
import io.github.stevdrey.dokene.customer.application.CustomerService;
import io.github.stevdrey.dokene.customer.application.CustomerService.PhoneInput;
import io.github.stevdrey.dokene.customer.domain.ConsentStatus;
import io.github.stevdrey.dokene.customer.domain.ContactChannel;
import io.github.stevdrey.dokene.customer.domain.ContactIntentSource;
import io.github.stevdrey.dokene.customer.domain.Customer;
import io.github.stevdrey.dokene.followup.application.FollowUpService;
import io.github.stevdrey.dokene.messaging.application.ApprovalCommand;
import io.github.stevdrey.dokene.messaging.application.CancellationCommand;
import io.github.stevdrey.dokene.messaging.application.DeliveryStatusResult;
import io.github.stevdrey.dokene.messaging.application.DeliveryStatusSink;
import io.github.stevdrey.dokene.messaging.application.OutboundDispatchService;
import io.github.stevdrey.dokene.messaging.application.OutboundMessageCommandService;
import io.github.stevdrey.dokene.messaging.application.OutboundMessageRepository;
import io.github.stevdrey.dokene.messaging.application.SendMessageCommand;
import io.github.stevdrey.dokene.messaging.application.SubmitMessageCommand;
import io.github.stevdrey.dokene.messaging.domain.DeliveryStatus;
import io.github.stevdrey.dokene.messaging.domain.DeliveryStatusReport;
import io.github.stevdrey.dokene.messaging.domain.FailureCategory;
import io.github.stevdrey.dokene.messaging.domain.MessageStatus;
import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.messaging.domain.MessagingRefusedException;
import io.github.stevdrey.dokene.messaging.domain.OutboundMessage;
import io.github.stevdrey.dokene.messaging.domain.ProviderSendResult;
import io.github.stevdrey.dokene.purchase.application.PurchaseService;
import io.github.stevdrey.dokene.tenant.application.ProviderContext;
import io.github.stevdrey.dokene.tenant.application.ProviderContextProvider;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import io.github.stevdrey.dokene.tenant.security.MappedGeneralFollowUpGateConfiguration.StubSafetyControl;
import io.github.stevdrey.dokene.tenant.security.MappedGeneralFollowUpGateConfiguration.StubTemplateResolver;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = "dokene.ai.provider=fake")
@Import({MappedGeneralFollowUpGateConfiguration.class, ScriptedMessagingProvider.Configuration.class})
class OpenMessageWindowIntegrationTest {
    private static final String BODY = "Hola, queríamos saber cómo le ha ido con su compra. Quedamos atentos.";
    private static final Pattern STATUS_LITERAL = Pattern.compile("'([A-Z_]+)'");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws Exception {
        TenantSecurityIntegrationFixture.configure(registry);
    }

    @Autowired OutboundDispatchService dispatch;
    @Autowired OutboundMessageCommandService commands;
    @Autowired OutboundMessageRepository messages;
    @Autowired DeliveryStatusSink sink;
    @Autowired ScriptedMessagingProvider provider;
    @Autowired StubTemplateResolver templates;
    @Autowired StubSafetyControl safety;
    @Autowired FollowUpService followUps;
    @Autowired ContactPolicyService contacts;
    @Autowired PurchaseService purchases;
    @Autowired CustomerService customers;
    @Autowired TenantRepository tenants;
    @Autowired TenantMembershipRepository memberships;
    @Autowired TenantContextProvider contexts;
    @Autowired ProviderContextProvider providerContexts;
    @Autowired AuditExecutionContext auditExecution;
    @Autowired JdbcTemplate jdbc;

    private Tenant tenantA;
    private TenantContext ownerA;
    private Customer customer;
    private UUID contactId;

    @BeforeEach
    void setUp() throws Exception {
        MappedGeneralFollowUpGateConfiguration.reset(templates, safety);
        safety.blockedWith = null;
        provider.reset();
        Instant now = Instant.now();
        tenantA = seedTenant(tenants, contexts, "Open window " + UUID.randomUUID(), now);
        ownerA = context(seedMembership(memberships, contexts, tenantA.id(), new IdentityId(UUID.randomUUID()),
                TenantRole.OWNER, now));
        inContext(ownerA, () -> followUps.configureTenant(30, ZoneId.of("UTC"), 0));
        customer = eligibleCustomer();
        contactId = customer.phones().getFirst().id();
    }

    /** covers: AC-1, AC-3 (the issue 198 regression: a sent message with no final report no longer strands the customer). */
    @Test
    void aSentMessageWithoutAFinalReportNoLongerBlocksTheNextMessage() throws Exception {
        OutboundMessage first = sent(approvedMessage());
        dueAgainPastTheInterval(first);

        OutboundMessage second = sent(approvedMessage());

        assertThat(second.status()).isEqualTo(MessageStatus.SENT);
        assertThat(current(first).status()).isEqualTo(MessageStatus.SENT);
        assertThat(current(first).version()).isEqualTo(first.version());
        List<Object> anchorBefore = cadenceAnchor();

        DeliveryStatusResult late = asProvider(() -> sink.apply(new DeliveryStatusReport(first.providerMessageId(),
                DeliveryStatus.DELIVERED, Instant.now(), Optional.empty())));

        assertThat(late).isEqualTo(new DeliveryStatusResult.Applied(MessageStatus.SENT, MessageStatus.DELIVERED));
        assertThat(current(first).status()).isEqualTo(MessageStatus.DELIVERED);
        assertThat(current(second).status()).isEqualTo(MessageStatus.SENT);
        assertThat(cadenceAnchor()).isEqualTo(anchorBefore);
    }

    /** covers: AC-3 (a late failure for the earlier message touches neither the newer one nor the cadence). */
    @Test
    void aLateFailureForTheEarlierMessageLeavesTheNewerOneAndTheCadenceAlone() throws Exception {
        OutboundMessage first = sent(approvedMessage());
        dueAgainPastTheInterval(first);
        OutboundMessage second = sent(approvedMessage());
        List<Object> anchorBefore = cadenceAnchor();

        DeliveryStatusResult late = asProvider(() -> sink.apply(new DeliveryStatusReport(first.providerMessageId(),
                DeliveryStatus.FAILED, Instant.now(), Optional.of(FailureCategory.UNKNOWN))));

        assertThat(late).isEqualTo(new DeliveryStatusResult.Applied(MessageStatus.SENT, MessageStatus.FAILED));
        assertThat(current(first).status()).isEqualTo(MessageStatus.FAILED);
        assertThat(current(second).status()).isEqualTo(MessageStatus.SENT);
        assertThat(cadenceAnchor()).isEqualTo(anchorBefore);
    }

    /** covers: AC-2 (every state before provider acceptance, an unknown outcome included, still blocks). */
    @Test
    void messagesTheProviderHasNotAcceptedStillBlockTheCustomer() throws Exception {
        OutboundMessage pending = submitted();
        assertThatRefused(this::submitted, MessagingErrorCode.MESSAGE_ALREADY_OPEN);

        OutboundMessage approved = approved(pending);
        assertThatRefused(this::submitted, MessagingErrorCode.MESSAGE_ALREADY_OPEN);

        provider.next = () -> new ProviderSendResult.OutcomeUnknown(Optional.empty());
        OutboundMessage unknown = sent(approved);
        assertThat(unknown.status()).isEqualTo(MessageStatus.SENDING);
        assertThat(unknown.outcomeUnknown()).isTrue();
        assertThatRefused(this::submitted, MessagingErrorCode.MESSAGE_ALREADY_OPEN);
        assertThat(provider.calls()).hasSize(1);
    }

    /**
     * covers: AC-6 (the cadence refuses at submit, and the minimum interval refuses at send). A message refused at send
     * stays APPROVED and keeps the customer's open slot until an operator cancels it (ADR 0023 Consequences).
     */
    @Test
    void theCadenceAndTheMinimumIntervalStillSpaceMessagesOut() throws Exception {
        sent(approvedMessage());
        assertThatRefused(this::submitted, MessagingErrorCode.FOLLOW_UP_INELIGIBLE);

        dueAgain();
        OutboundMessage tooSoon = approvedMessage();

        assertThatRefused(() -> sent(tooSoon), MessagingErrorCode.CONTACT_FREQUENCY_EXCEEDED);
        OutboundMessage held = current(tooSoon);
        assertThat(held.status()).isEqualTo(MessageStatus.APPROVED);
        assertThat(held.version()).isEqualTo(tooSoon.version());
        assertThat(provider.calls()).hasSize(1);
        assertThatRefused(this::submitted, MessagingErrorCode.MESSAGE_ALREADY_OPEN);

        inContext(ownerA, () -> commands.cancel(new CancellationCommand(held.id(), null,
                Long.toString(held.version()), "cancel-" + UUID.randomUUID())));

        assertThat(submitted().status()).isEqualTo(MessageStatus.PENDING_APPROVAL);
    }

    /** covers: AC-2, AC-4 (the service check's SQL agrees with MessageStatus.open() for every status). */
    @Test
    void theOpenMessageLookupFindsExactlyTheOpenStatuses() throws Exception {
        for (MessageStatus status : MessageStatus.values()) {
            UUID customerId = inContext(ownerA, () -> customers.create("Lookup " + status, null,
                    List.of(new PhoneInput(String.format("8877%04d", status.ordinal()), "CR", true)))).id().value();
            UUID messageId = insertMessage(customerId, status);
            UUID tenantId = tenantA.id().value();

            assertThat(inContext(ownerA, () -> messages.findOpenByCustomer(tenantId, customerId, Optional.empty()))
                    .map(OutboundMessage::id)).as(status.name()).isEqualTo(status.open()
                            ? Optional.of(messageId) : Optional.empty());
            assertThat(inContext(ownerA, () -> messages.findOpenByCustomer(tenantId, customerId,
                    Optional.of(messageId)))).as(status.name() + " excluded").isEmpty();
        }
    }

    /** covers: AC-4 (the index and MessageStatus.open() name the same statuses). */
    @Test
    void theOpenMessageIndexCoversExactlyTheOpenStatuses() throws Exception {
        Map<String, Object> index = inContext(ownerA, () -> jdbc.queryForMap("""
                SELECT i.indisunique AS unique_index,
                       pg_get_indexdef(i.indexrelid) AS definition,
                       pg_get_expr(i.indpred, i.indrelid) AS predicate
                FROM pg_index i
                JOIN pg_class c ON c.oid = i.indexrelid
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'dokene' AND c.relname = 'one_open_message_per_customer'
                """));

        assertThat(index.get("unique_index")).isEqualTo(true);
        assertThat((String) index.get("definition")).contains("ON dokene.outbound_messages")
                .contains("(tenant_id, customer_id)");
        String predicate = (String) index.get("predicate");
        assertThat(predicate).isNotBlank();
        Set<String> indexed = new TreeSet<>();
        Matcher literal = STATUS_LITERAL.matcher(predicate);
        while (literal.find()) {
            indexed.add(literal.group(1));
        }
        Set<String> open = Arrays.stream(MessageStatus.values()).filter(MessageStatus::open).map(Enum::name)
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(indexed).isEqualTo(open)
                .containsExactlyInAnyOrder("PENDING_APPROVAL", "APPROVED", "QUEUED", "SENDING");
    }

    private OutboundMessage submitted() throws Exception {
        long policyVersion = inContext(ownerA, () -> followUps.evaluateSnapshot(customer.id())).policyVersion();
        return inContext(ownerA, () -> commands.submit(new SubmitMessageCommand(customer.id().value(), contactId,
                "GENERAL_CHECK_IN", "GENERAL_FOLLOW_UP", BODY, "es-419", "MANUAL", Long.toString(policyVersion),
                "submit-" + UUID.randomUUID()))).message();
    }

    private OutboundMessage approved(OutboundMessage pending) throws Exception {
        OutboundMessage approved = inContext(ownerA, () -> commands.approve(new ApprovalCommand(pending.id(), null,
                Long.toString(pending.version()), "approve-" + UUID.randomUUID()))).message();
        assertThat(approved.status()).isEqualTo(MessageStatus.APPROVED);
        return approved;
    }

    private OutboundMessage approvedMessage() throws Exception {
        return approved(submitted());
    }

    private OutboundMessage sent(OutboundMessage approved) throws Exception {
        return inContext(ownerA, () -> dispatch.send(new SendMessageCommand(approved.id(),
                Long.toString(approved.version()), "send-" + UUID.randomUUID()))).message();
    }

    /** An operator schedules the next follow up for today, which makes the customer due again after a send. */
    private void dueAgain() throws Exception {
        long version = inContext(ownerA, () -> followUps.evaluateSnapshot(customer.id())).policyVersion();
        inContext(ownerA, () -> followUps.configureCustomer(customer.id(), null, LocalDate.now(ZoneId.of("UTC")),
                version));
    }

    /** The integration tests run on the real clock, so the earlier send is aged past the 24h interval instead. */
    private void dueAgainPastTheInterval(OutboundMessage sent) throws Exception {
        dueAgain();
        int aged = inContext(ownerA, () -> jdbc.update("""
                UPDATE dokene.outbound_messages SET sent_at = sent_at - interval '25 hours'
                WHERE tenant_id = ? AND id = ?
                """, sent.tenantId(), sent.id()));
        assertThat(aged).isEqualTo(1);
    }

    /** A row straight into the table, filling only what each status's check constraints require. */
    private UUID insertMessage(UUID customerId, MessageStatus status) throws Exception {
        UUID id = UUID.randomUUID();
        boolean accepted = status == MessageStatus.SENT || status == MessageStatus.DELIVERED
                || status == MessageStatus.READ;
        int inserted = inContext(ownerA, () -> jdbc.update("""
                INSERT INTO dokene.outbound_messages
                    (id, tenant_id, customer_id, contact_id, recipient_phone, channel, status, action,
                     template_intent, locale, origin, body, source_policy_version, send_key, provider_message_id,
                     failure_category, sent_at, created_by_membership_id, created_by_actor_id, created_at,
                     updated_at)
                VALUES (?, ?, ?, gen_random_uuid(), '+50688770000', 'WHATSAPP', ?, 'GENERAL_CHECK_IN',
                    'GENERAL_FOLLOW_UP', 'es-419', 'MANUAL', 'Hola', 0, gen_random_uuid(),
                    CASE WHEN ? THEN 'wamid.LOOKUP-' || gen_random_uuid() END,
                    CASE WHEN ? THEN 'UNKNOWN' END,
                    CASE WHEN ? THEN now() END,
                    gen_random_uuid(), gen_random_uuid(), now(), now())
                """, id, tenantA.id().value(), customerId, status.name(), accepted, status == MessageStatus.FAILED,
                accepted));
        assertThat(inserted).isEqualTo(1);
        return id;
    }

    private List<Object> cadenceAnchor() throws Exception {
        return inContext(ownerA, () -> jdbc.queryForList("""
                SELECT last_outbound_message_date, explicit_next_date, version
                FROM dokene.customer_follow_up_policies WHERE tenant_id = ? AND customer_id = ?
                """, tenantA.id().value(), customer.id().value()).stream().map(Object.class::cast).toList());
    }

    private OutboundMessage current(OutboundMessage message) throws Exception {
        return inContext(ownerA, () -> messages.findById(message.tenantId(), message.id())).orElseThrow();
    }

    private Customer eligibleCustomer() throws Exception {
        Instant now = Instant.now();
        Customer created = inContext(ownerA, () -> customers.create("Open window customer", null,
                List.of(new PhoneInput("8888" + String.format("%04d", Math.abs(UUID.randomUUID().hashCode()) % 10000),
                        "CR", true))));
        UUID phone = created.phones().getFirst().id();
        inContext(ownerA, () -> contacts.changeConsent(created.id(), phone, ContactChannel.WHATSAPP,
                ConsentStatus.GRANTED, ContactIntentSource.CUSTOMER_WRITTEN, contacts.get(created.id()).version()));
        inContext(ownerA, () -> purchases.record(created.id(), now.minusSeconds(60), "Anchor purchase",
                "anchor-" + UUID.randomUUID()));
        inContext(ownerA, () -> followUps.configureCustomer(created.id(), null, LocalDate.now(ZoneId.of("UTC")), 0));
        return created;
    }

    private static void assertThatRefused(Callable<?> operation, MessagingErrorCode code) {
        assertThatThrownBy(operation::call).isInstanceOf(MessagingRefusedException.class)
                .extracting(exception -> ((MessagingRefusedException) exception).code()).isEqualTo(code);
    }

    private <T> T asProvider(Callable<T> operation) throws Exception {
        return auditExecution.callWithCorrelation(UUID.randomUUID(),
                () -> providerContexts.callWithContext(new ProviderContext(tenantA.id()), operation::call));
    }

    private <T> T inContext(TenantContext context, Callable<T> operation) throws Exception {
        return auditExecution.callWithCorrelation(UUID.randomUUID(),
                () -> contexts.callWithContext(context, operation::call));
    }
}
