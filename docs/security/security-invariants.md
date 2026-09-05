# Security Invariants

The following invariants are architectural requirements, not optional conventions.

1. No tenant-scoped data is accessed without an active tenant context.
2. Cross-tenant access must be blocked at both application and database layers.
3. LLM output never directly causes an external side effect.
4. Every outbound action is authorized, policy-checked, idempotent, and auditable.
5. Opt-out and consent restrictions always override automation.
6. Secrets never appear in source control, logs, prompts, or plaintext business tables.
7. External providers, webhooks, imported data, and user-generated content are untrusted.
8. Human approval is the default for outbound messaging until a tenant explicitly enables an allowed automation policy.
9. Security-sensitive state transitions are explicit and validated.
10. Runtime database credentials must not have schema-migration or RLS-bypass privileges.

11. Runtime audit history is append-only; tenant reads require `AUDIT_READ` and signed-capability RLS.
12. Successful security-sensitive database transitions commit atomically with audit records; denied actions require independent durable auditing. Audit persistence failures abort the operation explicitly.
13. Audit attribution comes from trusted execution context, and metadata accepts only closed, privacy-reviewed types. Unattributed global denials remain unreadable to the runtime role.
