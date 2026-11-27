package io.github.stevdrey.dokene.tenant.security;

import io.github.stevdrey.dokene.followup.domain.FollowUpWorkClaim;
import io.github.stevdrey.dokene.followup.domain.FollowUpWorkerId;
import io.github.stevdrey.dokene.followup.persistence.jdbc.JdbcFollowUpWorkLedgerRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Issue 458 (ADR 0037): a separate backend process for {@link FollowUpWorkLedgerMultiProcessIntegrationTest}. It runs
 * the production JDBC ledger in its own JVM against the shared database, with its own connection and clock, and
 * prints one {@code CLAIMED} line per claim. The database URL and credentials come from the environment, never from
 * the command line.
 *
 * <p>Arguments: {@code drain <tenant> <worker> <leaseMillis>} claims batches of two, pausing between them as a
 * worker would, until none is left; {@code claim-and-crash <tenant> <worker> <leaseMillis>} claims one item and halts
 * the JVM without completing it, as a crash would. The process prints {@code READY} and claims nothing until it reads
 * {@code GO} on its standard input, so the test can start several processes at the same moment.
 */
public final class FollowUpWorkClaimProcess {
    private FollowUpWorkClaimProcess() {
    }

    public static void main(String[] args) throws InterruptedException, IOException {
        String mode = args[0];
        TenantId tenantId = new TenantId(UUID.fromString(args[1]));
        FollowUpWorkerId worker = new FollowUpWorkerId(args[2]);
        Duration lease = Duration.ofMillis(Long.parseLong(args[3]));
        DriverManagerDataSource dataSource = new DriverManagerDataSource(System.getenv("WORK_DB_URL"),
                System.getenv("WORK_DB_USER"), System.getenv("WORK_DB_PASSWORD"));
        JdbcFollowUpWorkLedgerRepository ledger = new JdbcFollowUpWorkLedgerRepository(new JdbcTemplate(dataSource));
        ledger.findItems(tenantId, 1);
        System.out.println("READY");
        System.out.flush();
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        if (!"GO".equals(input.readLine())) {
            throw new IllegalStateException("Expected GO");
        }
        int limit = mode.equals("claim-and-crash") ? 1 : 2;
        while (true) {
            Instant now = Instant.now();
            List<FollowUpWorkClaim> claims = ledger.claim(tenantId, worker, now, now.plus(lease), 3, limit);
            for (FollowUpWorkClaim claim : claims) {
                System.out.printf("CLAIMED %s %d %s %s %s%n", claim.candidateId(), claim.attempt(),
                        claim.claimToken(), claim.claimedAt(), claim.leaseExpiresAt());
            }
            System.out.flush();
            if (mode.equals("claim-and-crash") && !claims.isEmpty()) {
                Runtime.getRuntime().halt(137);
            }
            if (claims.isEmpty()) {
                return;
            }
            Thread.sleep(25);
        }
    }
}
