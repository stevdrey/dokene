package io.github.stevdrey.dokene.ai.eval;

import io.github.stevdrey.dokene.ai.application.AiProvider;
import io.github.stevdrey.dokene.ai.domain.ActionRecommendation;
import io.github.stevdrey.dokene.ai.domain.DraftJsonSchema;
import io.github.stevdrey.dokene.ai.domain.RecommendationJsonSchema;
import io.github.stevdrey.dokene.audit.application.AuditExecutionContext;
import io.github.stevdrey.dokene.customer.application.ContactPolicyService;
import io.github.stevdrey.dokene.customer.application.CustomerService;
import io.github.stevdrey.dokene.customer.application.CustomerService.PhoneInput;
import io.github.stevdrey.dokene.customer.domain.ConsentStatus;
import io.github.stevdrey.dokene.customer.domain.ContactChannel;
import io.github.stevdrey.dokene.customer.domain.ContactIntentSource;
import io.github.stevdrey.dokene.customer.domain.Customer;
import io.github.stevdrey.dokene.followup.application.FollowUpDraftResult;
import io.github.stevdrey.dokene.followup.application.FollowUpDraftService;
import io.github.stevdrey.dokene.followup.application.FollowUpRecommendationResult;
import io.github.stevdrey.dokene.followup.application.FollowUpRecommendationService;
import io.github.stevdrey.dokene.followup.application.FollowUpService;
import io.github.stevdrey.dokene.purchase.application.PurchaseService;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * Drives the production recommendation and draft services over an isolated synthetic tenant. Every case runs through
 * the same authorization, tenant-context, Action Gate and failure-handling code paths as the application; only the
 * provider differs (scripted or live). Nothing here talks to a real customer or a production tenant.
 */
public final class EvalRunner {
    /** Fixed so every draft prompt is identical across runs; isolation comes from the random tenant id. */
    static final String SYNTHETIC_BUSINESS_NAME = "Tienda Demo";
    static final ZoneId ZONE = ZoneId.of("America/Costa_Rica");

    private final FollowUpService followUps;
    private final FollowUpRecommendationService recommendations;
    private final FollowUpDraftService drafts;
    private final CustomerService customers;
    private final ContactPolicyService contacts;
    private final PurchaseService purchases;
    private final TenantRepository tenants;
    private final TenantMembershipRepository memberships;
    private final TenantContextProvider contexts;
    private final AuditExecutionContext auditExecution;
    private final RecordingAiProvider recorder;

    public EvalRunner(FollowUpService followUps, FollowUpRecommendationService recommendations,
            FollowUpDraftService drafts, CustomerService customers, ContactPolicyService contacts,
            PurchaseService purchases, TenantRepository tenants, TenantMembershipRepository memberships,
            TenantContextProvider contexts, AuditExecutionContext auditExecution, AiProvider provider) {
        this.followUps = followUps;
        this.recommendations = recommendations;
        this.drafts = drafts;
        this.customers = customers;
        this.contacts = contacts;
        this.purchases = purchases;
        this.tenants = tenants;
        this.memberships = memberships;
        this.contexts = contexts;
        this.auditExecution = auditExecution;
        if (!(provider instanceof RecordingAiProvider recording)) {
            throw new IllegalArgumentException("Evaluation requires the recording provider from EvalProviderConfiguration");
        }
        this.recorder = recording;
    }

    /** Runs all cases in dataset order inside one fresh synthetic tenant and returns the observations. */
    public List<CaseObservation> run(EvalDataset dataset) throws Exception {
        Instant now = Instant.now();
        Tenant tenant = TenantSecurityIntegrationFixture.seedTenant(tenants, SYNTHETIC_BUSINESS_NAME, now);
        TenantContext tenantContext = TenantSecurityIntegrationFixture.context(
                TenantSecurityIntegrationFixture.seedMembership(memberships, contexts, tenant.id(),
                        new IdentityId(UUID.randomUUID()), TenantRole.OWNER, now));
        inContext(tenantContext, () -> followUps.configureTenant(14, ZONE, 0L));
        List<CaseObservation> observations = new ArrayList<>();
        int index = 0;
        for (EvalCase evalCase : dataset.cases()) {
            observations.add(runCase(tenantContext, evalCase, index++));
        }
        return observations;
    }

