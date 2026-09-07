package io.github.stevdrey.dokene.customer.persistence.jdbc;

import io.github.stevdrey.dokene.customer.application.CustomerConflictException;
import io.github.stevdrey.dokene.customer.application.CustomerRepository;
import io.github.stevdrey.dokene.customer.application.CustomerSearch;
import io.github.stevdrey.dokene.customer.domain.Customer;
import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.customer.domain.CustomerPhone;
import io.github.stevdrey.dokene.customer.domain.CustomerStatus;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCustomerRepository implements CustomerRepository {
    private final JdbcTemplate jdbc;

    public JdbcCustomerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Customer> findById(TenantId tenantId, CustomerId customerId) {
        List<CustomerRow> rows = jdbc.query("""
                SELECT * FROM dokene.customers WHERE tenant_id = ? AND id = ?
                """, this::mapCustomerRow, tenantId.value(), customerId.value());
        return rows.stream().findFirst().map(this::toDomain);
    }

    @Override
    public List<Customer> search(TenantId tenantId, CustomerSearch search, int fetchLimit) {
        StringBuilder sql = new StringBuilder("""
                SELECT DISTINCT c.* FROM dokene.customers c
                """);
        List<Object> arguments = new ArrayList<>();
        if (search.normalizedPhone() != null) {
            sql.append(" JOIN dokene.customer_phone_contacts p ON p.tenant_id = c.tenant_id AND p.customer_id = c.id ");
        }
        sql.append(" WHERE c.tenant_id = ? ");
        arguments.add(tenantId.value());
        if (search.status().customerStatus() != null) {
            sql.append(" AND c.status = ? ");
            arguments.add(search.status().customerStatus().name());
        }
        if (search.name() != null) {
            sql.append(" AND lower(c.display_name) LIKE ? ESCAPE '!' ");
            arguments.add("%" + escapeLike(search.name().toLowerCase(java.util.Locale.ROOT)) + "%");
        }
        if (search.normalizedPhone() != null) {
            sql.append(" AND p.normalized_phone = ? ");
            arguments.add(search.normalizedPhone());
        }
        if (search.cursor() != null) {
            sql.append(" AND (c.created_at, c.id) < (?, ?) ");
            arguments.add(Timestamp.from(search.cursor().createdAt()));
            arguments.add(search.cursor().id());
        }
        sql.append(" ORDER BY c.created_at DESC, c.id DESC LIMIT ? ");
        arguments.add(fetchLimit);
        List<CustomerRow> rows = jdbc.query(sql.toString(), this::mapCustomerRow, arguments.toArray());
        if (rows.isEmpty()) {
            return List.of();
        }
        List<UUID> customerIds = rows.stream().map(CustomerRow::id).toList();
        Map<UUID, List<CustomerPhone>> phonesByCustomer = findPhonesForCustomers(tenantId.value(), customerIds);
        return rows.stream()
                .map(row -> toDomain(row, phonesByCustomer.getOrDefault(row.id(), List.of())))
                .toList();
    }

    @Override
    public Customer insert(Customer customer) {
        try {
            jdbc.update("""
                    INSERT INTO dokene.customers
                        (id, tenant_id, display_name, notes, status, created_at, updated_at, archived_at, version)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, customer.id().value(), customer.tenantId().value(), customer.displayName(), customer.notes(),
                    customer.status().name(), Timestamp.from(customer.createdAt()), Timestamp.from(customer.updatedAt()),
                    customer.archivedAt() == null ? null : Timestamp.from(customer.archivedAt()), customer.version());
            insertPhones(customer);
            return customer;
        } catch (DataIntegrityViolationException exception) {
            throw new CustomerConflictException();
        }
    }

    @Override
    public Customer update(Customer customer, long expectedVersion) {
        try {
            int updated = jdbc.update("""
                    UPDATE dokene.customers
                    SET display_name = ?, notes = ?, status = ?, updated_at = ?, archived_at = ?, version = version + 1
                    WHERE tenant_id = ? AND id = ? AND version = ?
                    """, customer.displayName(), customer.notes(), customer.status().name(),
                    Timestamp.from(customer.updatedAt()), customer.archivedAt() == null ? null : Timestamp.from(customer.archivedAt()),
                    customer.tenantId().value(), customer.id().value(), expectedVersion);
            if (updated != 1) {
                throw new CustomerConflictException();
            }

            List<CustomerPhone> currentDbPhones = findPhones(customer.tenantId().value(), customer.id().value());
            Set<String> newNormalizedPhones = customer.phones().stream()
                    .map(CustomerPhone::e164)
                    .collect(Collectors.toSet());

            for (CustomerPhone oldPhone : currentDbPhones) {
                if (!newNormalizedPhones.contains(oldPhone.e164())) {
                    jdbc.update("DELETE FROM dokene.customer_phone_contacts WHERE tenant_id = ? AND customer_id = ? AND id = ?",
                            customer.tenantId().value(), customer.id().value(), oldPhone.id());
                }
            }

            jdbc.update("UPDATE dokene.customer_phone_contacts SET is_primary = false WHERE tenant_id = ? AND customer_id = ?",
                    customer.tenantId().value(), customer.id().value());

            Set<String> existingNormalizedPhones = currentDbPhones.stream()
                    .map(CustomerPhone::e164)
                    .collect(Collectors.toSet());

            for (CustomerPhone phone : customer.phones()) {
                if (existingNormalizedPhones.contains(phone.e164())) {
                    jdbc.update("""
                            UPDATE dokene.customer_phone_contacts
                            SET is_primary = ?
                            WHERE tenant_id = ? AND customer_id = ? AND id = ?
                            """, phone.primary(), customer.tenantId().value(), customer.id().value(), phone.id());
                } else {
                    jdbc.update("""
                            INSERT INTO dokene.customer_phone_contacts
                                (id, tenant_id, customer_id, normalized_phone, is_primary)
                            VALUES (?, ?, ?, ?, ?)
                            """, phone.id(), customer.tenantId().value(), customer.id().value(), phone.e164(), phone.primary());
                }
            }

            customer.synchronizeVersion(expectedVersion + 1);
            return customer;
        } catch (DataIntegrityViolationException exception) {
            throw new CustomerConflictException();
        }
    }

    @Override
    public Customer archive(Customer customer, long expectedVersion) {
        try {
            int updated = jdbc.update("""
                    UPDATE dokene.customers
                    SET status = 'ARCHIVED', updated_at = ?, archived_at = ?, version = version + 1
                    WHERE tenant_id = ? AND id = ? AND version = ?
                    """, Timestamp.from(customer.updatedAt()), Timestamp.from(customer.archivedAt()),
                    customer.tenantId().value(), customer.id().value(), expectedVersion);
            if (updated != 1) {
                throw new CustomerConflictException();
            }
            customer.synchronizeVersion(expectedVersion + 1);
            return customer;
        } catch (DataIntegrityViolationException exception) {
            throw new CustomerConflictException();
        }
    }

    private void insertPhones(Customer customer) {
        for (CustomerPhone phone : customer.phones()) {
            jdbc.update("""
                    INSERT INTO dokene.customer_phone_contacts
                        (id, tenant_id, customer_id, normalized_phone, is_primary)
                    VALUES (?, ?, ?, ?, ?)
                    """, phone.id(), customer.tenantId().value(), customer.id().value(), phone.e164(), phone.primary());
        }
    }

    private List<CustomerPhone> findPhones(UUID tenantId, UUID customerId) {
        return jdbc.query("""
                SELECT id, normalized_phone, is_primary FROM dokene.customer_phone_contacts
                WHERE tenant_id = ? AND customer_id = ? ORDER BY is_primary DESC, normalized_phone
                """, (result, index) -> new CustomerPhone(result.getObject("id", UUID.class),
                result.getString("normalized_phone"), result.getBoolean("is_primary")), tenantId, customerId);
    }

    private CustomerRow mapCustomerRow(ResultSet row, int index) throws SQLException {
        return new CustomerRow(row.getObject("id", UUID.class), row.getObject("tenant_id", UUID.class),
                row.getString("display_name"), row.getString("notes"), CustomerStatus.valueOf(row.getString("status")),
                row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant(),
                row.getTimestamp("archived_at") == null ? null : row.getTimestamp("archived_at").toInstant(),
                row.getLong("version"));
    }

    private Map<UUID, List<CustomerPhone>> findPhonesForCustomers(UUID tenantId, List<UUID> customerIds) {
        if (customerIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", Collections.nCopies(customerIds.size(), "?"));
        String sql = """
                SELECT customer_id, id, normalized_phone, is_primary
                FROM dokene.customer_phone_contacts
                WHERE tenant_id = ? AND customer_id IN (%s)
                ORDER BY is_primary DESC, normalized_phone
                """.formatted(placeholders);

        List<Object> args = new ArrayList<>(1 + customerIds.size());
        args.add(tenantId);
        args.addAll(customerIds);

        Map<UUID, List<CustomerPhone>> result = new LinkedHashMap<>();
        for (UUID id : customerIds) {
            result.put(id, new ArrayList<>());
        }
        jdbc.query(sql, rs -> {
            UUID customerId = rs.getObject("customer_id", UUID.class);
            UUID phoneId = rs.getObject("id", UUID.class);
            String normalizedPhone = rs.getString("normalized_phone");
            boolean isPrimary = rs.getBoolean("is_primary");
            result.get(customerId).add(new CustomerPhone(phoneId, normalizedPhone, isPrimary));
        }, args.toArray());
        return result;
    }

    private Customer toDomain(CustomerRow row) {
        return toDomain(row, findPhones(row.tenantId(), row.id()));
    }

    private Customer toDomain(CustomerRow row, List<CustomerPhone> phones) {
        return Customer.restore(new CustomerId(row.id()), new TenantId(row.tenantId()), row.displayName(), row.notes(),
                phones, row.status(), row.createdAt(), row.updatedAt(), row.archivedAt(), row.version());
    }

    private String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private record CustomerRow(UUID id, UUID tenantId, String displayName, String notes, CustomerStatus status,
                               java.time.Instant createdAt, java.time.Instant updatedAt,
                               java.time.Instant archivedAt, long version) {
    }
}
