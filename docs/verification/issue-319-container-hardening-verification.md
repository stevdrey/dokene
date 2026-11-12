# Issue #319 PostgreSQL and Keycloak container restrictions: verification record

Status: single PR. It closes audit finding L14 of the [security audit of 2026-11-02](../security/security-audit-2026-11-02.md): the `postgres` and `keycloak` services of `compose.yaml` had none of the restrictions the other services have. The decision is recorded in [ADR 0022](../adr/0022-containerized-local-environment.md); the writable paths and the remaining exceptions are listed in [`infra/docker/README.md`](../../infra/docker/README.md).

This record is sanitized: it gives outcomes, counts and settings only. It contains no credential, key or reproduction script. All data was synthetic and all secrets were local-only values.

## Environment

- **Run:** 2026-11-11, branch `fix/319-harden-local-containers`, based on `main` at `e7fcc67`. The finding was rechecked on that base before the change and still applied: both containers ran with the default capability bounding set, `NoNewPrivs: 0` and a writable root filesystem.
- **Tested revision:** the working tree that became the first commit of this branch on top of `e7fcc67` (a commit cannot name its own hash). The file under test is `compose.yaml` with Git blob id `c017a94698d035bf8a7eeca871375b30dee6c9cc`. Comments in that file were reworded after the live run, once before and once after the independent verification; the service configuration did not change. The Compose config hashes of the running `postgres` and `keycloak` containers were compared with `docker compose config --hash` of the file before the last comment edit and were identical, and the same two hashes were computed again from the final file.
- **Toolchain:** Docker Desktop 29.7.2 (Linux arm64 VM on macOS), Docker Compose v5.4.0, `postgres:17-alpine`, Keycloak 26.7.3.
- **Live run:** the repository `compose.yaml` in a separate Compose project (`dokene-qa319`, its own volume), started through `./scripts/dev-env.sh`. The baseline used the `compose.yaml` of `e7fcc67` in the same project.
- **Not run:** `./gradlew check` and the frontend suite, because no backend or frontend file changed. ShellCheck is not installed on this host, so the smoke test skipped that step.

## What changed

| Service | Restriction | Before | After |
| --- | --- | --- | --- |
| `postgres` | start user | root, stepping down to `postgres` | `postgres` (uid/gid 70) from the start |
| `postgres` | capabilities | Docker default set | none (`cap_drop: [ALL]`) |
| `postgres` | `no-new-privileges` | off | on |
| `postgres` | root filesystem | writable | read-only; writable: data volume, tmpfs `/var/run/postgresql`, tmpfs `/tmp`, and Docker's `/dev/shm` |
| `keycloak` | start user | uid 1000 (image default) | unchanged |
| `keycloak` | capabilities | Docker default set | none |
| `keycloak` | `no-new-privileges` | off | on |
| `keycloak` | root filesystem | writable | read-only; writable: tmpfs `/opt/keycloak/data`, tmpfs `/tmp`, and Docker's `/dev/shm` |

## Compatibility checks (throwaway containers)

| Check | Outcome |
| --- | --- |
| PostgreSQL, stock root start, all capabilities dropped | Refused to start: `chmod … Operation not permitted`, `failed switching to 'postgres': operation not permitted`. This is why the service starts as uid 70 instead of adding capabilities back |
| PostgreSQL as uid 70 with every restriction, empty volume | Initialised and accepted connections |
| PostgreSQL as uid 70 with every restriction, volume initialised by the previous configuration with one row | Started; the row was read back |
| PostgreSQL entrypoint running the role-creation script from a volume (no host bind mount), every restriction | Script sourced; `dokene_migration` and `dokene_runtime` exist |
| The same without a writable `/tmp` | Also passed: startup does not need `/tmp` today. It stays as a 16 MiB tmpfs for here-documents too large for a pipe |
| Keycloak, read-only root, no writable path | Refused to start: `/tmp/vertx-cache: Read-only file system` |
| Keycloak, writable `/opt/keycloak/data` only | Refused to start with the same error |
| Keycloak, writable `/opt/keycloak/data` and `/tmp` | Started |
| `/dev/shm` with every restriction, both images | Still writable: Docker mounts a 64 MiB tmpfs there (`rw,nosuid,nodev,noexec`) that `read_only` does not cover. PostgreSQL uses it for dynamic shared memory. In the Keycloak image `/dev/mqueue`, `/run` and `/var/tmp` refused a write |

