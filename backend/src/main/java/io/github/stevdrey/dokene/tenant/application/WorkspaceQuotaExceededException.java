// ABOUTME: Thrown when an allowed identity already provisioned as many workspaces as the cap permits (ADR 0033 §6).
// ABOUTME: The API answers 403 with code WORKSPACE_QUOTA_EXCEEDED; replays of an existing key are never refused.
package io.github.stevdrey.dokene.tenant.application;

public class WorkspaceQuotaExceededException extends TenantAccessDeniedException {

    public WorkspaceQuotaExceededException() {
        super("Workspace provisioning limit reached for this identity");
    }
}