    private CaseObservation runCase(TenantContext tenantContext, EvalCase evalCase, int index) throws Exception {
        Customer customer = seed(tenantContext, evalCase, index);
        String recStatus;
        String recRejection = null;
        ActionRecommendation delivered = null;
        io.github.stevdrey.dokene.ai.domain.NoRecommendation recommendationRefusal = null;
        try {
            FollowUpRecommendationResult result = inContext(tenantContext,
                    () -> recommendations.recommendSafe(customer.id(), null, null));
            recStatus = result.status().name();
            recRejection = result.gateRejection().map(Enum::name).orElse(
                    result.providerFailure().orElse(null));
            delivered = result.recommendation();
            recommendationRefusal = result.refusal();
        } catch (RuntimeException ex) {
            recStatus = "EXCEPTION_" + ex.getClass().getSimpleName();
        }

        EvalCase.Request request = evalCase.request();
        var draftAction = request != null && request.draftAction() != null ? request.draftAction()
                : delivered != null ? delivered.action() : null;
        var draftIntent = request != null && request.draftIntent() != null ? request.draftIntent()
                : request != null && request.draftAction() != null ? null
                : delivered != null ? delivered.templateIntent() : null;
        String draftStatus;
        String draftRejection = null;
        io.github.stevdrey.dokene.ai.domain.MessageDraft deliveredDraft = null;
        io.github.stevdrey.dokene.ai.domain.NoDraft draftRefusal = null;
        try {
            FollowUpDraftResult result = inContext(tenantContext,
                    () -> drafts.draftSafe(customer.id(), draftAction, draftIntent, null, null));
            draftStatus = result.status().name();
            draftRejection = result.gateRejection().map(Enum::name).orElse(result.providerFailure().orElse(null));
            deliveredDraft = result.draft();
            draftRefusal = result.refusal();
        } catch (RuntimeException ex) {
            draftStatus = "EXCEPTION_" + ex.getClass().getSimpleName();
        }
        return new CaseObservation(evalCase, recStatus, recRejection, delivered, draftStatus, draftRejection,
                deliveredDraft, recorder.callsFor(evalCase.displayName()), recommendationRefusal, draftRefusal);
    }

    private Customer seed(TenantContext tenantContext, EvalCase evalCase, int index) throws Exception {
        EvalCase.Setup setup = evalCase.setup();
        Customer customer = inContext(tenantContext, () -> customers.create(evalCase.displayName(), setup.notes(),
                List.of(new PhoneInput("8888" + String.format("%04d", index), "CR", true))));
        UUID contactId = customer.phones().getFirst().id();
        if ("GRANTED".equals(setup.consent()) || "REVOKED".equals(setup.consent())) {
            ConsentStatus status = ConsentStatus.valueOf(setup.consent());
            inContext(tenantContext, () -> contacts.changeConsent(customer.id(), contactId, ContactChannel.WHATSAPP,
                    status, ContactIntentSource.CUSTOMER_WRITTEN, contacts.get(customer.id()).version()));
        }
        if (setup.doNotContact()) {
            inContext(tenantContext, () -> contacts.changeDoNotContact(customer.id(), true,
                    ContactIntentSource.CUSTOMER_WRITTEN, contacts.get(customer.id()).version()));
        }
        LocalDate today = LocalDate.now(ZONE);
        int purchaseIndex = 0;
        for (EvalCase.PurchaseSpec spec : setup.purchases()) {
            Instant when = today.minusDays(spec.daysAgo()).atStartOfDay(ZONE).toInstant();
            String key = "eval-" + evalCase.id() + "-" + purchaseIndex++;
            inContext(tenantContext, () -> purchases.record(customer.id(), when, spec.description(), key));
        }
        if (setup.customerCadenceDays() != null || setup.explicitNextFollowUpDaysFromToday() != null) {
            LocalDate explicit = setup.explicitNextFollowUpDaysFromToday() == null ? null
                    : today.plusDays(setup.explicitNextFollowUpDaysFromToday());
            inContext(tenantContext, () -> followUps.configureCustomer(customer.id(), setup.customerCadenceDays(),
                    explicit, followUps.customerPolicy(customer.id()).version()));
        }
        if (setup.archived()) {
            inContext(tenantContext, () -> {
                customers.archive(customer.id(), customers.get(customer.id()).version());
                return null;
            });
        }
        return customer;
    }

    private <T> T inContext(TenantContext tenantContext, Callable<T> operation) throws Exception {
        return auditExecution.callWithCorrelation(UUID.randomUUID(),
                () -> contexts.callWithContext(tenantContext, operation::call));
    }

    /**
     * Hash of the strict structured-output contracts, so a contract change is visible when comparing reports.
     * The schemas are canonicalized first (sorted keys, sorted scalar arrays) because their generation order is not
     * stable across JVM runs.
     */
    public static String contractFingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String schema : List.of(RecommendationJsonSchema.generateSchemaJson(), DraftJsonSchema.generateSchemaJson())) {
                digest.update(canonical(FINGERPRINT_MAPPER.readTree(schema)).getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest()).substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static final tools.jackson.databind.json.JsonMapper FINGERPRINT_MAPPER =
            tools.jackson.databind.json.JsonMapper.builder().build();

    private static String canonical(tools.jackson.databind.JsonNode node) {
        if (node.isObject()) {
            List<String> names = new ArrayList<>(node.propertyNames());
            java.util.Collections.sort(names);
            StringBuilder out = new StringBuilder("{");
            for (String name : names) {
                out.append('"').append(name).append("\":").append(canonical(node.get(name))).append(',');
            }
            return out.append('}').toString();
        }
        if (node.isArray()) {
            List<String> items = new ArrayList<>();
            node.forEach(item -> items.add(canonical(item)));
            java.util.Collections.sort(items);
            return "[" + String.join(",", items) + "]";
        }
        return node.toString();
    }

    static Set<String> sortedModels(List<CaseObservation> observations, boolean provider) {
        Set<String> values = new TreeSet<>();
        for (CaseObservation obs : observations) {
            obs.calls().stream().filter(call -> call.metadata() != null)
                    .forEach(call -> values.add(provider ? call.metadata().providerId() : call.metadata().modelId()));
        }
        return new LinkedHashSet<>(values);
    }

    static Map<String, Object> newInformational() {
        return new LinkedHashMap<>();
    }
}
