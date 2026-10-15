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
- Preemptive phone-number-id registration through the WhatsApp integration API: any `INTEGRATION_MANAGE` user can map any well-formed Meta id, which blocks the legitimate tenant and could misroute webhooks. Mitigation: mappings are unverified (`webhookVerified=false`), the webhook (#139) must not trust unverified mappings and the send path must fail closed; a squatted id is released out of band until verification-based reclaim exists (ADR 0024 addendum).
- Operator- or model-asserted template approval (issue 135): a mapping becomes sendable only from provider evidence recorded by the explicit refresh, bound to the mapping revision and business account and expiring after `dokene.template.approval-max-age`; the API has no approval or provider-id member, the intent is a closed enum, and an unavailable provider drops evidence instead of keeping it (ADR 0025). The secret resolver fails closed by construction (spec 0006, ADR 0024 addendum for issue 142): with no configured source every resolution is `SOURCE_DISABLED`, a reference outside the caller's tenant or deployment scope is `MISSING`, a mismatched purpose is `WRONG_PURPOSE`, and the local source refuses to boot on an invalid entry; every denial carries only a category, the scope kind and the tenant id, never the reference or the value, and consumers map every category to the same refusal.
- Phone-number-id availability probe through the WhatsApp integration API (accepted residual risk): `(provider, phone_number_id)` is globally unique because webhooks resolve the tenant from it, so a tenant admin with `INTEGRATION_MANAGE` can learn that a candidate Meta id is unavailable. The refusal is the same `400 INVALID_INPUT` as any invalid mapping and never names or reads another tenant's record. Follow-up: rate-limit rejected mappings per tenant (see the rate limiting requirement below).

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

This document will evolve as features and integrations are introduced.
