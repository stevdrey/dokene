package io.github.stevdrey.dokene.tenant.security;

import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.context;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.runtimeConnection;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.runtimeConnectionWithoutContext;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedMembership;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.audit.application.AuditExecutionContext;
import io.github.stevdrey.dokene.audit.application.AuditReader;
import io.github.stevdrey.dokene.audit.domain.AuditEventType;
import io.github.stevdrey.dokene.customer.application.CustomerConflictException;
import io.github.stevdrey.dokene.customer.application.CustomerCursor;
import io.github.stevdrey.dokene.customer.application.CustomerNotFoundException;
import io.github.stevdrey.dokene.customer.application.CustomerSearch;
import io.github.stevdrey.dokene.customer.application.CustomerService;
import io.github.stevdrey.dokene.customer.application.CustomerService.PhoneInput;
import io.github.stevdrey.dokene.customer.domain.Customer;
import io.github.stevdrey.dokene.customer.domain.CustomerStatus;
import io.github.stevdrey.dokene.tenant.application.DatabaseContextSigner;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.application.TenantAccessDeniedException;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantMembership;
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
class CustomerManagementIntegrationTest {
    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws SQLException {
        TenantSecurityIntegrationFixture.configure(registry);
        // Customer denials use REQUIRES_NEW auditing while the business transaction is suspended.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
    }

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

    @BeforeEach
    void setUp() {
        Instant now = Instant.now();
        tenantA = seedTenant(tenants, "Customer test A " + UUID.randomUUID(), now);
        tenantB = seedTenant(tenants, "Customer test B " + UUID.randomUUID(), now);
        TenantMembership membershipA = seedMembership(memberships, contexts, tenantA.id(),
                new IdentityId(UUID.randomUUID()), TenantRole.OWNER, now);
        TenantMembership membershipB = seedMembership(memberships, contexts, tenantB.id(),
                new IdentityId(UUID.randomUUID()), TenantRole.OWNER, now);
        contextA = context(membershipA);
        contextB = context(membershipB);
    }

    @Test
    void createsSearchesUpdatesArchivesAndAuditsWithoutPii() throws Exception {
        Customer created = inContext(contextA, () -> customers.create("Ana Example", "private note",
                List.of(new PhoneInput("8888 7777", "CR", true))));

        var page = inContext(contextA, () -> customers.search(new CustomerSearch(
                CustomerSearch.Status.ACTIVE, "ana", "+50688887777", null, 10)));
        assertThat(page.customers()).extracting(Customer::id).containsExactly(created.id());

        Customer updated = inContext(contextA, () -> customers.update(created.id(), created.version(),
                "Ana Updated", null, List.of(new PhoneInput("415 555 2671", "US", true))));
        assertThat(updated.version()).isEqualTo(1);
        assertThatThrownBy(() -> inContext(contextA, () -> customers.update(created.id(), 0,
                "Stale", null, List.of(new PhoneInput("8888 7777", "CR", true)))))
                .isInstanceOf(CustomerConflictException.class);

        inContext(contextA, () -> { customers.archive(created.id(), updated.version()); return null; });
        Customer archived = inContext(contextA, () -> customers.get(created.id()));
        assertThat(archived.status()).isEqualTo(CustomerStatus.ARCHIVED);
        assertThat(inContext(contextA, () -> customers.search(new CustomerSearch(
                CustomerSearch.Status.ACTIVE, null, null, null, 10))).customers()).isEmpty();
        assertThat(inContext(contextA, () -> customers.search(new CustomerSearch(
                CustomerSearch.Status.ARCHIVED, null, null, null, 10))).customers()).hasSize(1);

        var events = inContext(contextA, auditReader::read).events();
        assertThat(events).filteredOn(event -> event.target() != null && event.target().id().equals(created.id().value()))
                .extracting(event -> event.type()).containsExactly(
                        AuditEventType.CUSTOMER_ARCHIVED, AuditEventType.CUSTOMER_UPDATED, AuditEventType.CUSTOMER_CREATED);
        assertThat(events.toString()).doesNotContain("Ana", "private note", "+50688887777");
    }

