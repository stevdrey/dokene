// ABOUTME: Decides whether a workspace role may see the message action controls (submit, approve, cancel, send).
// ABOUTME: A usability aid only; the server stays the gate and a missing or unknown role is read only.
const ACTING_ROLES: readonly string[] = ['OWNER', 'ADMIN', 'OPERATOR', 'TENANT_ADMIN', 'TENANT_OPERATOR'];

export function canActOnMessages(role?: string | null): boolean {
  return typeof role === 'string' && ACTING_ROLES.includes(role);
}

/** Every present role holds MESSAGE_READ (ADR 0023 §5); only a missing role hides the read only history. */
export function canReadMessages(role?: string | null): boolean {
  return typeof role === 'string' && role !== '';
}
