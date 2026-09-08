package io.github.stevdrey.dokene.purchase.api;

import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.purchase.application.PurchaseCursor;
import io.github.stevdrey.dokene.purchase.application.PurchaseEventCursor;
import io.github.stevdrey.dokene.purchase.application.PurchaseService;
import io.github.stevdrey.dokene.purchase.domain.Purchase;
import io.github.stevdrey.dokene.purchase.domain.PurchaseEvent;
import io.github.stevdrey.dokene.purchase.domain.PurchaseId;
import io.github.stevdrey.dokene.purchase.domain.PurchaseStatus;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/customers/{customerId}/purchases")
public class PurchaseController {
    private final PurchaseService service;

    public PurchaseController(PurchaseService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<PurchaseResponse> record(@PathVariable UUID customerId,
            @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestBody PurchaseRequest request) {
        requireRequest(request);
        var result = service.record(new CustomerId(customerId), request.purchasedAt(), request.description(), idempotencyKey);
        var response = response(result.purchase());
        if (!result.created()) return response;
        return ResponseEntity.created(URI.create("/api/customers/" + customerId + "/purchases/"
                        + result.purchase().id().value())).eTag(etag(result.purchase())).body(response.getBody());
    }

    @GetMapping("/{purchaseId}")
    public ResponseEntity<PurchaseResponse> get(@PathVariable UUID customerId, @PathVariable UUID purchaseId) {
        return response(service.get(new CustomerId(customerId), new PurchaseId(purchaseId)));
    }

    @GetMapping
    public PurchasePageResponse list(@PathVariable UUID customerId,
            @RequestParam(required = false) PurchaseStatus status,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "50") int limit) {
        var page = service.list(new CustomerId(customerId), status,
                cursor == null ? null : PurchaseCursor.decode(cursor), limit);
        return new PurchasePageResponse(page.purchases().stream().map(this::body).toList(), page.nextCursor());
    }

    @GetMapping("/last")
    public ResponseEntity<PurchaseResponse> last(@PathVariable UUID customerId) {
        return service.lastPurchase(new CustomerId(customerId)).map(this::response)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PutMapping("/{purchaseId}")
    public ResponseEntity<PurchaseResponse> correct(@PathVariable UUID customerId, @PathVariable UUID purchaseId,
            @RequestHeader("If-Match") String ifMatch, @RequestBody PurchaseRequest request) {
        requireRequest(request);
        return response(service.correct(new CustomerId(customerId), new PurchaseId(purchaseId),
                request.purchasedAt(), request.description(), parseVersion(ifMatch)));
    }

    @DeleteMapping("/{purchaseId}")
    public ResponseEntity<Void> voidPurchase(@PathVariable UUID customerId, @PathVariable UUID purchaseId,
            @RequestHeader("If-Match") String ifMatch) {
        service.voidPurchase(new CustomerId(customerId), new PurchaseId(purchaseId), parseVersion(ifMatch));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{purchaseId}/history")
    public HistoryResponse history(@PathVariable UUID customerId, @PathVariable UUID purchaseId,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "50") int limit) {
        var page = service.history(new CustomerId(customerId), new PurchaseId(purchaseId),
                cursor == null ? null : PurchaseEventCursor.decode(cursor), limit);
        return new HistoryResponse(page.events().stream().map(this::event).toList(), page.nextCursor());
    }

    private ResponseEntity<PurchaseResponse> response(Purchase purchase) {
        return ResponseEntity.ok().eTag(etag(purchase)).body(body(purchase));
    }
    private PurchaseResponse body(Purchase p) {
        return new PurchaseResponse(p.id().value(), p.customerId().value(), p.purchasedAt(), p.description(),
                p.status(), p.version(), p.createdAt(), p.updatedAt(), p.voidedAt());
    }
    private EventResponse event(PurchaseEvent event) {
        return new EventResponse(event.id(), event.type(), event.purchasedAt(), event.description(), event.occurredAt(),
                event.actorId().value(), event.membershipId().value(), event.purchaseVersion());
    }
    private static String etag(Purchase purchase) { return "\"" + purchase.version() + "\""; }
    private static void requireRequest(PurchaseRequest request) {
        if (request == null || request.purchasedAt() == null || request.description() == null) {
            throw new IllegalArgumentException("Purchase timestamp and description are required");
        }
    }
    private static long parseVersion(String value) {
        try {
            if (value == null || value.isBlank()) throw new IllegalArgumentException();
            String normalized = value.strip();
            if (normalized.startsWith("\"") && normalized.endsWith("\"") && normalized.length() >= 2) {
                normalized = normalized.substring(1, normalized.length() - 1);
            }
            long version = Long.parseLong(normalized);
            if (version < 0) throw new IllegalArgumentException();
            return version;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid purchase version");
        }
    }

    public record PurchaseRequest(Instant purchasedAt, String description) { }
    public record PurchaseResponse(UUID id, UUID customerId, Instant purchasedAt, String description,
                                   PurchaseStatus status, long version, Instant createdAt,
                                   Instant updatedAt, Instant voidedAt) { }
    public record PurchasePageResponse(List<PurchaseResponse> purchases, String nextCursor) { }
    public record EventResponse(UUID id, PurchaseEvent.Type type, Instant purchasedAt, String description,
                                Instant occurredAt, UUID actorId, UUID membershipId, long purchaseVersion) { }
    public record HistoryResponse(List<EventResponse> events, String nextCursor) { }
}
