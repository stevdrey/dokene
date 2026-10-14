package io.github.stevdrey.dokene.template.persistence.jdbc;

import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.template.application.TemplateErrorCode;
import io.github.stevdrey.dokene.template.application.TemplateMappingRepository;
import io.github.stevdrey.dokene.template.application.TemplateRefusedException;
import io.github.stevdrey.dokene.template.domain.TemplateApprovalStatus;
import io.github.stevdrey.dokene.template.domain.TemplateMapping;
import io.github.stevdrey.dokene.template.domain.TemplateMapping.ApprovalEvidence;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTemplateMappingRepository implements TemplateMappingRepository {
    private static final String TENANT_INTENT_CONSTRAINT = "uq_tenant_provider_templates_tenant_intent";

    private final JdbcTemplate jdbc;

    public JdbcTemplateMappingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<TemplateMapping> findAll(TenantId tenantId) {
        return jdbc.query("SELECT * FROM dokene.tenant_provider_templates WHERE tenant_id = ?",
                (rs, row) -> map(rs), tenantId.value());
    }

    @Override
    public Optional<TemplateMapping> find(TenantId tenantId, SemanticTemplateIntent intent) {
        return jdbc.query("""
                SELECT * FROM dokene.tenant_provider_templates WHERE tenant_id = ? AND semantic_intent = ?
                """, (rs, row) -> map(rs), tenantId.value(), intent.name()).stream().findFirst();
    }

    @Override
    public void insert(TemplateMapping mapping) {
        try {
            jdbc.update(con -> {
                var ps = con.prepareStatement("""
                        INSERT INTO dokene.tenant_provider_templates (id, tenant_id, integration_id, semantic_intent,
                            provider_template_name, language, parameter_names, enabled, created_at, updated_at, version)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """);
                ps.setObject(1, mapping.id());
                ps.setObject(2, mapping.tenantId().value());
                ps.setObject(3, mapping.integrationId());
                ps.setString(4, mapping.intent().name());
                ps.setString(5, mapping.providerTemplateName());
                ps.setString(6, mapping.language());
                ps.setArray(7, con.createArrayOf("text", mapping.parameterNames().toArray()));
                ps.setBoolean(8, mapping.enabled());
                ps.setTimestamp(9, Timestamp.from(mapping.createdAt()));
                ps.setTimestamp(10, Timestamp.from(mapping.updatedAt()));
                ps.setLong(11, mapping.version());
                return ps;
            });
        } catch (DataIntegrityViolationException conflict) {
            String cause = String.valueOf(conflict.getMostSpecificCause().getMessage());
            if (cause.contains(TENANT_INTENT_CONSTRAINT)) {
                throw new TemplateRefusedException(TemplateErrorCode.STALE_VERSION);
            }
            throw new TemplateRefusedException(TemplateErrorCode.INVALID_INPUT);
        }
    }

    @Override
    public OptionalLong updateMapping(TemplateMapping mapping, long expectedVersion) {
        try {
            return jdbc.query(con -> {
                var ps = con.prepareStatement("""
                        UPDATE dokene.tenant_provider_templates
                        SET integration_id = ?, provider_template_name = ?, language = ?, parameter_names = ?,
                            enabled = ?, approval_status = 'UNVERIFIED', approval_checked_at = NULL,
                            approval_checked_business_account_id = NULL, approval_checked_version = NULL,
                            updated_at = ?, version = version + 1
                        WHERE tenant_id = ? AND id = ? AND version = ?
                        RETURNING version
                        """);
                ps.setObject(1, mapping.integrationId());
                ps.setString(2, mapping.providerTemplateName());
                ps.setString(3, mapping.language());
                ps.setArray(4, con.createArrayOf("text", mapping.parameterNames().toArray()));
                ps.setBoolean(5, mapping.enabled());
                ps.setTimestamp(6, Timestamp.from(mapping.updatedAt()));
                ps.setObject(7, mapping.tenantId().value());
                ps.setObject(8, mapping.id());
                ps.setLong(9, expectedVersion);
                return ps;
            }, rs -> rs.next() ? OptionalLong.of(rs.getLong(1)) : OptionalLong.empty());
        } catch (DataIntegrityViolationException conflict) {
            throw new TemplateRefusedException(TemplateErrorCode.INVALID_INPUT);
        }
    }

    @Override
    public boolean updateApproval(TemplateMapping mapping, long expectedVersion) {
        ApprovalEvidence evidence = mapping.approval();
        return jdbc.update("""
                UPDATE dokene.tenant_provider_templates
                SET approval_status = ?, approval_checked_at = ?, approval_checked_business_account_id = ?,
                    approval_checked_version = ?, updated_at = ?
                WHERE tenant_id = ? AND id = ? AND version = ?
                """, evidence.status().name(),
                evidence.checkedAt() == null ? null : Timestamp.from(evidence.checkedAt()),
                evidence.checkedBusinessAccountId(), evidence.checkedVersion(),
                Timestamp.from(mapping.updatedAt()), mapping.tenantId().value(), mapping.id(), expectedVersion) == 1;
    }

    private static TemplateMapping map(ResultSet rs) throws SQLException {
        Array names = rs.getArray("parameter_names");
        List<String> parameterNames = List.of((String[]) names.getArray());
        Timestamp checkedAt = rs.getTimestamp("approval_checked_at");
        Long checkedVersion = rs.getObject("approval_checked_version", Long.class);
        ApprovalEvidence evidence = new ApprovalEvidence(
                TemplateApprovalStatus.valueOf(rs.getString("approval_status")),
                checkedAt == null ? null : checkedAt.toInstant(),
                rs.getString("approval_checked_business_account_id"), checkedVersion);
        return new TemplateMapping(rs.getObject("id", UUID.class),
                new TenantId(rs.getObject("tenant_id", UUID.class)), rs.getObject("integration_id", UUID.class),
                SemanticTemplateIntent.valueOf(rs.getString("semantic_intent")), rs.getString("provider_template_name"),
                rs.getString("language"), parameterNames, rs.getBoolean("enabled"), evidence,
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                rs.getLong("version"));
    }
}
