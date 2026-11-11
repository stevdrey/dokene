# Issue #314 identity provider hardening: verification record

Status: single PR. It closes audit finding L9 of the [security audit of 2026-11-02](../security/security-audit-2026-11-02.md): the local Keycloak realm did not require PKCE on the provider side, had no password policy, and gave every test user the same well known password. The design is [spec 0014](../specs/0014-identity-provider-security-hardening.md); the contract is amended in [ADR 0007](../adr/0007-oidc-server-side-session.md).

This record is sanitized: it gives outcomes, lengths and test names only. It contains no password, client secret, authorization code or session value, and no reproduction script beyond the repository's own scripts.

## Environment

- **Run:** 2026-11-08, branch `fix/314-identity-provider-hardening`, based on `main` at `40d35b0`. L9 still applied on that base as reported: its realm file has no PKCE attribute and no password policy, and `compose.yaml` gives all three test users one shared password with a published default. **Tested revision:** the working tree that became the first commit of this branch on top of `40d35b0` (the commit that adds this record; a commit cannot name its own hash). The images for the live run were built from that tree. The commit was then rebased onto `main` at `38deb90` (#321, #322, #324, #325 and three dependency bumps); the rebase was clean, and the script tests below were run again on the rebased tree with the same results. The live run was not repeated after the rebase: of the files this change touches, those commits changed only `compose.yaml` (outside the Keycloak service) and other findings' sections of the audit report.
- **Stack:** the local Compose stack (`compose.yaml`) with Keycloak 26.7.3, the repository realm and synthetic users only, on macOS with Docker Desktop.
- **Toolchain:** Gradle Wrapper with the Java 26 toolchain; Bash script tests with a stubbed `docker`.

## Fresh path

The `.env` used for the run predated #314 and had none of the three password variables.

| Check | Outcome |
| --- | --- |
| `./scripts/dev-env.sh up` on that `.env` | `check_env` reported `Generated test user passwords in .env: DOKENE_TEST_OWNER_PASSWORD DOKENE_TEST_OPERATOR_PASSWORD DOKENE_TEST_VIEWER_PASSWORD`, names only. The three values are 32 characters each and all different |
| Keycloak recreated with the new environment | The realm settings script reported every config value `ok` and `set password for <user> from <variable>` for all three users |
| `./scripts/dev-env.sh doctor` afterwards | Exit `0`; nothing generated again |
| `./scripts/dev-env.sh up --seed` | All three users logged in through the BFF with their own password; memberships seeded |
| Keycloak entrypoint with `DOKENE_TEST_VIEWER_PASSWORD` empty | Refuses to start: `Error: Set DOKENE_TEST_VIEWER_PASSWORD in .env (./scripts/dev-env.sh up generates it)`, exit `1` |

## Upgrade path

Keycloak imports the realm file only when the realm does not exist yet. The upgrade was verified against a Keycloak container created before this change, holding a realm imported from an earlier `main`, started without recreating it:

| Step | Outcome |
| --- | --- |
| `keycloak-apply-realm-settings.sh --check` | Exit `1`. Drift on `backchannel.logout.url`, `backchannel.logout.revoke.offline.tokens`, `pkce.code.challenge.method` (unset, expected `S256`), `ssoSessionMaxLifespan` (36000, expected 28800) and `passwordPolicy` (unset); notes that passwords are not checkable |
| Apply on that container | The five values updated and read back; then exit `3` with `Error: DOKENE_TEST_<ROLE>_PASSWORD is empty in the keycloak container; recreate it with ./scripts/dev-env.sh up.` and `Error: could not set password for <user>` for each user, because that container predates the variables |
| `--check` again | Every value `ok`, exit `0` |
| `./scripts/dev-env.sh up` | Container recreated with the variables; passwords set; `up` exit `0` |

## PKCE on the provider side

Probes against Keycloak's authorization endpoint, using the authorization URL the BFF itself produced:

| Request | Outcome |
| --- | --- |
| As sent by the BFF (`code_challenge_method=S256`) | `200`, login form |
| `code_challenge` and `code_challenge_method` removed | `302` back to the client with `error=invalid_request` (`Missing parameter: code_challenge_method`), no login form |
| `code_challenge_method=plain` | `302` with `error=invalid_request` (`code challenge method is not matching the configured one`), no login form |

Keycloak's event log recorded both refusals as `LOGIN_ERROR` with `error="invalid_request"` for `dokene-bff`.

## Credentials and password policy

| Check | Outcome |
| --- | --- |
| `./scripts/verify-keycloak-bff-e2e.sh` | 11 of 11 steps passed, including the PKCE refusals, `testuser` refused with the VIEWER password (login form shown again, no authorization code), and `testoperator` and `testviewer` reaching the BFF callback with their own passwords |
| Keycloak Admin CLI sets an 11 character password for `testviewer` | Refused: `Invalid password: minimum length 12. [invalidPasswordMinLengthMessage]`; the e2e run afterwards still passed with the unchanged password |
| Host process list sampled every 30 to 50 ms during an apply run, compared against the three values | 121 samples; the `set-password` exec for `testuser` was visible in 19 and for `testviewer` in 20; no sample contained a password value |
| Container logs (Keycloak, backend, oidc-bridge, frontend) and every script output of the run, compared against the three values | No match |

## Automated coverage

| Acceptance item | Test |
| --- | --- |
| Realm JSON: S256 on `dokene-bff`, the exact password policy (also equal to the apply script's constant), `${env.DOKENE_TEST_*_PASSWORD}` placeholders and never literals; confidential client, no implicit flow, no direct access grants, registration off, brute force protection on, `sslRequired: external` | `scripts/tests/keycloak-realm-config-test.sh`, each rule also checked against a mutated realm that must fail |
| Script: drift reporting, idempotence, PKCE and policy updates, password step by variable name only, exit `3` on an empty variable or a refused password naming the user and never the value, a failed exec | `scripts/tests/keycloak-apply-realm-settings-test.sh` |
| Compose: one variable per user, no default; entrypoint substitution and refusal of an empty value; `check_env` generation, upgrade, keeping existing values, the 12 character minimum and the warning for the unused `DOKENE_TEST_USER_PASSWORD` | `scripts/tests/dev-env-smoke.sh`, the five `#314` checks |
| `up` fails when the realm settings script did not set every password (exit `3`, which a normal run also reports when a step before the passwords fails) and only warns on any other status | `scripts/tests/dev-env-smoke.sh`, checked against two mutated copies of `dev-env.sh` that must fail |
| `up` requires `jq` in preflight even without `--seed`, because the realm settings script cannot run without it (Codex review finding on PR #368) | `scripts/tests/dev-env-smoke.sh`, `check_tools` with `jq` stubbed absent |
| BFF login with S256 PKCE, session, CSRF and logout unchanged | `OidcBrowserSessionIntegrationTest` (19 tests) and `BffTenantBoundarySecurityIntegrationTest` (8 tests), 0 failures |

`keycloak-realm-config-test.sh` and `keycloak-apply-realm-settings-test.sh` passed. `dev-env-smoke.sh` passed every check except the #308 secret files check, which fails on macOS on `main` as well: the test reads the directory mode with `stat -f %Lp`, which drops the sticky bit. That is a defect of the test, unrelated to this change, tracked separately; it passes on the Linux runner.

## Evidence scripts that predate this change

The scripts under `docs/verification/issue-119-evidence/`, `issue-120-evidence/`, `issue-121-evidence/`, `issue-122-evidence/`, `issue-149-evidence/` and `phase-3-evidence/` are frozen evidence of earlier runs. They still read `DOKENE_TEST_USER_PASSWORD` or fall back to the old shared password, so they no longer log in against a stack started after #314 without adjusting the password source. They were left as recorded.

## Residuals

- The realm stays a local template. A shared deployment needs its own realm process (password history, a breached password blocklist, MFA for OWNER, admin console exposure, no imported test users), listed in `infra/docker/README.md`.
- PKCE is required by a per client attribute on `dokene-bff`. A second OIDC client would need the same attribute, or a realm wide client policy instead.
- `scripts/seed-local-qa.sh` and the `verify-issue-58`, `59` and `61` scripts still pass the test password to `curl` as a command line argument, visible in the host process list while `curl` runs. The values are random and local only; moving them to standard input is a follow up.
