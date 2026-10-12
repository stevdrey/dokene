package io.github.stevdrey.dokene.tenant.persistence.jpa;

import io.github.stevdrey.dokene.tenant.application.DatabaseContextSigner;
import io.github.stevdrey.dokene.tenant.application.SignedDatabaseContext;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.WorkspaceProvisioningRecord;
import io.github.stevdrey.dokene.tenant.domain.WorkspaceProvisioningRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class JpaWorkspaceProvisioningRepositoryAdapter implements WorkspaceProvisioningRepository {

    private final SpringDataWorkspaceProvisioningRepository repository;
    private final EntityManager entityManager;
    private final DatabaseContextSigner databaseContextSigner;

    JpaWorkspaceProvisioningRepositoryAdapter(
            SpringDataWorkspaceProvisioningRepository repository,
            EntityManager entityManager,
            DatabaseContextSigner databaseContextSigner
    ) {
        this.repository = repository;
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

    /**
     * Looks up the caller's own record through the identity-scoped security-definer function, because no
     * tenant is bound yet and the table is otherwise visible only to its verified tenant.
     */
    @Override
    public Optional<WorkspaceProvisioningRecord> findByIdentityIdAndIdempotencyKey(IdentityId identityId, String idempotencyKey) {
        SignedDatabaseContext context = databaseContextSigner.issueIdentityContext(identityId);
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery("""
                        SELECT id, tenant_id, display_name, created_at
                        FROM dokene.find_workspace_provisioning_record(:payload, :signature, :idempotencyKey)
                        """, Tuple.class)
                .setParameter("payload", context.payload())
                .setParameter("signature", context.signature())
                .setParameter("idempotencyKey", idempotencyKey)
                .getResultList();
        return rows.stream().findFirst().map(row -> new WorkspaceProvisioningRecord(
                row.get("id", UUID.class),
                idempotencyKey,
                identityId,
                new TenantId(row.get("tenant_id", UUID.class)),
                row.get("display_name", String.class),
                toInstant(row.get("created_at"))
        ));
    }

    @Override
    public WorkspaceProvisioningRecord save(WorkspaceProvisioningRecord record) {
        return repository.saveAndFlush(WorkspaceProvisioningEntity.fromDomain(record)).toDomain();
    }

    private static Instant toInstant(Object value) {
        return switch (value) {
            case Instant instant -> instant;
            case OffsetDateTime offsetDateTime -> offsetDateTime.toInstant();
            case java.sql.Timestamp timestamp -> timestamp.toInstant();
            default -> throw new IllegalStateException("Unexpected provisioning timestamp type: " + value.getClass().getName());
        };
    }
}
