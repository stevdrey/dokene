# Security Policy

Dokene is designed with security as a first-class architectural concern.

## Reporting a vulnerability

Please do **not** open a public GitHub issue for suspected vulnerabilities. Until a private reporting channel is configured, contact the maintainer through GitHub and request a private disclosure path.

## Security principles

- Explicit tenant isolation at application and database layers.
- Least privilege for users, integrations, database roles, and external providers.
- No LLM output may directly trigger a side effect.
- All external actions pass authorization, policy validation, and audit checks.
- Secrets must not be committed, logged, persisted in plaintext, or exposed to prompts.
- Outbound messaging is human-approved by default.
- Opt-out and consent rules always override automation.
- Security-sensitive operations must be idempotent and auditable.

See `docs/security/` for the threat model and security invariants.
