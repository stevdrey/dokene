package io.github.stevdrey.dokene.customer.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CustomerTest {
    private final Instant now = Instant.parse("2026-09-06T12:00:00Z");

    @Test
    void createsUpdatesAndArchivesProfile() {
        Customer customer = customer(List.of(phone("+50688887777", true)));
        customer.update(" Updated ", "notes", List.of(phone("+14155552671", true)), now.plusSeconds(1));

        assertThat(customer.displayName()).isEqualTo("Updated");
        assertThat(customer.notes()).isEqualTo("notes");
        assertThat(customer.archive(now.plusSeconds(2))).isTrue();
        assertThat(customer.archive(now.plusSeconds(3))).isFalse();
        assertThat(customer.status()).isEqualTo(CustomerStatus.ARCHIVED);
        assertThatThrownBy(() -> customer.update("Again", null,
                List.of(phone("+50688887777", true)), now.plusSeconds(4))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void enforcesProfileAndContactInvariants() {
        assertThatThrownBy(() -> customer(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> customer(List.of(phone("+50688887777", false)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> customer(List.of(phone("+50688887777", true), phone("+50688887777", false))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> customer(java.util.stream.IntStream.range(0, 11)
                .mapToObj(index -> phone("+141555526" + String.format("%02d", index), index == 0)).toList()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Customer.create(new CustomerId(UUID.randomUUID()), TenantId.random(), " ", null,
                List.of(phone("+50688887777", true)), now)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Customer.create(new CustomerId(UUID.randomUUID()), TenantId.random(), "Name",
                "x".repeat(2001), List.of(phone("+50688887777", true)), now)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnicodeBlankNamesAndUnpairedSurrogates() {
        assertThatThrownBy(() -> Customer.create(new CustomerId(UUID.randomUUID()), TenantId.random(), "\u00A0", null,
                List.of(phone("+50688887777", true)), now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid customer display name");

        assertThatThrownBy(() -> Customer.create(new CustomerId(UUID.randomUUID()), TenantId.random(), "\u2000\u3000\u0085", null,
                List.of(phone("+50688887777", true)), now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid customer display name");

        assertThatThrownBy(() -> Customer.create(new CustomerId(UUID.randomUUID()), TenantId.random(), "Name\uD800", null,
                List.of(phone("+50688887777", true)), now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid customer display name");
    }

    @Test
    void trimsUnicodeWhitespaceFromDisplayName() {
        Customer c = Customer.create(new CustomerId(UUID.randomUUID()), TenantId.random(), "\u00A0Ana Example\u3000", null,
                List.of(phone("+50688887777", true)), now);
        assertThat(c.displayName()).isEqualTo("Ana Example");
    }

    private Customer customer(List<CustomerPhone> phones) {
        return Customer.create(new CustomerId(UUID.randomUUID()), TenantId.random(), "Customer", null, phones, now);
    }

    private CustomerPhone phone(String e164, boolean primary) {
        return new CustomerPhone(UUID.randomUUID(), e164, primary);
    }
}
