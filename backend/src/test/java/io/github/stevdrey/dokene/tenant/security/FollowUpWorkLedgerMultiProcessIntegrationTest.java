package io.github.stevdrey.dokene.tenant.security;

import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.context;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedMembership;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedTenant;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.stevdrey.dokene.audit.application.AuditExecutionContext;
import io.github.stevdrey.dokene.customer.application.ContactPolicyService;
import io.github.stevdrey.dokene.customer.application.CustomerService;
import io.github.stevdrey.dokene.customer.application.CustomerService.PhoneInput;
import io.github.stevdrey.dokene.customer.domain.ConsentStatus;
import io.github.stevdrey.dokene.customer.domain.ContactChannel;
import io.github.stevdrey.dokene.customer.domain.ContactIntentSource;
import io.github.stevdrey.dokene.customer.domain.Customer;
import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.followup.application.FollowUpCandidateGenerator;
import io.github.stevdrey.dokene.followup.application.FollowUpCandidateProperties;
import io.github.stevdrey.dokene.followup.application.FollowUpCandidateRepository;
import io.github.stevdrey.dokene.followup.application.FollowUpService;
import io.github.stevdrey.dokene.followup.application.FollowUpWorkLedger;
import io.github.stevdrey.dokene.followup.application.FollowUpWorkLedgerRepository;
import io.github.stevdrey.dokene.followup.application.FollowUpWorkProperties;
import io.github.stevdrey.dokene.followup.application.FollowUpWorkTransition;
import io.github.stevdrey.dokene.followup.domain.FollowUpWorkClaim;
import io.github.stevdrey.dokene.followup.domain.FollowUpWorkItem;
import io.github.stevdrey.dokene.followup.domain.FollowUpWorkState;
import io.github.stevdrey.dokene.followup.domain.FollowUpWorkerId;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.io.IOException;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Issue 458 (ADR 0037): independent backend processes sharing one PostgreSQL. Each child JVM
 * ({@link FollowUpWorkClaimProcess}) runs the production JDBC ledger with its own connection and clock. The children
 * connect as the migration role, which the ledger policies let through without a tenant capability; tenant isolation
 * under the runtime role is covered by {@link FollowUpWorkLedgerIntegrationTest}.
 */
@SpringBootTest(properties = "dokene.ai.provider=fake")
class FollowUpWorkLedgerMultiProcessIntegrationTest {
    private static final Duration LEASE = Duration.ofSeconds(2);

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws Exception {
        TenantSecurityIntegrationFixture.configure(registry);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
    }

    @Autowired FollowUpWorkLedgerRepository repository;
    @Autowired FollowUpCandidateRepository candidates;
    @Autowired FollowUpService followUps;
    @Autowired ContactPolicyService contacts;
    @Autowired CustomerService customers;
    @Autowired TenantRepository tenants;
    @Autowired TenantMembershipRepository memberships;
    @Autowired TenantContextProvider contexts;
    @Autowired AuditExecutionContext auditExecution;
    @Autowired PlatformTransactionManager transactionManager;

    private Tenant tenant;
    private TenantContext tenantContext;
    private Instant now;

    @BeforeEach
    void setUp() {
        now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        tenant = seedTenant(tenants, contexts, "Work processes " + UUID.randomUUID(), now);
        tenantContext = context(seedMembership(memberships, contexts, tenant.id(),
                new IdentityId(UUID.randomUUID()), TenantRole.OWNER, now));
    }

    @Test
    void twoBackendProcessesClaimingAtOnceNeverClaimTheSameItem() throws Exception {
        int count = 40;
        enqueueDueCustomers(count);
        Process first = launch("drain", "process-a", Duration.ofMinutes(5));
        Process second = launch("drain", "process-b", Duration.ofMinutes(5));
        go(first, second);
        List<String[]> claimsA = claimed(first);
        List<String[]> claimsB = claimed(second);

        Set<String> candidateIds = new HashSet<>();
        for (String[] claim : concat(claimsA, claimsB)) {
            assertThat(candidateIds.add(claim[1])).as("claimed once: %s", claim[1]).isTrue();
            assertThat(claim[2]).isEqualTo("1");
        }
        assertThat(candidateIds).hasSize(count);
        // Both processes were released at once and, pausing between batches like workers, each took part of the work.
        assertThat(claimsA).isNotEmpty();
        assertThat(claimsB).isNotEmpty();
        assertThat(items()).allSatisfy(item -> assertThat(item.state()).isEqualTo(FollowUpWorkState.CLAIMED))
                .extracting(item -> item.claimedBy().orElseThrow().value())
                .containsOnly("process-a", "process-b");
    }

