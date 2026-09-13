package io.github.stevdrey.dokene.customer.application;

import io.github.stevdrey.dokene.customer.domain.Customer;
import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.util.List;
import java.util.Optional;

public interface CustomerRepository {
    Optional<Customer> findById(TenantId tenantId, CustomerId customerId);
    Optional<Customer> findByIdForUpdate(TenantId tenantId, CustomerId customerId);
    List<Customer> search(TenantId tenantId, CustomerSearch search, int fetchLimit);
    Customer insert(Customer customer);
    Customer update(Customer customer, long expectedVersion);
    Customer archive(Customer customer, long expectedVersion);
}
