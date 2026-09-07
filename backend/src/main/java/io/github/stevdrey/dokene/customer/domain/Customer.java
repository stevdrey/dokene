package io.github.stevdrey.dokene.customer.domain;

import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantScopedResource;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public final class Customer implements TenantScopedResource {
    public static final int DISPLAY_NAME_MAX_LENGTH = 160;
    public static final int NOTES_MAX_LENGTH = 2_000;
    public static final int MAX_PHONES = 10;

    private final CustomerId id;
    private final TenantId tenantId;
    private String displayName;
    private String notes;
    private List<CustomerPhone> phones;
    private CustomerStatus status;
    private final Instant createdAt;
    private Instant updatedAt;
    private Instant archivedAt;
    private long version;

    private Customer(CustomerId id, TenantId tenantId, String displayName, String notes,
            List<CustomerPhone> phones, CustomerStatus status, Instant createdAt,
            Instant updatedAt, Instant archivedAt, long version) {
        this.id = Objects.requireNonNull(id, "Customer ID is required");
        this.tenantId = Objects.requireNonNull(tenantId, "Tenant ID is required");
        this.displayName = validateDisplayName(displayName);
        this.notes = validateNotes(notes);
        this.phones = validatePhones(phones);
        this.status = Objects.requireNonNull(status, "Customer status is required");
        this.createdAt = micros(createdAt);
        this.updatedAt = micros(updatedAt);
        this.archivedAt = archivedAt == null ? null : micros(archivedAt);
        if (this.updatedAt.isBefore(this.createdAt) || (this.archivedAt != null && this.archivedAt.isBefore(this.createdAt))) {
            throw new IllegalArgumentException("Customer timestamps are inconsistent");
        }
        if ((status == CustomerStatus.ARCHIVED) != (archivedAt != null) || version < 0) {
            throw new IllegalArgumentException("Customer state is inconsistent");
        }
        this.version = version;
    }

    public static Customer create(CustomerId id, TenantId tenantId, String displayName, String notes,
            List<CustomerPhone> phones, Instant now) {
        Instant timestamp = micros(now);
        return new Customer(id, tenantId, displayName, notes, phones, CustomerStatus.ACTIVE,
                timestamp, timestamp, null, 0);
    }

    public static Customer restore(CustomerId id, TenantId tenantId, String displayName, String notes,
            List<CustomerPhone> phones, CustomerStatus status, Instant createdAt,
            Instant updatedAt, Instant archivedAt, long version) {
        return new Customer(id, tenantId, displayName, notes, phones, status,
                createdAt, updatedAt, archivedAt, version);
    }

    public void update(String displayName, String notes, List<CustomerPhone> phones, Instant now) {
        if (status == CustomerStatus.ARCHIVED) {
            throw new IllegalStateException("Archived customers cannot be updated");
        }
        Instant timestamp = micros(now);
        if (timestamp.isBefore(updatedAt)) {
            throw new IllegalArgumentException("Update timestamp cannot move backwards");
        }
        this.displayName = validateDisplayName(displayName);
        this.notes = validateNotes(notes);
        this.phones = validatePhones(phones);
        this.updatedAt = timestamp;
    }

    public boolean archive(Instant now) {
        if (status == CustomerStatus.ARCHIVED) {
            return false;
        }
        Instant timestamp = micros(now);
        if (timestamp.isBefore(updatedAt)) {
            throw new IllegalArgumentException("Archive timestamp cannot move backwards");
        }
        status = CustomerStatus.ARCHIVED;
        updatedAt = timestamp;
        archivedAt = timestamp;
        return true;
    }

    public void synchronizeVersion(long newVersion) {
        if (newVersion < version) {
            throw new IllegalArgumentException("Version cannot move backwards");
        }
        version = newVersion;
    }

    private static String validateDisplayName(String value) {
        Objects.requireNonNull(value, "Display name is required");
        if (value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid customer display name");
        }
        validateUnicodeScalars(value);

        int start = 0;
        int end = value.length();
        while (start < end) {
            int codePoint = value.codePointAt(start);
            if (!isWhitespace(codePoint)) {
                break;
            }
            start += Character.charCount(codePoint);
        }
        while (start < end) {
            int codePoint = value.codePointBefore(end);
            if (!isWhitespace(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }

        String normalized = value.substring(start, end);
        if (normalized.isEmpty() || normalized.codePointCount(0, normalized.length()) > DISPLAY_NAME_MAX_LENGTH) {
            throw new IllegalArgumentException("Invalid customer display name");
        }
        return normalized;
    }

    private static boolean isWhitespace(int codePoint) {
        return codePoint == 0x0085 || Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static void validateUnicodeScalars(String value) {
        for (int index = 0; index < value.length(); index++) {
            char codeUnit = value.charAt(index);
            if (Character.isHighSurrogate(codeUnit)) {
                if (index + 1 == value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException("Invalid customer display name");
                }
                index++;
            } else if (Character.isLowSurrogate(codeUnit)) {
                throw new IllegalArgumentException("Invalid customer display name");
            }
        }
    }

    private static String validateNotes(String value) {
        if (value == null) {
            return null;
        }
        if (value.length() > NOTES_MAX_LENGTH || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid customer notes");
        }
        return value;
    }

    private static List<CustomerPhone> validatePhones(List<CustomerPhone> values) {
        Objects.requireNonNull(values, "Phones are required");
        List<CustomerPhone> copy = List.copyOf(values);
        if (copy.isEmpty() || copy.size() > MAX_PHONES || copy.stream().filter(CustomerPhone::primary).count() != 1) {
            throw new IllegalArgumentException("Customers require 1-10 phones and exactly one primary phone");
        }
        if (new HashSet<>(copy.stream().map(CustomerPhone::e164).toList()).size() != copy.size()) {
            throw new IllegalArgumentException("Customer phones must be unique");
        }
        return copy;
    }

    private static Instant micros(Instant value) {
        return Objects.requireNonNull(value, "Timestamp is required").truncatedTo(ChronoUnit.MICROS);
    }

    public CustomerId id() { return id; }
    @Override public TenantId tenantId() { return tenantId; }
    public String displayName() { return displayName; }
    public String notes() { return notes; }
    public List<CustomerPhone> phones() { return phones; }
    public CustomerStatus status() { return status; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public Instant archivedAt() { return archivedAt; }
    public long version() { return version; }
}
