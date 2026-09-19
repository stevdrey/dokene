package io.github.stevdrey.dokene.customer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.stevdrey.dokene.tenant.application.TenantAuthorizationService;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CustomerServiceTest {
    private CustomerRepository repository;
    private CustomerAuditPort audit;
    private TenantAuthorizationService authorization;
    private TenantContextProvider contexts;
    private PhoneNormalizer phoneNormalizer;
    private Clock clock;
    private CustomerService service;

    @BeforeEach
    void setUp() {
        repository = mock();
        audit = mock();
        authorization = mock();
        contexts = mock();
        phoneNormalizer = new PhoneNormalizer();
        clock = Clock.fixed(Instant.parse("2026-09-19T10:00:00Z"), ZoneOffset.UTC);

        TenantContext tenantContext = mock();
        when(tenantContext.tenantId()).thenReturn(TenantId.random());
        when(contexts.requireCurrent()).thenReturn(tenantContext);

        service = new CustomerService(repository, audit, authorization, contexts, phoneNormalizer, clock);
    }

    @Test
    void createThrowsCustomerValidationExceptionWhenPhoneIsInvalidForRegion() {
        var invalidPhones = List.of(
                new CustomerService.PhoneInput("123", "CL", true)
        );

        assertThatThrownBy(() -> service.create("Valentina Morales", null, invalidPhones))
                .isInstanceOf(CustomerValidationException.class)
                .satisfies(ex -> {
                    var cve = (CustomerValidationException) ex;
                    assertThat(cve.field()).isEqualTo("phones[0].number");
                    assertThat(cve.getMessage()).isEqualTo("Invalid phone number");
                });
    }

    @Test
    void createIdentifiesSecondPhoneWhenSecondPhoneFailsValidation() {
        var phones = List.of(
                new CustomerService.PhoneInput("984521190", "CL", true),
                new CustomerService.PhoneInput("123", "CL", false)
        );

        assertThatThrownBy(() -> service.create("Valentina Morales", null, phones))
                .isInstanceOf(CustomerValidationException.class)
                .satisfies(ex -> {
                    var cve = (CustomerValidationException) ex;
                    assertThat(cve.field()).isEqualTo("phones[1].number");
                    assertThat(cve.getMessage()).isEqualTo("Invalid phone number");
                });
    }
}
