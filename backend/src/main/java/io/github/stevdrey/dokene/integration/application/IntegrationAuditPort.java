package io.github.stevdrey.dokene.integration.application;

import java.util.UUID;

/** Typed audit for integration changes; carries the enabled flag only, never identifiers or secret references. */
public interface IntegrationAuditPort {
    void updated(UUID integrationId, boolean enabled);
}
