---
applyTo: "backend/**"
---

# Backend review instructions

For files under `backend/`, apply the canonical backend review guidance in `.agents/skills/backend-java-spring/SKILL.md` together with `AGENTS.md` and relevant ADR/security documentation.

During review, pay particular attention to:

- authentication versus authorization boundaries;
- tenant context derivation, tenant isolation, and PostgreSQL RLS assumptions;
- transaction boundaries, concurrency, retries, idempotency, and race conditions;
- schema migrations, constraints, compatibility, and data-integrity failure modes;
- request/response contracts, validation, error mapping, and persistence-entity leakage;
- secret/PII handling, logging, provider/webhook trust boundaries, timeouts, and external-response validation;
- negative/security tests for unauthorized, cross-tenant, malformed, duplicate/replayed, and dependency-failure cases where relevant.

Prefer concrete behavioral findings over style preferences. Do not flag a Java/Spring pattern merely because another pattern is possible when the existing choice is safe, documented, and consistent with the repository architecture.