## Automated coverage

| Acceptance item | Test |
| --- | --- |
| Both services have a read-only root, drop every capability with none added back, set `no-new-privileges`, are not privileged, and have exactly the listed writable volumes and size-capped tmpfs mounts; PostgreSQL starts as `70:70` | `scripts/tests/dev-env-smoke.sh`: "compose: postgres and keycloak run restricted with explicit writable paths (#319)". It failed before the change and passes after it |

**Smoke run:** 18 checks passed, 1 failed, 1 skipped (ShellCheck). The failing check is "secret files: … (#308)"; it fails the same way on `e7fcc67` on macOS and is tracked as [#366](https://github.com/stevdrey/dokene/issues/366). No CI workflow runs this script.

## Live run (Compose)

| Scenario | Outcome |
| --- | --- |
| Baseline `e7fcc67`, full stack, QA seed | Healthy. Schema at V39 (40 history rows), 1 tenant, 3 memberships, 4 audit events. `postgres` and `keycloak`: capability bounding set `00000000a80425fb`, `NoNewPrivs: 0`, `ReadonlyRootfs=false` (the finding) |
| Upgrade: same volume, `./scripts/dev-env.sh up` on this branch | `postgres`, `keycloak`, `migrate` and `backend` recreated; all healthy. `migrate`: "Successfully validated 40 migrations", "Schema is up to date". Counts identical to the baseline |
| Container state after the upgrade | Both: `ReadonlyRootfs=true`, `CapDrop=[ALL]`, `SecurityOpt=[no-new-privileges:true]`; process 1 has `CapPrm`, `CapEff` and `CapBnd` all zero and `NoNewPrivs: 1`. `postgres` runs as uid 70, `keycloak` as uid 1000 |
| Write probes, `postgres` | Refused with "Read-only file system": `/`, `/var/lib/postgresql`, `/usr/local/bin`, `/docker-entrypoint-initdb.d`. Writable: the data directory, `/var/run/postgresql`, `/tmp`. `/dev/shm` was not probed in this run; see the compatibility checks |
| Write probes, `keycloak` | Refused: `/`, `/opt/keycloak`, `/opt/keycloak/conf`, `/opt/keycloak/providers`, `/opt/keycloak/import-template`. Writable: `/opt/keycloak/data` (mode 0700, uid 1000), `/tmp`. `/dev/shm` was not probed in this run; see the compatibility checks |
| tmpfs use after realm import and logins | `/opt/keycloak/data` 1.7 MiB of 256 MiB; Keycloak `/tmp` 36 KiB of 64 MiB; PostgreSQL `/var/run/postgresql` 4 KiB of 1 MiB; PostgreSQL `/tmp` empty |
| Authentication after the upgrade: `scripts/verify-keycloak-bff-e2e.sh` | 8 of 8 assertions passed (authorization code flow, session rotation, token isolation, CSRF, logout) |
| `scripts/keycloak-apply-session-revocation.sh` (runs `kcadm` inside the read-only container) | All 4 settings reported `ok`; exit 0 |
| Persistence: `docker compose restart postgres keycloak` | All healthy again. Database counts unchanged. Keycloak imported the realm again; the login check passed 8 of 8 |
| Memberships after Keycloak re-import: QA seed run again | Exit 0; still 1 tenant and 3 memberships, so the re-imported test users map to the stored identities |
| Persistence: `./scripts/dev-env.sh down`, then `up` | All healthy. Database counts unchanged; the login check passed 8 of 8 |
| Clean deployment: volume removed, `./scripts/dev-env.sh up --seed` | `migrate`: "Successfully applied 39 migrations … now at version v39"; all services healthy; seed provisioned the workspace (1 tenant, 3 memberships); the login check passed 8 of 8. See the limitation below for the role-creation step |

## Review follow-up (2026-11-12)

Changes made after the review of PR #385. The sections above describe the run of 2026-11-11 and were not repeated; the full stack was not started again.

| Review point | Change | Evidence |
| --- | --- | --- |
| The smoke check also read a local `compose.override.yaml` | `compose_config_json` renders `-f compose.yaml` only | Smoke run below |
| Keycloak's user and the options of its data tmpfs were not asserted | The #319 check asserts that Compose does not override the image user and that `/opt/keycloak/data` keeps `uid=1000` and `mode=0700` | Smoke run below |
| uid 70 was written in `user:` and again in the tmpfs options | Both read `DOKENE_POSTGRES_UID` (default 70) | New check renders the model with 999 and finds `999:999` and `uid=999,gid=999`; with the default the rendered values are the ones tested on 2026-11-11 |
| Interactive `psql` could not save its history | `PSQL_HISTORY=/tmp/.psql_history` on `postgres` | Throwaway `postgres:17-alpine` container with the same restrictions: without the variable `psql` printed `could not save history to file "/var/lib/postgresql/.psql_history": Read-only file system`; with it the file was written to `/tmp` and no error was printed |
| `kcadm.sh` cannot write `~/.keycloak/kcadm.config` | Documented in `infra/docker/README.md`: pass `--config /tmp/…` | Not run again; `scripts/keycloak-apply-session-revocation.sh` already uses `--config` under `/tmp` and passed in the live run above |
| `up` still ran the session revocation reconcile, which cannot change a realm imported at every start | Call removed from `cmd_up`; the script stays for a Keycloak that keeps its realm, and ADR 0007 says so | New check: `cmd_up` does not reference the script. `scripts/tests/keycloak-apply-session-revocation-test.sh` passes |
| The usage header said `reset` destroys Keycloak data | Header corrected | New check on the `help` output |
| "listed here and nowhere else" was inaccurate | README reworded; ADR 0022 points to the README instead of restating the paths | Read-through |

**Smoke run:** 21 checks passed, 1 failed, 1 skipped (ShellCheck, not installed on this host). The failing check is the same "secret files: … (#308)" check tracked as [#366](https://github.com/stevdrey/dokene/issues/366). The four checks added or extended for this round failed before the changes and pass after them.

## Limitations

- On this Docker Desktop host the bind-mounted `01-create-database-roles.sh` is executed instead of sourced and exits 126 ("bad interpreter: Permission denied"). It does so identically on `e7fcc67`; it is the behaviour reported in [#298](https://github.com/stevdrey/dokene/issues/298). In both the baseline and the clean deployment the script was therefore applied with `docker compose exec -T postgres bash < …`, which runs inside the restricted container. The unmodified entrypoint path was verified separately with the script in a volume (see the compatibility checks). A clean first start on a Linux host was not run.
- Rootless Docker, Podman and SELinux hosts were not tested. Tmpfs ownership options (`uid=`, `gid=`) are passed to the engine as written.
- A passing run shows the listed criteria were met under the listed checks; it does not prove the absence of defects.

## Residual risk and operator notes

- Keycloak state is now in memory. A Keycloak restart ends the open sessions and discards users or settings added through the admin console; the realm is imported again from the template. Before this change that state lasted until the container was removed.
- The PostgreSQL bootstrap role is still a superuser inside the database, and the container environment still holds its password and the two role passwords used on first start.
- `/dev/shm` stays a writable 64 MiB `noexec` tmpfs in every container. PostgreSQL needs it; it is not capped or removed for the other services.
- No user namespace remapping, custom seccomp or AppArmor profile, or resource limit is configured; Docker's default seccomp profile applies.
- Replacing `postgres:17-alpine` with a Debian-based tag requires setting `DOKENE_POSTGRES_UID` to that image's `postgres` uid (999); it sets `user:` and the owner of the socket tmpfs together (see the review follow-up).
