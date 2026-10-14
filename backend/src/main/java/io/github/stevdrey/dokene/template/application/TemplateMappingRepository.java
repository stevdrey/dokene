package io.github.stevdrey.dokene.template.application;

import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.template.domain.TemplateMapping;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

public interface TemplateMappingRepository {
    List<TemplateMapping> findAll(TenantId tenantId);

    Optional<TemplateMapping> find(TenantId tenantId, SemanticTemplateIntent intent);

    /** Throws {@link TemplateRefusedException} {@code STALE_VERSION} when the intent is already mapped. */
    void insert(TemplateMapping mapping);

    /** Replaces the editable fields and drops evidence; advances the version. Empty when the version moved. */
    OptionalLong updateMapping(TemplateMapping mapping, long expectedVersion);

    /** Stores provider evidence without advancing the version. False when the version moved. */
    boolean updateApproval(TemplateMapping mapping, long expectedVersion);
}
