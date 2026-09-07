package io.github.stevdrey.dokene.customer.persistence.jdbc;

import io.github.stevdrey.dokene.customer.application.ContactPolicyCursor;
import io.github.stevdrey.dokene.customer.application.ContactPolicyRepository;
import io.github.stevdrey.dokene.customer.application.CustomerConflictException;
import io.github.stevdrey.dokene.customer.domain.ContactChannel;
import io.github.stevdrey.dokene.customer.domain.ContactConsent;
import io.github.stevdrey.dokene.customer.domain.ContactIntentSource;
import io.github.stevdrey.dokene.customer.domain.ContactPolicy;
import io.github.stevdrey.dokene.customer.domain.ContactPolicyEvent;
import io.github.stevdrey.dokene.customer.domain.ConsentStatus;
import io.github.stevdrey.dokene.customer.domain.Customer;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcContactPolicyRepository implements ContactPolicyRepository {
    private final JdbcTemplate jdbc;

    public JdbcContactPolicyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ContactPolicy find(Customer customer) {
        long version = jdbc.queryForObject("SELECT contact_policy_version FROM dokene.customers WHERE tenant_id = ? AND id = ?",
                Long.class, customer.tenantId().value(), customer.id().value());
        Map<UUID, ContactConsent> current = jdbc.query("""
                SELECT phone_contact_id, channel, status, source, changed_at
                FROM dokene.customer_contact_consents WHERE tenant_id = ? AND customer_id = ?
                """, this::mapConsent, customer.tenantId().value(), customer.id().value()).stream()
                .collect(Collectors.toMap(ContactConsent::contactId, Function.identity()));
        List<ContactConsent> consents = customer.phones().stream()
                .map(phone -> current.getOrDefault(phone.id(), ContactConsent.unknown(phone.id(), ContactChannel.WHATSAPP)))
                .toList();
        var dnc = jdbc.query("""
                SELECT enabled, source, changed_at FROM dokene.customer_do_not_contact
                WHERE tenant_id = ? AND customer_id = ?
                """, (row, index) -> new DoNotContactRow(row.getBoolean("enabled"),
                    ContactIntentSource.valueOf(row.getString("source")), row.getTimestamp("changed_at").toInstant()),
                customer.tenantId().value(), customer.id().value()).stream().findFirst().orElse(null);
        return new ContactPolicy(customer.id(), version, dnc != null && dnc.enabled(),
                dnc == null ? null : dnc.source(), dnc == null ? null : dnc.changedAt(), consents);
    }

    @Override
    public long changeConsent(Customer customer, UUID contactId, ContactChannel channel, ConsentStatus status,
            ContactIntentSource source, long expectedVersion, TenantContext actor, Instant occurredAt) {
        long nextVersion = incrementVersion(customer, expectedVersion);
        Instant timestamp = micros(occurredAt);
        jdbc.update("""
                INSERT INTO dokene.customer_contact_consents
                    (tenant_id, customer_id, phone_contact_id, channel, status, source, changed_at, actor_id, membership_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, customer_id, phone_contact_id, channel) DO UPDATE
                SET status = EXCLUDED.status, source = EXCLUDED.source, changed_at = EXCLUDED.changed_at,
                    actor_id = EXCLUDED.actor_id, membership_id = EXCLUDED.membership_id
                """, customer.tenantId().value(), customer.id().value(), contactId, channel.name(), status.name(),
                source.name(), Timestamp.from(timestamp), actor.identityId().value(), actor.membershipId().value());
        jdbc.update("""
                INSERT INTO dokene.customer_consent_history
                    (id, tenant_id, customer_id, phone_contact_id, channel, status, source, occurred_at, actor_id, membership_id, policy_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), customer.tenantId().value(), customer.id().value(), contactId, channel.name(),
                status.name(), source.name(), Timestamp.from(timestamp), actor.identityId().value(),
                actor.membershipId().value(), nextVersion);
        return nextVersion;
    }

    @Override
    public long changeDoNotContact(Customer customer, boolean enabled, ContactIntentSource source,
            long expectedVersion, TenantContext actor, Instant occurredAt) {
        long nextVersion = incrementVersion(customer, expectedVersion);
        Instant timestamp = micros(occurredAt);
        jdbc.update("""
                INSERT INTO dokene.customer_do_not_contact
                    (tenant_id, customer_id, enabled, source, changed_at, actor_id, membership_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, customer_id) DO UPDATE
                SET enabled = EXCLUDED.enabled, source = EXCLUDED.source, changed_at = EXCLUDED.changed_at,
                    actor_id = EXCLUDED.actor_id, membership_id = EXCLUDED.membership_id
                """, customer.tenantId().value(), customer.id().value(), enabled, source.name(), Timestamp.from(timestamp),
                actor.identityId().value(), actor.membershipId().value());
        jdbc.update("""
                INSERT INTO dokene.customer_do_not_contact_history
                    (id, tenant_id, customer_id, enabled, source, occurred_at, actor_id, membership_id, policy_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), customer.tenantId().value(), customer.id().value(), enabled, source.name(),
                Timestamp.from(timestamp), actor.identityId().value(), actor.membershipId().value(), nextVersion);
        return nextVersion;
    }

    @Override
    public List<ContactPolicyEvent> history(Customer customer, ContactPolicyCursor before, int fetchLimit) {
        var arguments = new ArrayList<Object>();
        arguments.add(customer.tenantId().value());
        arguments.add(customer.id().value());
        String boundary = "";
        if (before != null) {
            boundary = " AND (occurred_at, id) < (?, ?) ";
            arguments.add(Timestamp.from(before.occurredAt()));
            arguments.add(before.id());
        }
        arguments.add(fetchLimit);
        String sql = """
                SELECT * FROM (
                    SELECT id, 'CONSENT_CHANGED' AS event_type, phone_contact_id, channel, status,
                           NULL::boolean AS do_not_contact, source, occurred_at, actor_id, membership_id, policy_version
                    FROM dokene.customer_consent_history WHERE tenant_id = ? AND customer_id = ?
                    UNION ALL
                    SELECT id, 'DO_NOT_CONTACT_CHANGED' AS event_type, NULL::uuid, NULL::varchar, NULL::varchar,
                           enabled, source, occurred_at, actor_id, membership_id, policy_version
                    FROM dokene.customer_do_not_contact_history WHERE tenant_id = ? AND customer_id = ?
                ) history WHERE 1 = 1
                """ + boundary + " ORDER BY occurred_at DESC, id DESC LIMIT ?";
        return jdbc.query(sql, this::mapEvent, historyArguments(arguments).toArray());
    }

    private List<Object> historyArguments(List<Object> base) {
        List<Object> result = new ArrayList<>();
        result.add(base.get(0)); result.add(base.get(1));
        result.add(base.get(0)); result.add(base.get(1));
        result.addAll(base.subList(2, base.size()));
        return result;
    }

    private long incrementVersion(Customer customer, long expectedVersion) {
        if (expectedVersion < 0) throw new CustomerConflictException();
        final long nextVersion;
        try {
            nextVersion = Math.incrementExact(expectedVersion);
        } catch (ArithmeticException exception) {
            throw new CustomerConflictException();
        }
        int updated = jdbc.update("""
                UPDATE dokene.customers SET contact_policy_version = ?
                WHERE tenant_id = ? AND id = ? AND contact_policy_version = ?
                """, nextVersion, customer.tenantId().value(), customer.id().value(), expectedVersion);
        if (updated != 1) throw new CustomerConflictException();
        return nextVersion;
    }

    private ContactConsent mapConsent(ResultSet row, int index) throws SQLException {
        return new ContactConsent(row.getObject("phone_contact_id", UUID.class),
                ContactChannel.valueOf(row.getString("channel")), ConsentStatus.valueOf(row.getString("status")),
                ContactIntentSource.valueOf(row.getString("source")), row.getTimestamp("changed_at").toInstant());
    }

    private ContactPolicyEvent mapEvent(ResultSet row, int index) throws SQLException {
        var type = ContactPolicyEvent.Type.valueOf(row.getString("event_type"));
        return new ContactPolicyEvent(row.getObject("id", UUID.class), type,
                row.getObject("phone_contact_id", UUID.class),
                row.getString("channel") == null ? null : ContactChannel.valueOf(row.getString("channel")),
                row.getString("status") == null ? null : ConsentStatus.valueOf(row.getString("status")),
                row.getObject("do_not_contact", Boolean.class), ContactIntentSource.valueOf(row.getString("source")),
                row.getTimestamp("occurred_at").toInstant(), new IdentityId(row.getObject("actor_id", UUID.class)),
                new TenantMembershipId(row.getObject("membership_id", UUID.class)), row.getLong("policy_version"));
    }

    private Instant micros(Instant value) {
        return value.truncatedTo(ChronoUnit.MICROS);
    }

    private record DoNotContactRow(boolean enabled, ContactIntentSource source, Instant changedAt) { }
}
