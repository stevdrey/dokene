# Dokene Backend

Spring Boot modular monolith.

Planned domain/module boundaries:

- `identity` — authentication-facing identity model
- `tenant` — tenant context, membership, roles, permissions
- `customer` — customer profile and consent state
- `purchase` — purchase/history signals
- `followup` — scheduling and follow-up decision workflow
- `template` — message-template lifecycle and versions
- `messaging` — message state machine and outbound ports
- `ai` — provider abstraction and structured recommendations
- `integration` — external provider adapters
- `audit` — append-only security/business audit events
- `security` — policy enforcement and cross-cutting security controls

The domain core must not depend directly on concrete AI or messaging providers.