    @Test
    void isolatesTenantsAndAllowsSamePhoneAcrossTenants() throws Exception {
        Customer a = inContext(contextA, () -> customers.create("Tenant A", null,
                List.of(new PhoneInput("8888 7777", "CR", true))));
        Customer b = inContext(contextB, () -> customers.create("Tenant B", null,
                List.of(new PhoneInput("8888 7777", "CR", true))));

        assertThat(a.phones().getFirst().e164()).isEqualTo(b.phones().getFirst().e164());
        assertThatThrownBy(() -> inContext(contextB, () -> customers.get(a.id())))
                .isInstanceOf(CustomerNotFoundException.class);

        assertThatThrownBy(() -> inContext(contextB, () -> customers.update(a.id(), a.version(),
                "Tenant B Update", null, List.of(new PhoneInput("8888 7777", "CR", true)))))
                .isInstanceOf(CustomerNotFoundException.class);
        assertThatThrownBy(() -> inContext(contextB, () -> {
            customers.archive(a.id(), a.version());
            return null;
        })).isInstanceOf(CustomerNotFoundException.class);

        assertThatThrownBy(() -> auditExecution.callWithCorrelation(UUID.randomUUID(), () -> customers.get(a.id())))
                .isInstanceOf(TenantAccessDeniedException.class);
        assertThatThrownBy(() -> auditExecution.callWithCorrelation(UUID.randomUUID(), () -> customers.create("No Context", null,
                List.of(new PhoneInput("8888 7777", "CR", true)))))
                .isInstanceOf(TenantAccessDeniedException.class);

        try (var connection = runtimeConnection(signer.issueTenantContext(tenantA.id()));
             var statement = connection.prepareStatement("SELECT count(*) FROM dokene.customers")) {
            try (var rows = statement.executeQuery()) {
                rows.next();
                assertThat(rows.getInt(1)).isEqualTo(1);
            }
        }
        try (var connection = runtimeConnectionWithoutContext();
             var statement = connection.prepareStatement("SELECT count(*) FROM dokene.customers")) {
            try (var rows = statement.executeQuery()) {
                rows.next();
                assertThat(rows.getInt(1)).isZero();
            }
        }
        try (var connection = TenantSecurityIntegrationFixture.migrationConnection();
             var statement = connection.prepareStatement("""
                     SELECT relname, relrowsecurity, relforcerowsecurity
                     FROM pg_class JOIN pg_namespace ON pg_namespace.oid = pg_class.relnamespace
                     WHERE nspname = 'dokene' AND relname IN ('customers', 'customer_phone_contacts')
                     ORDER BY relname
                     """);
             var rows = statement.executeQuery()) {
            int checked = 0;
            while (rows.next()) {
                assertThat(rows.getBoolean("relrowsecurity")).isTrue();
                assertThat(rows.getBoolean("relforcerowsecurity")).isTrue();
                checked++;
            }
            assertThat(checked).isEqualTo(2);
        }
        try (var connection = runtimeConnection(signer.issueTenantContext(tenantA.id()));
             var statement = connection.prepareStatement("""
                     INSERT INTO dokene.customers
                         (id, tenant_id, display_name, status, created_at, updated_at, version)
                     VALUES (?, ?, 'Forged', 'ACTIVE', now(), now(), 0)
                     """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, tenantB.id().value());
            assertThatThrownBy(statement::executeUpdate).isInstanceOf(SQLException.class);
        }
        try (var connection = runtimeConnection(signer.issueTenantContext(tenantA.id()));
             var statement = connection.prepareStatement("UPDATE dokene.customers SET tenant_id = ? WHERE id = ?")) {
            statement.setObject(1, tenantB.id().value());
            statement.setObject(2, a.id().value());
            assertThatThrownBy(statement::executeUpdate).isInstanceOf(SQLException.class);
        }
        try (var connection = runtimeConnection(signer.issueTenantContext(tenantA.id()));
             var statement = connection.prepareStatement("DELETE FROM dokene.customers WHERE id = ?")) {
            statement.setObject(1, a.id().value());
            assertThatThrownBy(statement::executeUpdate).isInstanceOf(SQLException.class);
        }
    }

    @Test
    void concurrentDuplicatePhoneCreatesExactlyOneCustomerAndAuditEvent() throws Exception {
        Callable<Customer> first = () -> inContext(contextA, () -> customers.create("First", null,
                List.of(new PhoneInput("8888 9999", "CR", true))));
        Callable<Customer> second = () -> inContext(contextA, () -> customers.create("Second", null,
                List.of(new PhoneInput("8888 9999", "CR", true))));

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = executor.invokeAll(List.of(first, second));
            long successes = 0;
            long conflicts = 0;
            for (var result : results) {
                try {
                    result.get();
                    successes++;
                } catch (java.util.concurrent.ExecutionException exception) {
                    if (exception.getCause() instanceof CustomerConflictException) {
                        conflicts++;
                    } else {
                        throw exception;
                    }
                }
            }
            assertThat(successes).isEqualTo(1);
            assertThat(conflicts).isEqualTo(1);
        }

