# ADR 0004: AI Action Gate

## Status
Accepted

## Decision
LLM output is advisory and may never directly trigger an external side effect.

All AI recommendations must pass through a deterministic action gate that validates:

- authenticated actor and tenant context,
- authorization,
- resource ownership,
- consent and opt-out policy,
- approved template state,
- rate and abuse limits,
- idempotency,
- required human approval.

## Consequence
AI providers remain replaceable and untrusted. The application core owns policy and execution.
