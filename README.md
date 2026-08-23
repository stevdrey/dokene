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

## License

AGPL-3.0-or-later.
