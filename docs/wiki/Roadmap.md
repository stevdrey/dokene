# Roadmap

The roadmap is intentionally capability-driven rather than date-driven. Security and tenant isolation are foundations, not later hardening phases.

## Phase 0 — Foundation

Goal: establish a safe, reviewable platform skeleton before customer-facing automation.

Key outcomes:

- modular-monolith boundaries;
- tenant + membership model;
- PostgreSQL/Flyway baseline;
- application tenant context;
- PostgreSQL RLS;
- authentication and authorization foundations;
- append-oriented audit foundation;
- secret/integration abstraction;
- AI Action Gate contracts;
- tenant-isolation integration test harness;
- provider-neutral agent/development guidance.

Exit condition: a cross-tenant access attempt is demonstrably rejected and the application has a stable foundation for tenant-owned data.

## Phase 1 — Customer memory and follow-up queue

Goal: make Dokene useful before outbound messaging automation exists.

Key outcomes:

- customer management;
- purchase-history capture;
- normalized contact identity;
- follow-up cadence / next-follow-up date;
- consent and do-not-contact state;
- deterministic follow-up eligibility;
- due/overdue follow-up queue;
- operator UI for reviewing customer context.

Exit condition: an operator can reliably see who requires follow-up and why.

## Phase 2 — AI-assisted recommendations

Goal: improve the quality and speed of follow-up preparation while retaining human control.

Key outcomes:

- `AiProvider` abstraction;
- initial hosted-model adapter;
- structured `FollowUpDecision`-style contract;
- Next Best Action recommendation;
- constrained message drafting;
- strict schema validation;
- prompt-injection-aware context assembly;
- recommendation rationale visible to operator;
- AI quality/evaluation baseline.

Exit condition: the system produces useful, explainable drafts without granting the model direct side-effect authority.

## Phase 3 — WhatsApp integration with manual approval

Goal: complete the first end-to-end customer follow-up flow.

Key outcomes:

- `MessagingProvider` abstraction;
- Meta WhatsApp Cloud API adapter (initial provider);
- tenant integration configuration;
- provider template mapping;
- message state machine;
- explicit approval workflow;
- idempotent sending;
- verified/deduplicated webhooks;
- delivery/failure tracking;
- outbound audit trail;
- emergency outbound kill switch.

Exit condition: an approved follow-up can be sent exactly once and tracked safely through delivery/failure state.

## Phase 4 — Scheduling and operational resilience

Goal: automate routine candidate generation without automating judgment prematurely.

Key outcomes:

- recurring follow-up scheduler;
- multi-instance-safe work claiming;
- retries/dead-letter or recovery strategy;
- operational dashboards/health metrics;
- provider health handling;
- rate limits and abuse controls;
- backup/restore validation;
- incident-response operational procedures.

Exit condition: scheduled processing is safe under retries, multiple instances, and transient provider failures.

## Phase 5 — Controlled automation

Goal: allow selected low-risk workflows to send automatically under explicit tenant policy.

Key outcomes:

- policy modes such as `MANUAL_APPROVAL` and constrained `AUTO_SEND_LOW_RISK`;
- explicit allowlisted scenarios;
- confidence/risk thresholds where evidence supports them;
- contact-frequency safeguards;
- per-tenant automation controls;
- audit/explainability for automated sends;
- immediate tenant/global disable control.

Exit condition: automated actions remain bounded by deterministic policy and can never bypass consent or authorization.

## Phase 6 — Product expansion

Potential directions, driven by validated demand:

- additional messaging channels;
- CRM/e-commerce imports;
- product recommendation relationships;
- campaign-like workflows with stronger consent/frequency controls;
- analytics on follow-up outcomes;
- additional AI providers/local models;
- mobile application;
- public API / SDKs;
- enterprise dedicated-database tenancy;
- richer Next Best Action strategies.

These are not commitments until user demand and operational evidence justify them.

## Roadmap guardrails

Across all phases:

- do not sacrifice tenant isolation for delivery speed;
- do not turn AI output into direct commands;
- do not enable auto-send by default;
- do not store provider secrets in source/logs/prompts/plain application data;
- do not use message volume as the primary product-success metric;
- do not split into microservices without an evidence-based reason;
- do not bypass ADR/documentation updates when durable architecture changes.
