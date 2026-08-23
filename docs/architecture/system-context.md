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
2. Tenant context is resolved from authenticated state.
3. Domain rules determine candidate customers.
4. AI may recommend a bounded action using structured output.
5. Policy and authorization layers validate the action.
6. A message is drafted or queued for approval.
7. The messaging provider sends only after the required approval state.
8. Delivery results and audit events are persisted.
