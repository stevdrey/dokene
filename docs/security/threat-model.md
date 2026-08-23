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

## Trust boundaries

1. Browser / Internet → application edge
2. Authenticated application → domain core
3. Domain core → PostgreSQL
4. Domain core → AI provider
5. Domain core → messaging provider
6. Provider webhooks → application

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

This document will evolve as features and integrations are introduced.
