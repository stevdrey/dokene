package io.github.stevdrey.dokene.customer.application;

import io.github.stevdrey.dokene.customer.domain.Customer;
import java.util.List;

public record CustomerPage(List<Customer> customers, String nextCursor) {
    public CustomerPage {
        customers = List.copyOf(customers);
    }
}
