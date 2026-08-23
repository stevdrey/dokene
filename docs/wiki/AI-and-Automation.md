# AI and Automation

## Role of AI in Dokene

AI is used where language generation, contextual judgment, and recommendation quality provide meaningful value.

AI is not the security model, authorization engine, consent engine, scheduler lock, or source of truth for customer state.

The application should remain useful even when an AI provider is temporarily unavailable.

## Next Best Action

A long-term product capability is a **Next Best Action** recommendation for each customer.

Inputs may include:

- customer history;
- recent purchases;
- elapsed time since activity;
- configured follow-up cadence;
- product relationships;
- seasonal/special dates;
- prior communication outcomes;
- tenant-specific business rules;
- consent/contact eligibility.

Outputs should be structured, explainable, and constrained.

Example conceptual response:

```text
FollowUpDecision
├── eligible: true
├── action: REPEAT_PURCHASE_FOLLOW_UP
├── template: REPEAT_PURCHASE
├── reason: "Customer bought a repeat-purchase item 63 days ago"
├── confidence: 0.84
└── draftVariables: {...}
```

The exact schema may evolve, but free-form model text must not be interpreted directly as an arbitrary command.

## AI Action Gate

All AI-recommended side effects must cross an application-controlled gate.

```text
Application context
      ↓
AI provider
      ↓
Structured result
      ↓
Schema validation
      ↓
Business eligibility
      ↓
Consent/contact policy
      ↓
Authorization
      ↓
Action allowlist
      ↓
Human approval or approved automation policy
      ↓
Side effect
```

The model may recommend `REPEAT_PURCHASE`, but application code determines whether that action exists, is allowed for the tenant, is valid for the recipient, and may be sent now.

## Trust boundaries

Treat these as untrusted input to the model and to the application:

- customer-entered text;
- imported notes;
- provider webhook payloads;
- external website/CRM data;
- generated model output.

Prompt injection can influence model behavior. Therefore prompt wording is never the only security barrier.

## Structured output

Prefer strict schemas and enums over prose parsing.

Avoid designs where a model returns values such as:

```text
tool = "anything"
url = "anything"
template = "anything"
tenant = "anything"
recipient = "anything"
```

and the application executes them directly.

Map model concepts onto application-owned enums and provider configuration.

## Templates and generation

WhatsApp business-initiated messages may require provider-approved templates depending on platform policy and conversation state.

Dokene should distinguish:

- a **Dokene business template**: semantic intent/content definition managed by the application;
- a **provider template**: externally approved channel-specific template identifier;
- a **rendered message**: immutable message content actually approved/sent.

The AI can help select among allowed intents or generate safe variable content, but it should not invent provider template identifiers.

## Human approval

Initial policy:

```text
MANUAL_APPROVAL
```

The operator should see enough context to judge a recommendation:

- customer;
- why follow-up is due;
- relevant purchase/context;
- proposed action;
- proposed message;
- contact/consent state;
- any warnings.

Approval should be explicit and auditable.

## Future auto-send

Automation may evolve toward:

```text
AUTO_SEND_LOW_RISK
```

but only when deterministic policy defines the allowed scenario.

Possible constraints may include:

- approved message category;
- verified consent;
- low contact frequency;
- approved template;
- confidence threshold;
- no recent failure/opt-out;
- tenant explicit opt-in;
- global/tenant kill switch.

The model itself must never toggle these controls.

## Provider abstraction

The application should expose a narrow interface such as:

```text
AiProvider
```

The initial adapter may target OpenAI, but core follow-up logic must not depend on OpenAI-specific request/response types.

This keeps future options open for other hosted models or local providers.

## Deterministic rules before AI

Use ordinary application logic when the problem is deterministic.

Examples:

- whether contact consent exists;
- whether the user has `MESSAGE_APPROVE`;
- whether a follow-up date is overdue;
- whether an idempotency key has already been consumed;
- whether a tenant has disabled outbound messages;
- whether a provider integration is enabled.

Use AI for problems such as:

- concise personalized wording;
- contextual recommendation ranking;
- extracting a safe structured summary from complex business context;
- selecting among explicitly allowed semantic actions where heuristics are insufficient.

## Evaluation

AI quality should be evaluated separately from platform correctness.

Useful evaluation dimensions include:

- recommendation relevance;
- draft acceptance/edit rate;
- unsupported/hallucinated action rate;
- schema-valid response rate;
- unsafe recommendation rejection rate;
- latency;
- token/cost consumption;
- provider failure behavior.

A better model is not a reason to weaken deterministic controls.
