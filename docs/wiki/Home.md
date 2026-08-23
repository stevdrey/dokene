# Dokene

Dokene is an open-source, security-first customer follow-up platform for small businesses.

The product helps a business remember when to reconnect with customers, understand relevant purchase history and timing, prepare a suitable follow-up, and send it through an approved communication channel. The system is designed to reduce the cognitive and operational burden of maintaining customer relationships without turning the AI model into an autonomous authority.

> **Naming note:** Dokene is a brand name inspired by the Bribri verb `dö̀knẽ`, associated with the idea of returning. Dokene is not presented as a literal Bribri word.

## Product goal

Dokene should answer a simple business question reliably:

> **Who should I follow up with, why now, and what is the safest useful next action?**

The initial product focuses on small businesses with limited time, small or medium customer bases, and recurring or relationship-driven sales.

## Core capabilities

- customer and purchase-history management;
- configurable follow-up cadence and next-contact scheduling;
- rule-based eligibility and consent checks;
- AI-assisted message drafting and Next Best Action recommendations;
- human approval before sending by default;
- WhatsApp as the initial messaging channel, behind a provider abstraction;
- auditability, idempotency, tenant isolation, and explicit security controls;
- multi-tenant SaaS architecture without coupling the core to one AI or messaging vendor.

## What Dokene is not

Dokene is not intended to be:

- a generic chatbot;
- an unrestricted AI agent with direct access to side effects;
- a full CRM replacement in the first releases;
- a marketing-blast platform;
- a product that depends permanently on one LLM or messaging provider;
- a microservice architecture by default.

## Architecture at a glance

```text
┌──────────────────────┐
│      Web Browser     │
└──────────┬───────────┘
           │ HTTPS
           ▼
┌──────────────────────┐
│ React + TypeScript   │
│ Frontend             │
└──────────┬───────────┘
           │ REST / OpenAPI
           ▼
┌──────────────────────────────────────────────┐
│ Spring Boot modular monolith                 │
│                                              │
│ identity  tenant  customer  followup         │
│ messaging template ai integration audit      │
│ security                                     │
└───────┬────────────────┬─────────────────────┘
        │                │
        ▼                ▼
┌───────────────┐  ┌──────────────────────────┐
│ PostgreSQL    │  │ External providers       │
│ + RLS         │  │ AI / WhatsApp / future  │
└───────────────┘  └──────────────────────────┘
```

## Architectural principles

1. **Security is a first-class property.** Tenant isolation, authorization, consent, auditability, secrets management, and safe side effects are design constraints rather than post-processing.
2. **The LLM is advisory, never authoritative.** AI output is untrusted input and must pass schema validation, deterministic policy, authorization, and action gates.
3. **Human-in-the-loop is the default.** Automated sends are an explicit policy decision introduced only after sufficient controls and evidence.
4. **One product, one monorepo.** Backend, frontend, documentation, and infrastructure evolve together while retaining clear runtime boundaries.
5. **Modular monolith before microservices.** Module ownership is explicit, but deployment complexity is kept low until scale or organizational boundaries justify separation.
6. **Provider independence.** AI and messaging providers sit behind narrow interfaces.
7. **Multi-tenancy is designed in from the start.** Tenant context is derived from authenticated identity; business data is tenant-scoped at both application and database layers.
8. **Deterministic rules before AI where possible.** AI is used for judgment and language generation, not for enforcing hard security/business invariants.

## Documentation map

- [[Product Vision]] — problem, users, scope, value proposition, and product principles.
- [[Architecture]] — system structure, modules, boundaries, and deployment model.
- [[Security Model]] — threat model, invariants, authorization, audit, and safe action execution.
- [[Multi Tenancy and Data]] — tenant model, PostgreSQL strategy, RLS, and data ownership.
- [[AI and Automation]] — AI Action Gate, Next Best Action, structured outputs, and human approval.
- [[Messaging and Integrations]] — messaging providers, templates, webhooks, secrets, and idempotency.
- [[Development Model]] — monorepo, backend/frontend conventions, agent skills, testing, and ADR workflow.
- [[Roadmap]] — phased delivery strategy and non-goals.

## Source of truth

The canonical editable Wiki source lives in `docs/wiki/` in the main Dokene repository so architectural changes can be reviewed through normal GitHub pull requests. The published GitHub Wiki is treated as a synchronized documentation surface, not a second independently maintained copy.

Durable architecture decisions are recorded separately in `docs/adr/`. If a Wiki page and an accepted ADR disagree, the ADR governs the decision it covers and the Wiki should be updated accordingly.