        var page = inContext(contextA, () -> customers.search(new CustomerSearch(
                CustomerSearch.Status.ALL, null, "+50688889999", null, 10)));
        assertThat(page.customers()).hasSize(1);
        assertThat(inContext(contextA, auditReader::read).events())
                .filteredOn(event -> event.type() == AuditEventType.CUSTOMER_CREATED)
                .filteredOn(event -> event.target().id().equals(page.customers().getFirst().id().value()))
                .hasSize(1);
    }

    @Test
    void archivedPhoneRemainsReservedAndConcurrentUpdatesHaveOneWinner() throws Exception {
        Customer archived = inContext(contextA, () -> customers.create("Archived", null,
                List.of(new PhoneInput("8888 1111", "CR", true))));
        inContext(contextA, () -> { customers.archive(archived.id(), archived.version()); return null; });
        assertThatThrownBy(() -> inContext(contextA, () -> customers.create("Replacement", null,
                List.of(new PhoneInput("8888 1111", "CR", true)))))
                .isInstanceOf(CustomerConflictException.class);

        Customer first = inContext(contextA, () -> customers.create("Update A", null,
                List.of(new PhoneInput("8888 2222", "CR", true))));
        Customer second = inContext(contextA, () -> customers.create("Update B", null,
                List.of(new PhoneInput("8888 3333", "CR", true))));
        Callable<Customer> updateFirst = () -> inContext(contextA, () -> customers.update(first.id(), first.version(),
                "Winner A", null, List.of(new PhoneInput("8888 4444", "CR", true))));
        Callable<Customer> updateSecond = () -> inContext(contextA, () -> customers.update(second.id(), second.version(),
                "Winner B", null, List.of(new PhoneInput("8888 4444", "CR", true))));

        long successes = 0;
        long conflicts = 0;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var result : executor.invokeAll(List.of(updateFirst, updateSecond))) {
                try {
                    result.get();
                    successes++;
                } catch (java.util.concurrent.ExecutionException exception) {
                    if (exception.getCause() instanceof CustomerConflictException) {
                        conflicts++;
                    } else {
                        throw exception;
                    }
                }
            }
        }
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);
        assertThat(inContext(contextA, () -> customers.search(new CustomerSearch(
                CustomerSearch.Status.ALL, null, "+50688884444", null, 10))).customers()).hasSize(1);
    }

    @Test
    void viewerCanReadButCannotMutateCustomers() throws Exception {
        Customer customer = inContext(contextA, () -> customers.create("Read only", null,
                List.of(new PhoneInput("8888 5555", "CR", true))));
        TenantMembership viewer = seedMembership(memberships, contexts, tenantA.id(),
                new IdentityId(UUID.randomUUID()), TenantRole.VIEWER, Instant.now());
        TenantContext viewerContext = context(viewer);

        assertThat(inContext(viewerContext, () -> customers.get(customer.id())).id()).isEqualTo(customer.id());
        assertThatThrownBy(() -> inContext(viewerContext, () -> customers.update(customer.id(), customer.version(),
                "Forbidden", null, List.of(new PhoneInput("8888 5555", "CR", true)))))
                .isInstanceOf(TenantAccessDeniedException.class);
        assertThatThrownBy(() -> inContext(viewerContext, () -> {
            customers.archive(customer.id(), customer.version());
            return null;
        })).isInstanceOf(TenantAccessDeniedException.class);
    }

    @Test
    void operatorCanCreateAndModifyCustomersButCannotArchive() throws Exception {
        TenantMembership operator = seedMembership(memberships, contexts, tenantA.id(),
                new IdentityId(UUID.randomUUID()), TenantRole.OPERATOR, Instant.now());
        TenantContext operatorContext = context(operator);

        Customer created = inContext(operatorContext, () -> customers.create("Operator Customer", null,
                List.of(new PhoneInput("8888 6666", "CR", true))));
        assertThat(created).isNotNull();

        Customer updated = inContext(operatorContext, () -> customers.update(created.id(), created.version(),
                "Operator Customer Renamed", null, List.of(new PhoneInput("8888 6666", "CR", true))));
        assertThat(updated.displayName()).isEqualTo("Operator Customer Renamed");

        assertThatThrownBy(() -> inContext(operatorContext, () -> {
            customers.archive(created.id(), updated.version());
            return null;
        })).isInstanceOf(TenantAccessDeniedException.class);
    }

    @Test
    void paginatesBoundedResultsUsingCursorInChronologicalOrder() throws Exception {
        Customer first = inContext(contextA, () -> customers.create("Customer 1", null,
                List.of(new PhoneInput("8888 0001", "CR", true))));
        Thread.sleep(5);
        Customer second = inContext(contextA, () -> customers.create("Customer 2", null,
                List.of(new PhoneInput("8888 0002", "CR", true))));
        Thread.sleep(5);
        Customer third = inContext(contextA, () -> customers.create("Customer 3", null,
                List.of(new PhoneInput("8888 0003", "CR", true))));

        var page1 = inContext(contextA, () -> customers.search(new CustomerSearch(
                CustomerSearch.Status.ACTIVE, null, null, null, 2)));
        assertThat(page1.customers()).hasSize(2);
        assertThat(page1.nextCursor()).isNotNull();
        assertThat(page1.customers()).extracting(Customer::id).containsExactly(third.id(), second.id());

        var page2 = inContext(contextA, () -> customers.search(new CustomerSearch(
                CustomerSearch.Status.ACTIVE, null, null, CustomerCursor.decode(page1.nextCursor()), 2)));
        assertThat(page2.customers()).extracting(Customer::id).containsExactly(first.id());
        assertThat(page2.nextCursor()).isNull();
    }

    private <T> T inContext(TenantContext context, Callable<T> operation) throws Exception {
        return auditExecution.callWithCorrelation(UUID.randomUUID(),
                () -> contexts.callWithContext(context, operation::call));
    }
}
