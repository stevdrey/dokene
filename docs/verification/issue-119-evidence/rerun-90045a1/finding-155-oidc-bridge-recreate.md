Found during the full re-execution of QA suite #119 (REG-08), tested SHA `90045a1e935c51facf39bf7422484d43513abbff`. Origin: **pre-existing in the containerized environment** (introduced with the `oidc-bridge` sidecar in e7e3d7b; no comparison build needed). Not a product defect.

**Severity:** Low (local-environment sharp edge; recoverable; documented workaround).

**Environment:** Linux, Docker 29.8.2, Compose 5.6.0, stack started with `./scripts/dev-env.sh up --seed`.

**Steps**
1. With the stack running, change a backend setting and recreate only that service, as is natural when toggling the AI provider: `DOKENE_AI_PROVIDER= docker compose up -d backend`.
2. Wait for the backend to become healthy.

**Expected:** the backend becomes healthy with the new setting.

**Actual:** the backend container is recreated, but `oidc-bridge` (`network_mode: service:backend`) stays attached to the previous, dead network namespace. The new backend never finds the OIDC discovery document and stays `health: starting`; its log only says `Waiting for OIDC discovery at localhost:8081/realms/dokene/.well-known/openid-configuration (up to 180s)` and it exits after 180 s.

**Workaround (verified):** recreate both together: `docker compose up -d backend oidc-bridge` (or run the whole `./scripts/dev-env.sh up`, which recreates dependents correctly; this is how the retest of #126 was done).

**Impact:** confusing failure for contributors who restart only the backend (e.g. to change `DOKENE_AI_*`).

**Suggested direction:** document it in `infra/docker/README.md`, make `dev-env.sh` expose a `restart backend`-style command that recreates `backend` and `oidc-bridge` together, and print a hint from the entrypoint timeout message.

**Reproducibility:** 100% (3/3 recreations of the backend alone).

Related: #118, #119, ADR 0022.
