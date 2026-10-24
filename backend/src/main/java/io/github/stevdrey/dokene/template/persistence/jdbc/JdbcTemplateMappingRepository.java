package io.github.stevdrey.dokene.template.persistence.jdbc;

import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.template.application.TemplateErrorCode;
import io.github.stevdrey.dokene.template.application.TemplateMappingRepository;
import io.github.stevdrey.dokene.template.application.TemplateRefusedException;
import io.github.stevdrey.dokene.template.domain.TemplateApprovalStatus;
import io.github.stevdrey.dokene.template.domain.TemplateButton;
import io.github.stevdrey.dokene.template.domain.TemplateButtonType;
import io.github.stevdrey.dokene.template.domain.TemplateContent;
import io.github.stevdrey.dokene.template.domain.TemplateParameterFormat;
import io.github.stevdrey.dokene.template.domain.TemplateMapping;
import io.github.stevdrey.dokene.template.domain.TemplateMapping.ApprovalEvidence;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Function;
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
                            approval_parameter_format = NULL, approval_parameter_checked_at = NULL,
                            approval_header_text = NULL, approval_body_text = NULL, approval_footer_text = NULL,
                            approval_button_types = NULL, approval_button_texts = NULL,
                            approval_button_values = NULL, approval_content_parameter_format = NULL,
                            approval_content_checked_at = NULL, approval_content_rules_version = NULL,
                            approval_content_rules_checked_at = NULL, updated_at = ?,
                            version = version + 1
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
    public boolean updateApproval(TemplateMapping mapping, long expectedVersion, ApprovalEvidence expected) {
        ApprovalEvidence evidence = mapping.approval();
        TemplateContent content = evidence.content();
        return jdbc.update("""
                UPDATE dokene.tenant_provider_templates
                SET approval_status = ?, approval_checked_at = ?, approval_checked_business_account_id = ?,
                    approval_checked_version = ?, approval_parameter_format = NULL,
                    approval_parameter_checked_at = NULL, approval_content_parameter_format = ?,
                    approval_header_text = ?, approval_body_text = ?, approval_footer_text = ?,
                    approval_button_types = ?, approval_button_texts = ?, approval_button_values = ?,
                    approval_content_checked_at = ?, approval_content_rules_version = ?,
                    approval_content_rules_checked_at = ?, updated_at = ?
                WHERE tenant_id = ? AND id = ? AND version = ?
                    AND approval_status = ? AND approval_checked_at IS NOT DISTINCT FROM ?
                    AND approval_checked_business_account_id IS NOT DISTINCT FROM ?
                    AND approval_checked_version IS NOT DISTINCT FROM ?
                """, evidence.status().name(), timestamp(evidence), evidence.checkedBusinessAccountId(),
                evidence.checkedVersion(), content == null ? null : format(evidence),
                content == null ? null : content.header(), content == null ? null : content.body(),
                content == null ? null : content.footer(),
                content == null ? null : buttonColumn(content, button -> button.type().name()),
                content == null ? null : buttonColumn(content, TemplateButton::text),
                content == null ? null : buttonColumn(content, TemplateButton::value),
                content == null ? null : timestamp(evidence),
                content == null ? null : TemplateMapping.FIXED_TEXT_RULES_VERSION,
                content == null ? null : timestamp(evidence),
                Timestamp.from(mapping.updatedAt()),
                mapping.tenantId().value(), mapping.id(), expectedVersion, expected.status().name(),
                timestamp(expected), expected.checkedBusinessAccountId(), expected.checkedVersion()) == 1;
    }

    /** One of the three aligned button arrays; a quick reply's value is a NULL element. */
    private static String[] buttonColumn(TemplateContent content, Function<TemplateButton, String> part) {
        return content.buttons().stream().map(part).toArray(String[]::new);
    }

    private static String format(ApprovalEvidence evidence) {
        return evidence.parameterFormat() == null ? null : evidence.parameterFormat().name();
    }

    private static Timestamp timestamp(ApprovalEvidence evidence) {
        return evidence.checkedAt() == null ? null : Timestamp.from(evidence.checkedAt());
    }

    /**
     * The V27 format, written by instances of the previous release only. A stored format counts only for approval
     * evidence written together with it. Evidence read this way carries no content, so it is never sendable.
     */
    private static String legacyFormat(ResultSet rs, Timestamp checkedAt) throws SQLException {
        Timestamp formatCheckedAt = rs.getTimestamp("approval_parameter_checked_at");
        boolean current = TemplateApprovalStatus.APPROVED.name().equals(rs.getString("approval_status"))
                && checkedAt != null && checkedAt.equals(formatCheckedAt);
        return current ? rs.getString("approval_parameter_format") : null;
    }

    /** The verified format and fixed content of current evidence, written together by this release (V31). */
    private record CurrentApproval(String format, TemplateContent content) {
    }

    /**
     * Content and its format count only for APPROVED evidence written together with them. An instance of the
     * previous release rewrites the evidence without touching these columns, so the check time no longer matches and
     * they are ignored: that approval is not sendable until a current instance refreshes it. Content safety-checked
     * under another version of the fixed-text rules (or by a release that recorded none, V32) is ignored the same way.
     */
    private static CurrentApproval currentApproval(ResultSet rs, Timestamp checkedAt) throws SQLException {
        String format = rs.getString("approval_content_parameter_format");
        String body = rs.getString("approval_body_text");
        if (!TemplateApprovalStatus.APPROVED.name().equals(rs.getString("approval_status")) || checkedAt == null
                || format == null || body == null || !checkedAt.equals(rs.getTimestamp("approval_content_checked_at"))
                || rs.getObject("approval_content_rules_version", Integer.class) == null
                || rs.getInt("approval_content_rules_version") != TemplateMapping.FIXED_TEXT_RULES_VERSION
                || !checkedAt.equals(rs.getTimestamp("approval_content_rules_checked_at"))) {
            return null;
        }
        return new CurrentApproval(format, new TemplateContent(rs.getString("approval_header_text"), body,
                rs.getString("approval_footer_text"), buttons(rs)));
    }

    private static List<TemplateButton> buttons(ResultSet rs) throws SQLException {
        String[] types = strings(rs.getArray("approval_button_types"));
        String[] texts = strings(rs.getArray("approval_button_texts"));
        String[] values = strings(rs.getArray("approval_button_values"));
        List<TemplateButton> buttons = new ArrayList<>(types.length);
        for (int i = 0; i < types.length; i++) {
            buttons.add(new TemplateButton(TemplateButtonType.valueOf(types[i]), texts[i], values[i]));
        }
        return buttons;
    }

    private static String[] strings(Array array) throws SQLException {
        return array == null ? new String[0] : (String[]) array.getArray();
    }

    private static TemplateMapping map(ResultSet rs) throws SQLException {
        Array names = rs.getArray("parameter_names");
        List<String> parameterNames = List.of((String[]) names.getArray());
        Timestamp checkedAt = rs.getTimestamp("approval_checked_at");
        Long checkedVersion = rs.getObject("approval_checked_version", Long.class);
        CurrentApproval current = currentApproval(rs, checkedAt);
        String format = current != null ? current.format() : legacyFormat(rs, checkedAt);
        ApprovalEvidence evidence = new ApprovalEvidence(
                TemplateApprovalStatus.valueOf(rs.getString("approval_status")),
                checkedAt == null ? null : checkedAt.toInstant(),
                rs.getString("approval_checked_business_account_id"), checkedVersion,
                format == null ? null : TemplateParameterFormat.valueOf(format),
                current == null ? null : current.content());
        return new TemplateMapping(rs.getObject("id", UUID.class),
                new TenantId(rs.getObject("tenant_id", UUID.class)), rs.getObject("integration_id", UUID.class),
                SemanticTemplateIntent.valueOf(rs.getString("semantic_intent")), rs.getString("provider_template_name"),
                rs.getString("language"), parameterNames, rs.getBoolean("enabled"), evidence,
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                rs.getLong("version"));
    }
}
