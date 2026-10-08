# Dokene

Dokene is an open-source, security-first customer follow-up SaaS for small businesses. It is inspired by the Bribri verb `dö̀knẽ`, meaning “to return”.

## Repository structure

- `backend/` — Spring Boot backend
- `frontend/` — React + TypeScript frontend
- `docs/` — architecture, ADRs, and security documentation
- `infra/` — local/deployment infrastructure
- `.github/` — CI and repository automation

## Local environment

The whole stack (PostgreSQL, Keycloak, backend, frontend) runs in Docker so every contributor gets the same
runtime configuration. Only Docker (Compose 2.17+) is required on the host:

```bash
./scripts/dev-env.sh up --seed     # preflight checks, .env generation, build, start, QA seed
./scripts/dev-env.sh status        # container health and HTTP checks
./scripts/dev-env.sh down          # stop (data kept); `reset` also deletes local data
```

Open http://localhost:5173 and sign in as `testoperator` / `testuser` / `testviewer` (password:
`DOKENE_TEST_USER_PASSWORD`, default `testpassword`). Details, host-run development and troubleshooting:
[`infra/docker/README.md`](infra/docker/README.md) and [ADR 0022](docs/adr/0022-containerized-local-environment.md).

## Architecture baseline

- Monorepo
- Modular monolith
- PostgreSQL
- Shared-schema multi-tenancy with `tenant_id`
- PostgreSQL Row Level Security as defense in depth
- Security-first design
- Provider abstractions for AI and messaging integrations
- Human-in-the-loop by default for outbound messaging

## Follow-up API concurrency and dispositions

Tenant and customer follow-up policy reads return a strong numeric `ETag`. Policy updates and snoozes require
the matching value in `If-Match`; stale versions return `409 Conflict`. Due follow-ups are queried through
`GET /api/follow-up-queue` with cursor pagination. Operator dispositions (dismissals and manual completions)
require an `Idempotency-Key` and `If-Match`, accept optional notes, return `201 Created` on first execution and
replay with `200 OK`. Dismissals and completions clear active snoozes and advance the cadence cycle. Dispositions
re-evaluate customer eligibility at execution time, rejecting invalid transitions with `409 Conflict`. Tenant
time zones must be regional IANA identifiers (including `UTC`), not fixed offsets.

## License

AGPL-3.0-or-later.
