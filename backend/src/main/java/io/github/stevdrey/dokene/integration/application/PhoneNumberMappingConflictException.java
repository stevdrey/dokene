package io.github.stevdrey.dokene.integration.application;

import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;

/**
 * The phone number id is already mapped by another tenant. Answered exactly like any invalid mapping; the distinct
 * type only lets the service count the attempt against the rejection budget.
 */
public class PhoneNumberMappingConflictException extends IntegrationRefusedException {
    public PhoneNumberMappingConflictException() {
        super(MessagingErrorCode.INVALID_INPUT);
    }
}
