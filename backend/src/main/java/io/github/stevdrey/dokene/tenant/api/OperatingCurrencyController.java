// ABOUTME: The current tenant's operating currency setting (issue 452): read with TENANT_READ, changed with
// ABOUTME: TENANT_UPDATE under a strong If-Match on the tenant revision. Unset means the tenant may quote any currency.
package io.github.stevdrey.dokene.tenant.api;

import io.github.stevdrey.dokene.http.StrictRequests;
import io.github.stevdrey.dokene.tenant.application.OperatingCurrencyService;
import io.github.stevdrey.dokene.tenant.application.RequiredPermission;
import io.github.stevdrey.dokene.tenant.application.TenantAccessDeniedException;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/operating-currency")
public class OperatingCurrencyController {

    private static final String MEMBER = "operatingCurrency";

    private final OperatingCurrencyService service;

    public OperatingCurrencyController(OperatingCurrencyService service) {
        this.service = Objects.requireNonNull(service, "Operating currency service is required");
    }

    @GetMapping
    public ResponseEntity<OperatingCurrencyResponse> read() {
        return response(service.read());
    }

    /**
     * Replaces the setting. The body must be exactly {@code {"operatingCurrency": "USD"}}, or {@code null} to clear
     * it; an unknown member, a missing member or an unsupported code is {@code 400}, a stale If-Match {@code 409}.
     */
    @RequiredPermission(TenantPermission.TENANT_UPDATE)
    @PutMapping
    public ResponseEntity<OperatingCurrencyResponse> change(@RequestHeader("If-Match") String ifMatch,
            @RequestBody(required = false) JsonNode body) {
        long expectedRevision = StrictRequests.strongNumericVersion(ifMatch)
                .orElseThrow(() -> new IllegalArgumentException("If-Match must be one strong numeric ETag"));
        if (body == null || !body.isObject() || !body.has(MEMBER)) {
            throw new IllegalArgumentException("Operating currency is required");
        }
        OperatingCurrencyRequest request = StrictRequests.read(body, OperatingCurrencyRequest.class)
                .orElseThrow(() -> new IllegalArgumentException("Operating currency request is malformed"));
        Currency currency = request.operatingCurrency() == null ? null
                : Tenant.operatingCurrencyOf(request.operatingCurrency());
        return response(service.change(currency, expectedRevision));
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class,
            MissingRequestHeaderException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    void handleInvalidInput() {
    }

    @ExceptionHandler(OperatingCurrencyService.StaleRevisionException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    void handleStaleRevision() {
    }

    @ExceptionHandler(TenantAccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    void handleAccessDenied() {
    }

    private static ResponseEntity<OperatingCurrencyResponse> response(OperatingCurrencyService.Setting setting) {
        return ResponseEntity.ok().eTag(StrictRequests.strongEtag(setting.revision())).body(
                new OperatingCurrencyResponse(setting.operatingCurrency().map(Currency::getCurrencyCode).orElse(null),
                        Tenant.SUPPORTED_OPERATING_CURRENCIES));
    }

    public record OperatingCurrencyRequest(String operatingCurrency) {
    }

    /** {@code operatingCurrency} is null when unset; {@code supportedCurrencies} lists every code a change accepts. */
    public record OperatingCurrencyResponse(String operatingCurrency, List<String> supportedCurrencies) {
    }
}
