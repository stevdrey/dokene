# Product Vision

## Problem

Small businesses often depend on repeat customers and personal relationships, but follow-up work is easy to postpone or forget. A business owner may remember who bought recently, but not consistently remember who has gone quiet, what they bought, whether a related offer is relevant, or when it is appropriate to contact them again.

This creates a gap between good customer relationships and day-to-day execution.

Dokene exists to make that follow-up process systematic without making it impersonal.

## Primary user

The initial target user is a small-business owner or operator who:

- serves customers directly;
- has recurring or relationship-driven sales;
- has limited time for CRM administration;
- may work alone or with a very small team;
- uses WhatsApp heavily for customer communication;
- needs help remembering and prioritizing follow-ups more than a large enterprise CRM.

## Core product question

Dokene should help the operator answer:

> **Who should I contact now, why, and what should I say or do next?**

## Initial product loop

```text
Customer activity
      ↓
Stored customer + purchase history
      ↓
Follow-up eligibility evaluation
      ↓
Deterministic policy checks
      ↓
AI-assisted Next Best Action / draft
      ↓
Human review and approval
      ↓
Message delivery
      ↓
Delivery/audit history
      ↓
Future follow-up state
```

## Initial capabilities

The MVP should support:

- customer records;
- purchase-history records;
- last-purchase and follow-up timing;
- configurable follow-up intervals or next-follow-up dates;
- consent and do-not-contact state;
- follow-up candidate generation;
- AI-assisted drafting using structured business context;
- message templates;
- manual approval before sending;
- WhatsApp delivery through a provider abstraction;
- message status tracking;
- audit history;
- tenant-aware access control.

## Product principles

### Reduce cognitive load

The product should remember routine follow-up state so the operator does not have to.

### Preserve human judgment

The system may recommend and draft, but the operator remains in control by default.

### Be useful before being autonomous

A reliable recommendation queue with manual approval is more valuable than premature full automation.

### Respect customer intent

Consent, opt-out, contact policy, and reasonable contact frequency are hard constraints. They are never overridden by AI-generated recommendations.

### Prefer explainable actions

A follow-up recommendation should be understandable: for example, because a customer bought a repeat-purchase product 60 days ago, because a configured follow-up date is due, or because a relevant seasonal event is approaching.

### Keep provider choice replaceable

Dokene should not become synonymous with one LLM, one WhatsApp intermediary, or one hosting provider.

## Differentiation

Dokene is not positioned primarily as an AI chatbot or bulk-marketing tool. Its primary value is **relationship follow-up orchestration for small businesses**.

Longer-term, this can evolve toward a broader Next Best Action engine that combines:

- customer history;
- timing;
- business rules;
- product relationships;
- special dates;
- previous communication outcomes;
- consent and risk constraints;
- AI-assisted recommendation quality.

## Success criteria

Early product success should be measured by outcomes such as:

- fewer overdue follow-ups;
- less manual tracking work;
- useful drafts accepted with little editing;
- low accidental-contact and duplicate-send rates;
- high operator trust in recommendations;
- reliable tenant and security isolation;
- clear auditability of every externally visible action.

Raw message volume is not a primary success metric.

## Explicit non-goals for early releases

- replacing mature enterprise CRM suites;
- building omnichannel support before WhatsApp is reliable;
- unrestricted autonomous outbound campaigns;
- building microservices for speculative scale;
- making the LLM the source of truth for customer state;
- storing provider secrets in prompts, logs, or ordinary application tables;
- optimizing for massive message throughput before product-market evidence exists.
