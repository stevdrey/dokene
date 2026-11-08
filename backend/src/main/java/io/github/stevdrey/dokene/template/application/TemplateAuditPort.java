package io.github.stevdrey.dokene.template.application;

import java.util.UUID;

/** Typed audit for mapping changes; carries the mapping id and enabled flag only, never names or statuses. */
public interface TemplateAuditPort {
    void updated(UUID mappingId, boolean enabled);

    /**
     * An approval refresh was refused because the tenant's or the member's provider request budget is spent (issue 321).
     * Durable in its own transaction, attributed to the current member, and reported at most once per member per
     * window; carries no intent, template name or provider text.
     */
    void refreshThrottled();
}
