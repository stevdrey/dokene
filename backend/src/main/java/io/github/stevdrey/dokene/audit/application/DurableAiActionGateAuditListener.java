package io.github.stevdrey.dokene.audit.application;

import io.github.stevdrey.dokene.audit.domain.AuditDenialReason;
import io.github.stevdrey.dokene.followup.application.ActionGateRejectionReason;
import io.github.stevdrey.dokene.followup.application.AiActionGateAuditListener;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Durable implementation of {@link AiActionGateAuditListener} that persists
 * security-relevant AI Action Gate rejections to the append-only audit trail and logs structured diagnostics.
 */
@Component
public class DurableAiActionGateAuditListener implements AiActionGateAuditListener {

    private static final Logger log = LoggerFactory.getLogger(DurableAiActionGateAuditListener.class);
    private final AuditRecorder recorder;

    public DurableAiActionGateAuditListener(AuditRecorder recorder) {
        this.recorder = Objects.requireNonNull(recorder, "Audit recorder is required");
    }

    @Override
    public void onSecurityRejection(SecurityRejectionEvent event) {
        Objects.requireNonNull(event, "Security rejection event is required");

        // 1. Structured logging (safe fields only, never prompts or customer content)
        log.warn("AI Action Gate rejected advisory recommendation: tenantId={}, actorId={}, customerId={}, reason={}, diagnosticCode={}, timestamp={}",
                event.tenantId() != null ? event.tenantId().value() : null,
                event.actorId() != null ? event.actorId().value() : null,
                event.customerId() != null ? event.customerId().value() : null,
                event.reason(),
                event.diagnosticCode(),
                event.timestamp());

        // 2. Persist denial to the durable append-only audit trail
        AuditDenialReason denialReason = mapDenialReason(event.reason());
        recorder.authorizationDenied(TenantPermission.FOLLOWUP_EVALUATE, denialReason);
    }

    private AuditDenialReason mapDenialReason(ActionGateRejectionReason reason) {
        return switch (reason) {
            case NO_TENANT_CONTEXT -> AuditDenialReason.NO_TENANT_CONTEXT;
            case UNAUTHORIZED -> AuditDenialReason.MISSING_PERMISSION;
            case CUSTOMER_NOT_FOUND -> AuditDenialReason.CROSS_TENANT_RESOURCE;
            case CUSTOMER_ARCHIVED, DO_NOT_CONTACT, NO_CONTACT_CONSENT -> AuditDenialReason.INSUFFICIENT_PERMISSION;
            case STALE_STATE, FOLLOW_UP_INELIGIBLE, DISALLOWED_ACTION, DISALLOWED_TEMPLATE_INTENT, INVALID_RECOMMENDATION -> AuditDenialReason.INSUFFICIENT_PERMISSION;
        };
    }
}
