package io.github.stevdrey.dokene.customer.application;

import io.github.stevdrey.dokene.customer.domain.Customer;
import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.customer.domain.CustomerPhone;
import io.github.stevdrey.dokene.tenant.application.TenantAuthorizationService;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerService {
    private final CustomerRepository repository;
    private final CustomerAuditPort audit;
    private final TenantAuthorizationService authorization;
    private final TenantContextProvider contexts;
    private final PhoneNormalizer phoneNormalizer;
    private final Clock clock;

    public CustomerService(CustomerRepository repository, CustomerAuditPort audit,
            TenantAuthorizationService authorization, TenantContextProvider contexts,
            PhoneNormalizer phoneNormalizer, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "Customer repository is required");
        this.audit = Objects.requireNonNull(audit, "Customer audit port is required");
        this.authorization = Objects.requireNonNull(authorization, "Authorization is required");
        this.contexts = Objects.requireNonNull(contexts, "Tenant contexts are required");
        this.phoneNormalizer = Objects.requireNonNull(phoneNormalizer, "Phone normalizer is required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    @Transactional
    public Customer create(String displayName, String notes, List<PhoneInput> phones) {
        authorization.requirePermission(TenantPermission.CUSTOMER_WRITE);
        TenantId tenantId = contexts.requireCurrent().tenantId();
        Customer customer = Customer.create(new CustomerId(UUID.randomUUID()), tenantId,
                displayName, notes, normalize(List.of(), phones), clock.instant());
        repository.insert(customer);
        audit.created(customer.id());
        return customer;
    }

    @Transactional(readOnly = true)
    public Customer get(CustomerId id) {
        authorization.requirePermission(TenantPermission.CUSTOMER_READ);
        Customer customer = find(id);
        authorization.requireResourceAccess(TenantPermission.CUSTOMER_READ, customer);
        return customer;
    }

    @Transactional(readOnly = true)
    public CustomerPage search(CustomerSearch search) {
        authorization.requirePermission(TenantPermission.CUSTOMER_READ);
        TenantId tenantId = contexts.requireCurrent().tenantId();
        List<Customer> fetched = repository.search(tenantId, search, search.limit() + 1);
        boolean hasNext = fetched.size() > search.limit();
        List<Customer> page = hasNext ? List.copyOf(fetched.subList(0, search.limit())) : List.copyOf(fetched);
        String next = hasNext ? new CustomerCursor(page.getLast().createdAt(), page.getLast().id().value()).encode() : null;
        return new CustomerPage(page, next);
    }

    @Transactional
    public Customer update(CustomerId id, long expectedVersion, String displayName, String notes, List<PhoneInput> phones) {
        authorization.requirePermission(TenantPermission.CUSTOMER_WRITE);
        Customer customer = find(id);
        authorization.requireResourceAccess(TenantPermission.CUSTOMER_WRITE, customer);
        if (expectedVersion < 0 || customer.version() != expectedVersion) {
            throw new CustomerConflictException();
        }
        customer.update(displayName, notes, normalize(customer.phones(), phones), clock.instant());
        repository.update(customer, expectedVersion);
        audit.updated(customer.id());
        return customer;
    }

    @Transactional
    public void archive(CustomerId id, long expectedVersion) {
        authorization.requirePermission(TenantPermission.CUSTOMER_DELETE);
        Customer customer = find(id);
        authorization.requireResourceAccess(TenantPermission.CUSTOMER_DELETE, customer);
        if (expectedVersion < 0 || customer.version() != expectedVersion) {
            throw new CustomerConflictException();
        }
        if (!customer.archive(clock.instant())) {
            return;
        }
        repository.archive(customer, expectedVersion);
        audit.archived(customer.id());
    }

    private Customer find(CustomerId id) {
        Objects.requireNonNull(id, "Customer ID is required");
        return repository.findById(contexts.requireCurrent().tenantId(), id).orElseThrow(CustomerNotFoundException::new);
    }

    private List<CustomerPhone> normalize(List<CustomerPhone> existingPhones, List<PhoneInput> phones) {
        Objects.requireNonNull(phones, "Phones are required");
        Map<String, UUID> existingIdsByPhone = (existingPhones == null) ? Map.of() :
                existingPhones.stream().collect(Collectors.toMap(CustomerPhone::e164, CustomerPhone::id, (a, b) -> a));
        return phones.stream()
                .map(phone -> {
                    String normalized = phoneNormalizer.normalize(phone.number(), phone.region());
                    UUID phoneId = existingIdsByPhone.getOrDefault(normalized, UUID.randomUUID());
                    return new CustomerPhone(phoneId, normalized, phone.primary());
                })
                .toList();
    }

    public record PhoneInput(String number, String region, boolean primary) {
    }
}
