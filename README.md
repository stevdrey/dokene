# Dokene

Dokene is an open-source, security-first customer follow-up SaaS for small businesses. It is inspired by the Bribri verb `dö̀knẽ`, meaning “to return”.

## Repository structure

- `backend/` — Spring Boot backend
- `frontend/` — React + TypeScript frontend
- `docs/` — architecture, ADRs, and security documentation
- `infra/` — local/deployment infrastructure
- `.github/` — CI and repository automation

## Architecture baseline

- Monorepo
- Modular monolith
- PostgreSQL
- Shared-schema multi-tenancy with `tenant_id`
- PostgreSQL Row Level Security as defense in depth
- Security-first design
- Provider abstractions for AI and messaging integrations
- Human-in-the-loop by default for outbound messaging

## Follow-up API concurrency

Tenant and customer follow-up policy reads return a strong numeric `ETag`. Policy updates and snoozes require
the matching value in `If-Match`; stale versions return `409 Conflict`. Manual follow-up recording additionally
requires an `Idempotency-Key`, returns `201 Created` on first completion and replays the original completion with
`200 OK`. Tenant time zones must be regional IANA identifiers (including `UTC`), not fixed offsets.

## License

AGPL-3.0-or-later.
