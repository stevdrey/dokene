package io.github.stevdrey.dokene.integration.application;

import java.util.UUID;

/** Typed audit for integration changes; carries the enabled flag only, never identifiers or secret references. */
public interface IntegrationAuditPort {
    void updated(UUID integrationId, boolean enabled);

    /**
     * Durable in its own transaction, so it survives the rejected request's rollback. Attributed to the current
     * member; never carries the candidate phone number id.
     */
    void mappingThrottled();
}