    @Test
    void aCrashedProcessesClaimIsTakenOverByAnotherProcessAndItsOwnerIsStale() throws Exception {
        enqueueDueCustomers(1);
        Process crashing = launch("claim-and-crash", "process-a", LEASE);
        go(crashing);
        List<String[]> crashedClaims = claimed(crashing);
        assertThat(crashing.exitValue()).isEqualTo(137);
        assertThat(crashedClaims).hasSize(1);
        String[] crashed = crashedClaims.getFirst();
        assertThat(items()).singleElement().satisfies(item -> {
            assertThat(item.state()).isEqualTo(FollowUpWorkState.CLAIMED);
            assertThat(item.claimedBy()).contains(new FollowUpWorkerId("process-a"));
        });

        // A second process starts after the dead process's lease has expired and takes the item over.
        Instant leaseEnd = Instant.parse(crashed[5]);
        Process takeover = launch("drain", "process-b", Duration.ofMinutes(5));
        Thread.sleep(Math.max(0, Duration.between(Instant.now(), leaseEnd).toMillis() + 200));
        go(takeover);
        List<String[]> takeoverClaims = claimed(takeover);
        assertThat(takeover.exitValue()).isZero();
        assertThat(takeoverClaims).singleElement().satisfies(claim -> {
            assertThat(claim[1]).isEqualTo(crashed[1]);
            assertThat(claim[2]).isEqualTo("2");
            assertThat(claim[3]).isNotEqualTo(crashed[3]);
        });

        // The crashed process's claim, replayed by a revived owner in this JVM, is rejected; the takeover's claim wins.
        FollowUpWorkItem item = items().getFirst();
        FollowUpWorkLedger revived = new FollowUpWorkLedger(repository, contexts,
                new FollowUpWorkProperties(5, Duration.ofSeconds(10), 3), transactionManager, Clock.systemUTC());
        assertThat(revived.complete(claim(item, crashed))).isEqualTo(FollowUpWorkTransition.STALE);
        assertThat(revived.complete(claim(item, takeoverClaims.getFirst())))
                .isEqualTo(FollowUpWorkTransition.COMPLETED);
        assertThat(items()).singleElement().satisfies(done -> {
            assertThat(done.state()).isEqualTo(FollowUpWorkState.COMPLETED);
            assertThat(done.attempts()).isEqualTo(2);
            assertThat(done.claimedBy()).contains(new FollowUpWorkerId("process-b"));
        });
    }

    private FollowUpWorkClaim claim(FollowUpWorkItem item, String[] line) {
        return new FollowUpWorkClaim(UUID.fromString(line[1]), tenant.id(), item.customerId(), item.dueDate(),
                new FollowUpWorkerId("revived"), Integer.parseInt(line[2]), UUID.fromString(line[3]),
                Instant.parse(line[4]), Instant.parse(line[5]));
    }

    private Process launch(String mode, String worker, Duration lease) throws IOException {
        String java = ProcessHandle.current().info().command().orElseThrow();
        ProcessBuilder builder = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                FollowUpWorkClaimProcess.class.getName(), mode, tenant.id().value().toString(), worker,
                Long.toString(lease.toMillis()));
        builder.environment().put("WORK_DB_URL", TenantSecurityIntegrationFixture.POSTGRES.getJdbcUrl());
        builder.environment().put("WORK_DB_USER", TenantSecurityIntegrationFixture.MIGRATION_ROLE);
        builder.environment().put("WORK_DB_PASSWORD", TenantSecurityIntegrationFixture.MIGRATION_PASSWORD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        return builder.start();
    }

    /** Waits until every process has connected and printed READY, then releases them together. */
    private static void go(Process... processes) throws IOException {
        for (Process process : processes) {
            // Process caches the reader per charset, so claimed() continues from the line after READY.
            String line = process.inputReader(StandardCharsets.UTF_8).readLine();
            if (!"READY".equals(line)) {
                throw new AssertionError("Claim process did not start: " + line);
            }
        }
        for (Process process : processes) {
            process.outputWriter(StandardCharsets.UTF_8).write("GO\n");
            process.outputWriter(StandardCharsets.UTF_8).flush();
        }
    }

    private static List<String[]> claimed(Process process) throws Exception {
        StringBuilder output = new StringBuilder();
        BufferedReader stdout = process.inputReader(StandardCharsets.UTF_8);
        for (String line = stdout.readLine(); line != null; line = stdout.readLine()) {
            output.append(line).append('\n');
        }
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Claim process did not finish");
        }
        return output.toString().lines().filter(line -> line.startsWith("CLAIMED ")).map(line -> line.split(" ")).toList();
    }

    private static List<String[]> concat(List<String[]> left, List<String[]> right) {
        List<String[]> all = new ArrayList<>(left);
        all.addAll(right);
        return all;
    }

    private void enqueueDueCustomers(int count) throws Exception {
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        for (int index = 0; index < count; index++) {
            CustomerId customer = consentedCustomer("Process customer " + index, index).id();
            inContext(() -> followUps.configureCustomer(customer, 7, today, 0));
        }
        new FollowUpCandidateGenerator(candidates, tenants, contexts, new FollowUpCandidateProperties(100),
                transactionManager, Clock.fixed(now, ZoneOffset.UTC)).generate(tenant.id());
        FollowUpWorkLedger ledger = new FollowUpWorkLedger(repository, contexts,
                new FollowUpWorkProperties(100, Duration.ofSeconds(10), 3), transactionManager,
                Clock.fixed(now, ZoneOffset.UTC));
        assertThat(ledger.enqueue(tenant.id())).isEqualTo(count);
    }

    private List<FollowUpWorkItem> items() {
        return contexts.callWithTenantId(tenant.id(), () -> repository.findItems(tenant.id(), 100));
    }

    /** Phones are unique per tenant, so each customer of the fresh tenant gets its own sequential number. */
    private Customer consentedCustomer(String name, int index) throws Exception {
        Customer customer = inContext(() -> customers.create(name, null, List.of(new PhoneInput(
                "8888" + String.format("%04d", index), "CR", true))));
        var policy = inContext(() -> contacts.get(customer.id()));
        UUID contactId = customer.phones().getFirst().id();
        inContext(() -> contacts.changeConsent(customer.id(), contactId, ContactChannel.WHATSAPP,
                ConsentStatus.GRANTED, ContactIntentSource.CUSTOMER_WRITTEN, policy.version()));
        return customer;
    }

    private <T> T inContext(Callable<T> operation) throws Exception {
        return auditExecution.callWithCorrelation(UUID.randomUUID(),
                () -> contexts.callWithContext(tenantContext, operation::call));
    }
}
