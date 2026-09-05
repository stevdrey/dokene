# System Context

Dokene is a multi-tenant SaaS that helps small businesses decide when and how to follow up with customers and deliver approved outbound messages through external messaging providers.

## Primary actors

- Tenant owner / administrator
- Tenant operator
- Customer recipient
- System automation

## External systems

- AI provider, initially OpenAI
- Messaging provider, initially Meta WhatsApp Cloud API
- PostgreSQL
- Secret manager

## Trust boundaries

External providers and all user-controlled input are treated as untrusted. The application core must validate identity, tenant context, authorization, policy, consent, and structured AI output before any side effect occurs.

## High-level flow

1. Scheduler or user requests a follow-up evaluation.
2. An authenticated request may nominate a tenant target, but the application validates an active
   server-side membership before creating its tenant context.
3. Domain rules determine candidate customers.
4. AI may recommend a bounded action using structured output.
5. Policy and authorization layers validate the action.
6. A message is drafted or queued for approval.
7. The messaging provider sends only after the required approval state.
8. Delivery results and audit events are persisted.

## Tenant-context execution boundary

`TenantContext` is the single application-facing source of the active tenant, authenticated
identity, verified membership, and granted tenant role.

Tenant context propagation is execution-scoped using Java `ScopedValue` (`runWithContext` /
`callWithContext`), providing strictly bounded lifetimes and eliminating thread-leakage risks
across reused threads.

For HTTP requests:
- **Authenticated global endpoints** (e.g., tenant discovery `GET /api/tenants`, user profile,
  or account operations) execute without an active `TenantContext` to avoid circular dependencies
  during tenant selection.
- **Tenant-scoped endpoints** require an explicit, verified `TenantContext`. The `X-Tenant-Id` header
  serves only as a requested target and must match an active server-side membership before establishing
  the execution scope. Requests with missing, malformed, or unauthorized tenant selectors fail closed.

Scheduled jobs, asynchronous tasks, and message consumers do not inherit ambient tenant context.
Their entry points must receive a trusted `TenantContext` explicitly and scope execution with
`runWithContext` or `callWithContext`. This keeps later PostgreSQL RLS transaction/session
propagation tied to an explicit, validated context.

## Durable audit boundary

The `audit` module records denials through the existing authorization listener port
and successful membership role changes in the same transaction as the business update.
Internal reads require `AUDIT_READ` and tenant RLS. Servlet requests receive a
server-generated correlation scope; jobs establish correlation explicitly.
[ADR 0006](../adr/0006-durable-append-only-audit.md) defines metadata privacy, global
denial isolation, rollback behavior, and the explicit 503 policy for persistence failures.
