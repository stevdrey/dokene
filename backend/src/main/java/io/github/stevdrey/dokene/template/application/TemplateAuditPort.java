package io.github.stevdrey.dokene.template.application;

import java.util.UUID;

/** Typed audit for mapping changes; carries the mapping id and enabled flag only, never names or statuses. */
public interface TemplateAuditPort {
    void updated(UUID mappingId, boolean enabled);
}
