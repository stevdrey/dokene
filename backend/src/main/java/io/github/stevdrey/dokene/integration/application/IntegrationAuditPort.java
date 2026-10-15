package io.github.stevdrey.dokene.integration.application;

import java.util.UUID;

/** Typed audit for integration changes; carries the enabled flag only, never identifiers or secret references. */
public interface IntegrationAuditPort {
    void updated(UUID integrationId, boolean enabled);

    /**
     * A phone number already mapped by another tenant was refused. Durable in its own transaction, so it survives the
     * rejected write's rollback. Attributed to the current member; never carries the candidate phone number id.
     */
    void mappingRejected();

    /** A mapping change was refused because the rejection budget is spent; same durability and privacy. */
    void mappingThrottled();
}
