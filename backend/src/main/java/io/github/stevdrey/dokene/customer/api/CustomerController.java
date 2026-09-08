package io.github.stevdrey.dokene.customer.api;

import io.github.stevdrey.dokene.customer.application.CustomerCursor;
import io.github.stevdrey.dokene.customer.application.CustomerSearch;
import io.github.stevdrey.dokene.customer.application.CustomerService;
import io.github.stevdrey.dokene.customer.application.CustomerService.PhoneInput;
import io.github.stevdrey.dokene.customer.application.PhoneNormalizer;
import io.github.stevdrey.dokene.customer.domain.Customer;
import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.customer.domain.CustomerPhone;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/customers")
public class CustomerController {
    private final CustomerService customers;
    private final PhoneNormalizer phoneNormalizer;

    public CustomerController(CustomerService customers, PhoneNormalizer phoneNormalizer) {
        this.customers = customers;
        this.phoneNormalizer = phoneNormalizer;
    }

    @PostMapping
    public ResponseEntity<CustomerResponse> create(@RequestBody CustomerWriteRequest request) {
        Customer customer = customers.create(request.displayName(), request.notes(), inputs(request.phones()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag("\"" + customer.version() + "\"")
                .body(response(customer));
    }

    @GetMapping("/{customerId}")
    public ResponseEntity<CustomerResponse> get(@PathVariable UUID customerId) {
        Customer customer = customers.get(new CustomerId(customerId));
        return ResponseEntity.ok()
                .eTag("\"" + customer.version() + "\"")
                .body(response(customer));
    }

    @PutMapping("/{customerId}")
    public ResponseEntity<CustomerResponse> update(
            @PathVariable UUID customerId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody CustomerWriteRequest request
    ) {
        long version;
        if (ifMatch != null && !ifMatch.isBlank()) {
            version = parseVersion(ifMatch);
        } else if (request.version() != null) {
            version = request.version();
        } else {
            throw new IllegalArgumentException("Customer version is required");
        }
        Customer customer = customers.update(new CustomerId(customerId), version, request.displayName(),
                request.notes(), inputs(request.phones()));
        return ResponseEntity.ok()
                .eTag("\"" + customer.version() + "\"")
                .body(response(customer));
    }

    @DeleteMapping("/{customerId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(@PathVariable UUID customerId, @RequestHeader("If-Match") String ifMatch) {
        customers.archive(new CustomerId(customerId), parseVersion(ifMatch));
    }

    @GetMapping
    public CustomerPageResponse search(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit
    ) {
        if ((phone == null) != (region == null)) {
            throw new IllegalArgumentException("Phone and region must be supplied together");
        }
        String normalizedPhone = phone == null ? null : phoneNormalizer.normalize(phone, region);
        CustomerSearch query = new CustomerSearch(CustomerSearch.Status.parse(status), name, normalizedPhone,
                cursor == null ? null : CustomerCursor.decode(cursor), limit);
        var page = customers.search(query);
        return new CustomerPageResponse(page.customers().stream().map(this::response).toList(), page.nextCursor());
    }

    private List<PhoneInput> inputs(List<PhoneRequest> phones) {
        if (phones == null) {
            throw new IllegalArgumentException("Phones are required");
        }
        return phones.stream().map(phone -> new PhoneInput(phone.number(), phone.region(), phone.primary())).toList();
    }

    private long parseVersion(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("If-Match is required");
        }
        String unquoted = value.strip();
        if (unquoted.length() >= 2 && unquoted.startsWith("\"") && unquoted.endsWith("\"")) {
            unquoted = unquoted.substring(1, unquoted.length() - 1);
        }
        try {
            long version = Long.parseLong(unquoted);
            if (version < 0) {
                throw new IllegalArgumentException("Invalid customer version");
            }
            return version;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid customer version");
        }
    }

    private CustomerResponse response(Customer customer) {
        return new CustomerResponse(customer.id().value(), customer.displayName(), customer.notes(),
                customer.phones().stream().map(this::response).toList(), customer.status().name(), customer.version(),
                customer.createdAt(), customer.updatedAt(), customer.archivedAt());
    }

    private PhoneResponse response(CustomerPhone phone) {
        return new PhoneResponse(phone.id(), phone.e164(), phone.primary());
    }

    public record PhoneRequest(String number, String region, boolean primary) { }
    public record CustomerWriteRequest(String displayName, String notes, List<PhoneRequest> phones, Long version) { }
    public record PhoneResponse(UUID id, String e164, boolean primary) { }
    public record CustomerResponse(UUID id, String displayName, String notes, List<PhoneResponse> phones,
                                   String status, long version, Instant createdAt, Instant updatedAt, Instant archivedAt) { }
    public record CustomerPageResponse(List<CustomerResponse> customers, String nextCursor) { }
}
