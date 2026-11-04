-- Runtime membership writes are invitations (INSERT), role changes and revocations (issue #315, audit L5).
-- Revocation is a status change, so the runtime role never deletes a membership, and it never rewrites
-- a membership's id, tenant, identity or creation time.
REVOKE UPDATE, DELETE ON TABLE dokene.tenant_memberships FROM dokene_runtime;
GRANT UPDATE (role, status, updated_at, version) ON TABLE dokene.tenant_memberships TO dokene_runtime;

-- Without the DELETE grant this policy can never apply; tenant_memberships_migration_policy still covers
-- dokene_migration.
DROP POLICY tenant_memberships_delete_policy ON dokene.tenant_memberships;
