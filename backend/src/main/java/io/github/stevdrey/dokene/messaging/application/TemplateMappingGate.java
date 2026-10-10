// ABOUTME: Port that answers whether a template intent has an enabled provider mapping for the tenant.
// ABOUTME: Feature 6 replaces the stub bean; submit fails closed on anything but MAPPED.
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import java.util.Optional;

public interface TemplateMappingGate {
    TemplateMappingStatus check(SemanticTemplateIntent intent);

    /** The provider template the send pipeline hands to the adapter; empty is TEMPLATE_NOT_MAPPED (spec 0003). */
    Optional<ResolvedTemplate> resolve(SemanticTemplateIntent intent);
}
