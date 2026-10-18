# Threat Model

## Assets

- Customer PII and purchase history
- Tenant business data
- Authentication and session state
- Meta WhatsApp credentials
- AI-provider credentials
- Message templates and outbound-message history
- Audit records

## Primary threats

- Cross-tenant data access / IDOR
- Privilege escalation
- Credential leakage
- Forged or replayed webhooks
- Prompt injection through imported or user-controlled content
- Unauthorized or duplicate outbound messages
- Abuse of the platform for spam
- Supply-chain compromise
- Sensitive data leakage through logs or observability
- Cross-tenant information channel through metric labels or diagnostic endpoints (mitigated by a closed tag allow-list and no exposed actuator endpoints; ADR 0019)
- Retry storms and duplicate AI cost during provider throttling or outages (mitigated by bounded, deadline-limited retry; ADR 0019)
- Preemptive phone-number-id registration through the WhatsApp integration API: any `INTEGRATION_MANAGE` user can map any well-formed Meta id. Mitigated (issue 210, ADR 0024 addendum):
  - Uniqueness binds only verified mappings, so an unverified claim never blocks the real owner.
  - Ownership is proven by the provider adapter with the tenant's own credential: the stored business account must list the number at Meta. The proof is cleared whenever the number, business account or credential revision changes or the integration is disabled, and a database trigger rejects any write that would keep it.
  - Webhook routing (#139) may resolve a tenant only through `VerifiedPhoneNumberMappings`, which returns a verified, enabled mapping's tenant id and nothing else.
  - The send gate treats a mapping without a current proof as `INTEGRATION_DISABLED`.

  Residual risks:
  - A credential that genuinely reaches another business's account, for example a shared or partner-managed business account, can verify that account's numbers.
  - If a number moves at Meta while the old tenant stays enabled with a stale proof, the new owner's proof conflicts until the old tenant disables or changes its integration, or a platform operator clears the stale proof.
  - Proofs depend on the provider being available, and every proof fails closed until a production secret resolver exists (#142).
- Operator- or model-asserted template approval (issue 135): a mapping becomes sendable only from provider evidence recorded by the explicit refresh, bound to the mapping revision and business account and expiring after `dokene.template.approval-max-age`; the API has no approval or provider-id member, the intent is a closed enum, and an unavailable provider drops evidence instead of keeping it (ADR 0025). Provider approval also has to match the mapping's parameter schema (issue 218): the template's components are parsed from untrusted provider JSON with fixed bounds, any unsupported, mismatching or unreadable schema is recorded as a closed `SCHEMA_*` status that is never sendable, and no provider text or placeholder is stored, logged, audited or returned. Until a production secret resolver exists every refresh fails closed.
- Phone-number-id availability probe through the WhatsApp integration API. *Since issue 210, `PUT` no longer conflicts on the number; the only remaining signal is a proof that the provider confirmed but another tenant's verified mapping blocks. That refusal is the same `400 INVALID_INPUT` as an unproven number, and the budget below now guards proofs instead of `PUT`.* Before issue 210, `(provider, phone_number_id)` was globally unique because webhooks resolve the tenant from it, so a tenant admin with `INTEGRATION_MANAGE` could learn that a candidate Meta id is unavailable. The refusal is the same `400 INVALID_INPUT` as any invalid mapping and never names or reads another tenant's record. Mitigated (issue 208): each id already mapped by another tenant consumes a per-tenant and per-actor budget in a sliding window (`dokene.integration.whatsapp.mapping-rejection-limit`, defaults 10 per tenant and 5 per actor in 15 minutes). Once a budget is spent, every write that changes the mapping is refused with `429 RATE_LIMITED` and `Retry-After`, whether the candidate is mapped or not, Every refused attempt is audited as `AUTHORIZATION_DENIED` without the candidate id: `MAPPING_REJECTED` for each conflict and `RATE_LIMITED` for each throttled attempt. Keeping the stored id (for example disabling the integration) is never throttled. Residual risk: a few ids per window can still be probed, and the budget is held in memory per instance, so it resets on restart and is multiplied by the number of instances.

## Trust boundaries

1. Browser / Internet → application edge
2. Authenticated application → domain core
3. Domain core → PostgreSQL
4. Domain core → AI provider
5. Domain core → messaging provider
6. Provider webhooks → application
7. OIDC provider callback → Spring Security authentication adapter

## Required mitigations

- Strong authentication and explicit authorization
- Server-derived tenant context
- PostgreSQL RLS and isolation tests
- Structured AI output and deterministic validation
- Human approval by default
- Secret-manager integration
- Webhook signature verification and replay protection
- Idempotency for scheduled and outbound actions
- Rate limiting and abuse controls
- PII-safe logging and append-only auditing
- OIDC issuer, signature, nonce/state, audience and time validation at the framework adapter boundary
- Unique issuer/subject identity mappings, server-side sessions, CSRF protection, and explicit tenant membership checks

## Phase 3 security cases

The threats above are turned into runnable cases for Phase 3 outbound messaging in [Phase 3 Security Cases](../verification/phase-3-security-cases.md). That table covers IDOR and RLS, role permissions, CSRF, edited or stale approval, template approval evidence, revoked consent, replay or duplicate send, forged webhook, secret exposure and emergency disable, across two synthetic tenants and the four tenant roles. It is preparation: it lists what must be proved and what already has a test, and it does not replace the integrated security run.

This document will evolve as features and integrations are introduced.
