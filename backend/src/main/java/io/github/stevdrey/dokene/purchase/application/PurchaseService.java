package io.github.stevdrey.dokene.purchase.application;

import io.github.stevdrey.dokene.customer.application.CustomerNotFoundException;
import io.github.stevdrey.dokene.customer.application.CustomerRepository;
import io.github.stevdrey.dokene.customer.domain.Customer;
import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.customer.domain.CustomerStatus;
import io.github.stevdrey.dokene.purchase.domain.Purchase;
import io.github.stevdrey.dokene.purchase.domain.PurchaseEvent;
import io.github.stevdrey.dokene.purchase.domain.PurchaseId;
import io.github.stevdrey.dokene.purchase.domain.PurchaseStatus;
import io.github.stevdrey.dokene.tenant.application.TenantAuthorizationService;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PurchaseService {
    private final CustomerRepository customers;
    private final PurchaseRepository purchases;
    private final PurchaseAuditPort audit;
    private final TenantAuthorizationService authorization;
    private final TenantContextProvider contexts;
    private final Clock clock;

    public PurchaseService(CustomerRepository customers, PurchaseRepository purchases, PurchaseAuditPort audit,
            TenantAuthorizationService authorization, TenantContextProvider contexts, Clock clock) {
        this.customers = customers;
        this.purchases = purchases;
        this.audit = audit;
        this.authorization = authorization;
        this.contexts = contexts;
        this.clock = clock;
    }

    @Transactional
    public RecordResult record(CustomerId customerId, Instant purchasedAt, String description, String idempotencyKey) {
        Customer customer = requireCustomerForUpdate(customerId, TenantPermission.PURCHASE_WRITE);
        Instant purchaseTime = validatePurchasedAt(purchasedAt);
        String key = validateKey(idempotencyKey);
        Purchase candidate = Purchase.create(new PurchaseId(UUID.randomUUID()), customer.tenantId(), customer.id(),
                purchaseTime, description, clock.instant());
        TenantContext actor = contexts.requireCurrent();
        PurchaseRepository.CreateResult result = purchases.insert(candidate, key,
                fingerprint(customer.id(), candidate.purchasedAt(), candidate.description()), actor);
        if (!result.matchingRequest()) throw new PurchaseConflictException();
        if (customer.status() == CustomerStatus.ARCHIVED && result.created()) {
            throw new IllegalStateException("Archived customer purchases cannot be recorded");
        }
        if (result.created()) audit.recorded(result.purchase().id());
        return new RecordResult(result.purchase(), result.created());
    }

    @Transactional(readOnly = true)
    public Purchase get(CustomerId customerId, PurchaseId purchaseId) {
        Customer customer = requireCustomer(customerId, TenantPermission.PURCHASE_READ);
        Purchase purchase = find(customer, purchaseId);
        authorization.requireResourceAccess(TenantPermission.PURCHASE_READ, purchase);
        return purchase;
    }

    @Transactional(readOnly = true)
    public PurchasePage list(CustomerId customerId, PurchaseStatus status, PurchaseCursor before, int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Purchase limit must be between 1 and 100");
        Customer customer = requireCustomer(customerId, TenantPermission.PURCHASE_READ);
        var fetched = purchases.list(customer.tenantId(), customer.id(), status, before, limit + 1);
        boolean hasNext = fetched.size() > limit;
        var page = hasNext ? fetched.subList(0, limit) : fetched;
        String next = hasNext ? new PurchaseCursor(page.getLast().purchasedAt(), page.getLast().id().value()).encode() : null;
        return new PurchasePage(page, next);
    }

    @Transactional(readOnly = true)
    public Optional<Purchase> lastPurchase(CustomerId customerId) {
        Customer customer = requireCustomer(customerId, TenantPermission.PURCHASE_READ);
        return purchases.lastValid(customer.tenantId(), customer.id());
    }

    @Transactional
    public Purchase correct(CustomerId customerId, PurchaseId purchaseId, Instant purchasedAt,
            String description, long expectedVersion) {
        Customer customer = requireCustomerForUpdate(customerId, TenantPermission.PURCHASE_WRITE);
        Purchase purchase = find(customer, purchaseId);
        authorization.requireResourceAccess(TenantPermission.PURCHASE_WRITE, purchase);
        requireVersion(purchase, expectedVersion);
        purchase.correct(validatePurchasedAt(purchasedAt), description, clock.instant());
        purchases.update(purchase, expectedVersion, PurchaseEvent.Type.CORRECTED, contexts.requireCurrent());
        audit.corrected(purchase.id());
        return purchase;
    }

    @Transactional
    public void voidPurchase(CustomerId customerId, PurchaseId purchaseId, long expectedVersion) {
        Customer customer = requireCustomerForUpdate(customerId, TenantPermission.PURCHASE_WRITE);
        Purchase purchase = find(customer, purchaseId);
        authorization.requireResourceAccess(TenantPermission.PURCHASE_WRITE, purchase);
        requireVersion(purchase, expectedVersion);
        if (!purchase.voidPurchase(clock.instant())) return;
        purchases.update(purchase, expectedVersion, PurchaseEvent.Type.VOIDED, contexts.requireCurrent());
        audit.voided(purchase.id());
    }

    @Transactional(readOnly = true)
    public PurchaseEventPage history(CustomerId customerId, PurchaseId purchaseId,
            PurchaseEventCursor before, int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("History limit must be between 1 and 100");
        Purchase purchase = get(customerId, purchaseId);
        var fetched = purchases.history(purchase, before, limit + 1);
        boolean hasNext = fetched.size() > limit;
        var page = hasNext ? fetched.subList(0, limit) : fetched;
        String next = hasNext ? new PurchaseEventCursor(page.getLast().occurredAt(), page.getLast().id()).encode() : null;
        return new PurchaseEventPage(page, next);
    }

    private Customer requireCustomer(CustomerId id, TenantPermission permission) {
        authorization.requirePermission(permission);
        Customer customer = customers.findById(contexts.requireCurrent().tenantId(), Objects.requireNonNull(id))
                .orElseThrow(CustomerNotFoundException::new);
        authorization.requireResourceAccess(permission, customer);
        return customer;
    }

    private Customer requireCustomerForUpdate(CustomerId id, TenantPermission permission) {
        authorization.requirePermission(permission);
        Customer customer = customers.findByIdForUpdate(contexts.requireCurrent().tenantId(),
                Objects.requireNonNull(id)).orElseThrow(CustomerNotFoundException::new);
        authorization.requireResourceAccess(permission, customer);
        return customer;
    }

    private Purchase find(Customer customer, PurchaseId id) {
        return purchases.findById(customer.tenantId(), customer.id(), Objects.requireNonNull(id))
                .orElseThrow(PurchaseNotFoundException::new);
    }

    private Instant validatePurchasedAt(Instant value) {
        Instant timestamp = Objects.requireNonNull(value, "Purchase timestamp is required").truncatedTo(ChronoUnit.MICROS);
        if (timestamp.isAfter(clock.instant())) throw new IllegalArgumentException("Purchase timestamp cannot be in the future");
        return timestamp;
    }

    private static String validateKey(String value) {
        if (value == null || value.isBlank() || value.length() > 128 || !value.matches("[A-Za-z0-9._:-]+")) {
            throw new IllegalArgumentException("Invalid idempotency key");
        }
        return value;
    }

    private static String fingerprint(CustomerId customerId, Instant purchasedAt, String description) {
        try {
            String canonical = customerId.value() + "\n" + purchasedAt + "\n" + description;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static void requireVersion(Purchase purchase, long expected) {
        if (expected < 0 || purchase.version() != expected) throw new PurchaseConflictException();
    }

    public record RecordResult(Purchase purchase, boolean created) { }
}
