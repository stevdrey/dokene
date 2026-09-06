package io.github.stevdrey.dokene.customer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.customer.domain.CustomerStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CustomerSearchTest {

    @Test
    void parsesValidStatusesAndDefaultsToActive() {
        assertThat(CustomerSearch.Status.parse(null)).isEqualTo(CustomerSearch.Status.ACTIVE);
        assertThat(CustomerSearch.Status.parse("   ")).isEqualTo(CustomerSearch.Status.ACTIVE);
        assertThat(CustomerSearch.Status.parse("active")).isEqualTo(CustomerSearch.Status.ACTIVE);
        assertThat(CustomerSearch.Status.parse("ARCHIVED")).isEqualTo(CustomerSearch.Status.ARCHIVED);
        assertThat(CustomerSearch.Status.parse("all")).isEqualTo(CustomerSearch.Status.ALL);

        assertThat(CustomerSearch.Status.ACTIVE.customerStatus()).isEqualTo(CustomerStatus.ACTIVE);
        assertThat(CustomerSearch.Status.ARCHIVED.customerStatus()).isEqualTo(CustomerStatus.ARCHIVED);
        assertThat(CustomerSearch.Status.ALL.customerStatus()).isNull();
    }

    @Test
    void rejectsInvalidStatus() {
        assertThatThrownBy(() -> CustomerSearch.Status.parse("UNKNOWN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid customer status filter");
    }

    @Test
    void validatesLimitBounds() {
        new CustomerSearch(CustomerSearch.Status.ACTIVE, null, null, null, 1);
        new CustomerSearch(CustomerSearch.Status.ACTIVE, null, null, null, 50);
        new CustomerSearch(CustomerSearch.Status.ACTIVE, null, null, null, 100);

        assertThatThrownBy(() -> new CustomerSearch(CustomerSearch.Status.ACTIVE, null, null, null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid customer search");

        assertThatThrownBy(() -> new CustomerSearch(CustomerSearch.Status.ACTIVE, null, null, null, 101))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid customer search");

        assertThatThrownBy(() -> new CustomerSearch(null, null, null, null, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid customer search");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t", "\n"})
    void rejectsBlankNameFilter(String blankName) {
        assertThatThrownBy(() -> new CustomerSearch(CustomerSearch.Status.ACTIVE, blankName, null, null, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid customer name filter");
    }

    @Test
    void rejectsOverlongOrNullByteNameFilter() {
        assertThatThrownBy(() -> new CustomerSearch(CustomerSearch.Status.ACTIVE, "a".repeat(161), null, null, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid customer name filter");

        assertThatThrownBy(() -> new CustomerSearch(CustomerSearch.Status.ACTIVE, "bad\0name", null, null, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid customer name filter");
    }

    @Test
    void normalizesTrimmedNameFilter() {
        CustomerSearch search = new CustomerSearch(CustomerSearch.Status.ACTIVE, "  Ana Example  ", null, null, 10);
        assertThat(search.name()).isEqualTo("Ana Example");
    }
}
