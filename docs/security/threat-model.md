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

The threats above are turned into runnable cases for Phase 3 outbound messaging in [Phase 3 Security Cases](../verification/phase-3-security-cases.md). That table covers IDOR and RLS, role permissions, CSRF, edited or stale approval, revoked consent, replay or duplicate send, forged webhook, secret exposure and emergency disable, across two synthetic tenants and the four tenant roles. It is preparation: it lists what must be proved and what already has a test, and it does not replace the integrated security run.

This document will evolve as features and integrations are introduced.
