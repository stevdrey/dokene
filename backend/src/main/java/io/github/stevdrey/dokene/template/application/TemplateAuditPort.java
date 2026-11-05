package io.github.stevdrey.dokene.template.application;

import java.util.UUID;

/** Typed audit for mapping changes; carries the mapping id and enabled flag only, never names or statuses. */
public interface TemplateAuditPort {
    void updated(UUID mappingId, boolean enabled);

    /**
     * An approval refresh was refused because the tenant's provider lookup budget is spent (issue 321). Durable in its
     * own transaction and attributed to the current member; carries no intent, template name or provider text.
     */
    void refreshThrottled();
}
