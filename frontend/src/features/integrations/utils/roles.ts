// ABOUTME: Decides whether a workspace role may edit the integration and run the ownership proof.
// ABOUTME: Mirrors INTEGRATION_MANAGE (OWNER, ADMIN) with the same alias handling as canArchiveCustomer.
export function canManageIntegration(role?: string | null): boolean {
  if (!role) return false;
  return role === 'OWNER' || role === 'ADMIN' || role === 'TENANT_ADMIN';
}
