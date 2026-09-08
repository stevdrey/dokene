package io.github.stevdrey.dokene.tenant.security;

import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.context;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.runtimeConnection;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedMembership;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.audit.application.AuditExecutionContext;
import io.github.stevdrey.dokene.audit.application.AuditReader;
import io.github.stevdrey.dokene.audit.domain.AuditEventType;
import io.github.stevdrey.dokene.customer.application.CustomerService;
import io.github.stevdrey.dokene.customer.application.CustomerService.PhoneInput;
import io.github.stevdrey.dokene.customer.domain.Customer;
import io.github.stevdrey.dokene.purchase.application.PurchaseConflictException;
import io.github.stevdrey.dokene.purchase.application.PurchaseNotFoundException;
import io.github.stevdrey.dokene.purchase.application.PurchaseService;
import io.github.stevdrey.dokene.purchase.domain.Purchase;
import io.github.stevdrey.dokene.purchase.domain.PurchaseStatus;
import io.github.stevdrey.dokene.tenant.application.DatabaseContextSigner;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class PurchaseManagementIntegrationTest {
    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws SQLException {
        TenantSecurityIntegrationFixture.configure(registry);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
    }

    @Autowired PurchaseService purchases;
    @Autowired CustomerService customers;
    @Autowired TenantRepository tenants;
    @Autowired TenantMembershipRepository memberships;
    @Autowired TenantContextProvider contexts;
    @Autowired AuditExecutionContext auditExecution;
    @Autowired AuditReader auditReader;
    @Autowired DatabaseContextSigner signer;

    private Tenant tenantA;
    private Tenant tenantB;
    private TenantContext contextA;
    private TenantContext contextB;
    private Customer customerA;

    @BeforeEach
    void setUp() throws Exception {
        Instant now = Instant.now();
        tenantA = seedTenant(tenants, "Purchase A " + UUID.randomUUID(), now);
        tenantB = seedTenant(tenants, "Purchase B " + UUID.randomUUID(), now);
        contextA = context(seedMembership(memberships, contexts, tenantA.id(), new IdentityId(UUID.randomUUID()), TenantRole.OWNER, now));
        contextB = context(seedMembership(memberships, contexts, tenantB.id(), new IdentityId(UUID.randomUUID()), TenantRole.OWNER, now));
        customerA = inContext(contextA, () -> customers.create("Purchase customer", null,
                List.of(new PhoneInput("8888" + String.format("%04d", Math.abs(UUID.randomUUID().hashCode()) % 10000), "CR", true))));
    }

    @Test
    void derivesLastPurchaseAcrossBackdatingCorrectionAndVoidAndKeepsHistory() throws Exception {
        assertThat(inContext(contextA, () -> purchases.lastPurchase(customerA.id()))).isEmpty();
        Instant older = Instant.now().minusSeconds(3600);
        Instant newer = Instant.now().minusSeconds(60);
        Purchase first = inContext(contextA, () -> purchases.record(customerA.id(), newer, "Newer item", "key-first")).purchase();
        Purchase second = inContext(contextA, () -> purchases.record(customerA.id(), older, "Older item", "key-second")).purchase();
        assertThat(inContext(contextA, () -> purchases.lastPurchase(customerA.id())))
                .get().extracting(Purchase::id).isEqualTo(first.id());

        Purchase corrected = inContext(contextA, () -> purchases.correct(customerA.id(), second.id(),
                newer.plusSeconds(30), "Corrected item", 0));
        assertThat(inContext(contextA, () -> purchases.lastPurchase(customerA.id())))
                .get().extracting(Purchase::id).isEqualTo(corrected.id());
        inContext(contextA, () -> { purchases.voidPurchase(customerA.id(), second.id(), corrected.version()); return null; });
        assertThat(inContext(contextA, () -> purchases.lastPurchase(customerA.id())))
                .get().extracting(Purchase::id).isEqualTo(first.id());

        var history = inContext(contextA, () -> purchases.history(customerA.id(), second.id(), null, 2));
        assertThat(history.events()).hasSize(2);
        assertThat(history.nextCursor()).isNotNull();
        assertThat(inContext(contextA, () -> purchases.history(customerA.id(), second.id(),
                io.github.stevdrey.dokene.purchase.application.PurchaseEventCursor.decode(history.nextCursor()), 2)).events())
                .hasSize(1);

        var auditTypes = inContext(contextA, auditReader::read).events().stream()
                .filter(event -> event.target() != null && event.target().id().equals(second.id().value()))
                .map(event -> event.type()).toList();
        assertThat(auditTypes).containsExactlyInAnyOrder(AuditEventType.PURCHASE_RECORDED,
                AuditEventType.PURCHASE_CORRECTED, AuditEventType.PURCHASE_VOIDED);
        assertThat(inContext(contextA, () -> purchases.list(customerA.id(), PurchaseStatus.VOID, null, 10)).purchases())
                .extracting(Purchase::id).containsExactly(second.id());
    }

    @Test
    void handlesRetriesConflictsAndConcurrentDuplicateSubmissions() throws Exception {
        Instant at = Instant.now().minusSeconds(10);
        var created = inContext(contextA, () -> purchases.record(customerA.id(), at, "Same", "retry-key"));
        var replay = inContext(contextA, () -> purchases.record(customerA.id(), at, "Same", "retry-key"));
        assertThat(replay.created()).isFalse();
        assertThat(replay.purchase().id()).isEqualTo(created.purchase().id());
        assertThatThrownBy(() -> inContext(contextA,
                () -> purchases.record(customerA.id(), at, "Different", "retry-key")))
                .isInstanceOf(PurchaseConflictException.class);

        Callable<PurchaseService.RecordResult> call = () -> inContext(contextA,
                () -> purchases.record(customerA.id(), at, "Concurrent", "concurrent-key"));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = executor.invokeAll(List.of(call, call));
            assertThat(results).allSatisfy(result -> assertThat(result.get().purchase()).isNotNull());
            assertThat(results.stream().filter(result -> {
                try { return result.get().created(); } catch (Exception exception) { throw new RuntimeException(exception); }
            })).hasSize(1);
        }
    }

    @Test
    void enforcesCustomerOwnershipAndRlsAndImmutableTenantReference() throws Exception {
        Purchase purchase = inContext(contextA, () -> purchases.record(customerA.id(), Instant.now().minusSeconds(1),
                "Private detail", "isolation-key")).purchase();
        assertThatThrownBy(() -> inContext(contextB, () -> purchases.get(customerA.id(), purchase.id())))
                .isInstanceOfAny(PurchaseNotFoundException.class, io.github.stevdrey.dokene.customer.application.CustomerNotFoundException.class);

        try (var connection = runtimeConnection(signer.issueTenantContext(tenantA.id()));
             var statement = connection.prepareStatement("UPDATE dokene.purchases SET tenant_id = ? WHERE id = ?")) {
            statement.setObject(1, tenantB.id().value());
            statement.setObject(2, purchase.id().value());
            assertThatThrownBy(statement::executeUpdate).isInstanceOf(SQLException.class);
        }
        try (var connection = runtimeConnection(signer.issueTenantContext(tenantB.id()));
             var statement = connection.prepareStatement("SELECT count(*) FROM dokene.purchases WHERE id = ?")) {
            statement.setObject(1, purchase.id().value());
            try (var rows = statement.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isZero(); }
        }
    }

    private <T> T inContext(TenantContext context, Callable<T> operation) throws Exception {
        return auditExecution.callWithCorrelation(UUID.randomUUID(),
                () -> contexts.callWithContext(context, operation::call));
    }
}
