package io.github.stevdrey.dokene.integration.application;

import java.util.UUID;

/** Typed audit for integration changes; carries the enabled flag only, never identifiers or secret references. */
public interface IntegrationAuditPort {
    void updated(UUID integrationId, boolean enabled);

    /**
     * An ownership proof was refused: not proven by the provider, or the number is verified by another tenant. Durable in
     * its own transaction, so it survives the rejected write's rollback. Attributed to the current member; never carries the candidate phone number id.
     */
    void mappingRejected();

    /** An ownership verification was refused because the rejection budget is spent; same durability and privacy. */
    void mappingThrottled();
}
