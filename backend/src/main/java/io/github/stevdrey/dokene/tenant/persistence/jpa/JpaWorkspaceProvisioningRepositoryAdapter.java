package io.github.stevdrey.dokene.tenant.persistence.jpa;

import io.github.stevdrey.dokene.tenant.application.DatabaseContextSigner;
import io.github.stevdrey.dokene.tenant.application.SignedDatabaseContext;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.WorkspaceProvisioningRecord;
import io.github.stevdrey.dokene.tenant.domain.WorkspaceProvisioningRepository;
import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * Provisioning records are reachable only through identity-verified security-definer functions: the lookup runs
 * before any tenant is bound, and the write additionally requires the new tenant's signed context.
 */
@Repository
public class JpaWorkspaceProvisioningRepositoryAdapter implements WorkspaceProvisioningRepository {

    private final EntityManager entityManager;
    private final DatabaseContextSigner databaseContextSigner;

    JpaWorkspaceProvisioningRepositoryAdapter(EntityManager entityManager, DatabaseContextSigner databaseContextSigner) {
        this.entityManager = entityManager;
        this.databaseContextSigner = databaseContextSigner;
    }

    @Override
    public void acquireIdempotencyLock(IdentityId identityId, String idempotencyKey) {
        entityManager.createNativeQuery("""
                        SELECT pg_advisory_xact_lock(
                            hashtext(CAST(:identityId AS text)),
                            hashtext(CAST(:idempotencyKey AS text))
                        )
                        """)
                .setParameter("identityId", identityId.value().toString())
                .setParameter("idempotencyKey", idempotencyKey)
                .getSingleResult();
    }

    @Override
    public Optional<WorkspaceProvisioningRecord> findByIdentityIdAndIdempotencyKey(IdentityId identityId, String idempotencyKey) {
        SignedDatabaseContext context = databaseContextSigner.issueIdentityContext(identityId);
        List<?> rows = entityManager.createNativeQuery("""
                        SELECT id, idempotency_key, identity_id, tenant_id, display_name, created_at
                        FROM dokene.find_workspace_provisioning_record(:payload, :signature, :idempotencyKey)
                        """, WorkspaceProvisioningEntity.class)
                .setParameter("payload", context.payload())
                .setParameter("signature", context.signature())
                .setParameter("idempotencyKey", idempotencyKey)
                .getResultList();
        return rows.stream()
                .map(WorkspaceProvisioningEntity.class::cast)
                .map(WorkspaceProvisioningEntity::toDomain)
                .findFirst();
    }

    @Override
    public WorkspaceProvisioningRecord save(WorkspaceProvisioningRecord record) {
        SignedDatabaseContext context = databaseContextSigner.issueIdentityContext(record.identityId());
        entityManager.createNativeQuery("""
                        SELECT 1
                        FROM dokene.record_workspace_provisioning(
                            :payload, :signature, :id, :idempotencyKey, :tenantId, :displayName, :createdAt
                        )
                        """)
                .setParameter("payload", context.payload())
                .setParameter("signature", context.signature())
                .setParameter("id", record.id())
                .setParameter("idempotencyKey", record.idempotencyKey())
                .setParameter("tenantId", record.tenantId().value())
                .setParameter("displayName", record.displayName())
                .setParameter("createdAt", OffsetDateTime.ofInstant(record.createdAt(), ZoneOffset.UTC))
                .getSingleResult();
        return record;
    }
}
