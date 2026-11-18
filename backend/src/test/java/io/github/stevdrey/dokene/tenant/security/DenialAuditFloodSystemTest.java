// ABOUTME: Sends the audit L1 flood over real HTTP (spec 0020, issue 310): 1,000 denied requests leave ten audit rows
// ABOUTME: and one summary of 990, every answer is the same 403, and an outage only fails denials that need a row.
package io.github.stevdrey.dokene.tenant.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import io.github.stevdrey.dokene.audit.persistence.jdbc.DenialAuditSummarySweep;
import io.github.stevdrey.dokene.audit.security.AuditRequestFilter;
import io.github.stevdrey.dokene.audit.testsupport.MutableClock;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;

// The sweep interval equals the window so the scheduled run never fires during a test; tests call sweepOnce().
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "dokene.audit.denial-limit.rows-per-key=10",
        "dokene.audit.denial-limit.rows-per-identity=60",
        "dokene.audit.denial-limit.sweep-interval=PT15M",
        "dokene.datasource.unavailable-retry-after=7s"})
@Import(PurchaseCurlSystemTest.CurlSecurityConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class DenialAuditFloodSystemTest {
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final MutableClock CLOCK = new MutableClock(Instant.now());
    private static final String SUMMARY = "AUTHORIZATION_DENIALS_SUPPRESSED";

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws SQLException {
        TenantSecurityIntegrationFixture.configure(registry);
        // One connection: while the test holds it, a denial that needs a row waits this long and then gets a 503.
        registry.add("spring.datasource.hikari.connection-timeout", () -> "1000");
    }

    @TestBean private Clock clock;
    @LocalServerPort int port;
    @Autowired DenialAuditSummarySweep sweep;
    @Autowired HikariDataSource runtimePool;

    private final HttpClient http = HttpClient.newHttpClient();

    static Clock clock() {
        return CLOCK;
    }

    /** Ends and writes whatever an earlier test left counted. */
    @BeforeEach
    void emptyTracker() {
        CLOCK.advance(WINDOW);
        sweep.sweepOnce();
    }

    /** covers: AC-1, AC-2, AC-17 */
    @Test
    void oneThousandDeniedRequestsLeaveTenRowsAndOneSummaryOf990() throws Exception {
        UUID identity = UUID.randomUUID();
        List<HttpResponse<String>> answers = new ArrayList<>();
        for (int request = 0; request < 1_000; request++) {
            answers.add(get(identity, UUID.randomUUID()));
        }

        // The caller cannot tell a written denial from a counted one.
        assertThat(answers).allSatisfy(answer -> {
            assertThat(answer.statusCode()).isEqualTo(403);
            assertThat(answer.body()).isEqualTo(answers.getFirst().body());
        });
        List<UUID> correlations = answers.stream().map(DenialAuditFloodSystemTest::correlation).toList();
        assertThat(correlations).doesNotHaveDuplicates();
        List<Map<String, Object>> denied = rows(correlations);
        assertThat(denied).hasSize(10).allSatisfy(row -> {
            assertThat(row.get("event_type")).isEqualTo("AUTHORIZATION_DENIED");
            assertThat(row.get("tenant_id")).isNull();
            assertThat(row.get("actor_id")).isNull();
        });
        assertThat(denied).extracting(row -> row.get("correlation_id"))
                .containsExactlyInAnyOrderElementsOf(correlations.subList(0, 10));

        CLOCK.advance(WINDOW);
        sweep.sweepOnce();

        List<Map<String, Object>> summaries = rows(correlations).stream()
                .filter(row -> SUMMARY.equals(row.get("event_type"))).toList();
        assertThat(rows(correlations)).hasSize(11);
        assertThat(summaries).hasSize(1);
        Map<String, Object> summary = summaries.getFirst();
        assertThat(summary.get("suppressed_count")).isEqualTo(990);
        assertThat(summary.get("permission")).isEqualTo("TENANT_READ");
        assertThat(summary.get("denial_reason")).isEqualTo("NO_TENANT_CONTEXT");
        assertThat(summary.get("correlation_id")).isEqualTo(correlations.get(10));
        assertThat(summary.get("tenant_id")).isNull();
        assertThat(summary.get("actor_id")).isNull();
        assertThat(summary.get("membership_id")).isNull();
        // No header value of the flood, identity or foreign tenant, is stored anywhere on the rows.
        assertThat(rows(correlations).toString()).doesNotContain(identity.toString());
    }

    /** covers: AC-1 */
    @Test
    void duringAnOutageASuppressedDenialGetsItsRefusalAndADenialThatNeedsARowGets503() throws Exception {
        UUID spent = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        // No tenant selector: the filter refuses before anything reads the database, so only the audit row needs it.
        List<HttpResponse<String>> insideBudget = new ArrayList<>();
        for (int request = 0; request < 10; request++) {
            insideBudget.add(get(spent, null));
        }
        assertThat(insideBudget).allSatisfy(answer -> assertThat(answer.statusCode()).isEqualTo(403));

        HttpResponse<String> suppressed;
        HttpResponse<String> needsRow;
        try (Connection held = runtimePool.getConnection()) {
            assertThat(held.isValid(1)).isTrue();
            suppressed = get(spent, null);
            needsRow = get(fresh, null);
        }

        assertThat(suppressed.statusCode()).isEqualTo(403);
        assertThat(suppressed.body()).isEqualTo(insideBudget.getFirst().body());
        assertThat(needsRow.statusCode()).isEqualTo(503);
        assertThat(needsRow.headers().firstValue("Retry-After")).contains("7");
        assertThat(rows(List.of(correlation(suppressed), correlation(needsRow)))).isEmpty();
        // The database is back: the same caller is refused and audited again.
        HttpResponse<String> recovered = get(fresh, null);
        assertThat(recovered.statusCode()).isEqualTo(403);
        assertThat(rows(List.of(correlation(recovered)))).hasSize(1);
    }

    /** covers: AC-6, AC-13 */
    @Test
    void aNewWindowOverHttpWritesAFreshTenRowsAndTheLogNeverRepeatsATenantSelector(CapturedOutput output)
            throws Exception {
        UUID identity = UUID.randomUUID();
        List<UUID> selectors = new ArrayList<>();
        List<UUID> firstWindow = new ArrayList<>();
        for (int request = 0; request < 13; request++) {
            selectors.add(UUID.randomUUID());
            firstWindow.add(correlation(get(identity, selectors.getLast())));
        }
        CLOCK.advance(WINDOW);
        List<UUID> secondWindow = new ArrayList<>();
        for (int request = 0; request < 12; request++) {
            selectors.add(UUID.randomUUID());
            secondWindow.add(correlation(get(identity, selectors.getLast())));
        }

        assertThat(rows(firstWindow)).hasSize(10);
        assertThat(rows(secondWindow)).as("the budget is full again after the window ended").hasSize(10);

        sweep.sweepOnce();
        CLOCK.advance(WINDOW);
        sweep.sweepOnce();

        assertThat(counts(firstWindow)).containsExactly(3);
        assertThat(counts(secondWindow)).containsExactly(2);
        // One line per key window names the identity; nothing the client chose is ever written to the log.
        assertThat(output.getAll().lines().filter(line -> line.contains("Authorization denial rows suppressed")
                && line.contains("identity=" + identity))).hasSize(2).allSatisfy(line -> assertThat(line)
                .contains("reason=NO_TENANT_CONTEXT", "permission=TENANT_READ", "cause=KEY_BUDGET"));
        assertThat(selectors).noneSatisfy(selector -> assertThat(output.getAll()).contains(selector.toString()));
    }

    private List<Object> counts(List<UUID> correlations) {
        return rows(correlations).stream().filter(row -> SUMMARY.equals(row.get("event_type")))
                .map(row -> row.get("suppressed_count")).toList();
    }

    private HttpResponse<String> get(UUID identity, UUID tenantSelector) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/customers"))
                .timeout(Duration.ofSeconds(30)).header("X-Test-Identity", identity.toString()).GET();
        if (tenantSelector != null) {
            request.header(TenantContextRequestFilter.TENANT_ID_HEADER, tenantSelector.toString());
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static UUID correlation(HttpResponse<String> answer) {
        return UUID.fromString(answer.headers().firstValue(AuditRequestFilter.CORRELATION_HEADER).orElseThrow());
    }

    /** Read as the database superuser: unattributed rows are hidden from the runtime role. */
    private List<Map<String, Object>> rows(List<UUID> correlations) {
        var database = TenantSecurityIntegrationFixture.POSTGRES;
        String ids = correlations.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
        return new JdbcTemplate(new DriverManagerDataSource(
                database.getJdbcUrl(), database.getUsername(), database.getPassword())).queryForList(
                "SELECT * FROM dokene.audit_events WHERE correlation_id = ANY (?::uuid[])", ids);
    }
}
