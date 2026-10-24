# Dokene

Dokene is an open-source, security-first customer follow-up SaaS for small businesses. It is inspired by the Bribri verb `dö̀knẽ`, meaning “to return”.

## Project status

**Active development — not production-ready.** Dokene is still being built. Some features and workflows are incomplete, and APIs, configuration, and documentation may change as development continues. The setup below is intended for local development and testing, not for use with real customer data or live messaging in production.

See the [Roadmap](docs/wiki/Roadmap.md) for planned phases, and follow progress through [Issues](../../issues) and
[Pull Requests](../../pulls).

## Repository structure

- `backend/` — Spring Boot backend
- `frontend/` — React + TypeScript frontend
- `docs/` — architecture, ADRs, and security documentation
- `infra/` — local development infrastructure (no supported production deployment yet)
- `.github/` — CI and repository automation

## Local environment

The whole stack (PostgreSQL, Keycloak, backend, frontend) runs in Docker so every contributor gets the same
runtime configuration. Only Docker (Compose 2.17+) is required on the host:

```bash
./scripts/dev-env.sh up --seed     # preflight checks, .env generation, build, start, QA seed
./scripts/dev-env.sh status        # container health and HTTP checks
./scripts/dev-env.sh restart-backend  # recreate backend + oidc-bridge only
./scripts/dev-env.sh down          # stop (data kept); `reset` also deletes local data
```

Open http://localhost:5173 and sign in as `testoperator` / `testuser` / `testviewer` (password:
`DOKENE_TEST_USER_PASSWORD`, default `testpassword`). Details, host-run development and troubleshooting:
[`infra/docker/README.md`](infra/docker/README.md) and [ADR 0022](docs/adr/0022-containerized-local-environment.md).

## AI coding-agent PR preflight (Claude Code and Codex)

Dokene's [PR size policy](docs/verification/pr-size-guard.md) allows **40 changed files maximum** per PR: up to 30 passes,
31–40 warns, and 41+ blocks. Local hooks warn or block *before a shell-based publish command*; the required GitHub
Actions **PR file count** check remains the authoritative merge protection.

**Prerequisites:** Node.js 22+, Git, a checkout with an `origin/main` reference, and either Claude Code or Codex with
project hooks enabled. No Node package install or API keys are needed.

From the repository root, refresh the baseline before starting an agent session or publishing, then run the manual
preflight as needed:

```bash
git fetch origin main
node .agents/scripts/pr-size-preflight.mjs
node --test .agents/scripts/pr-size-preflight.test.mjs
```

The preflight counts unique committed, staged, unstaged, and non-ignored untracked paths relative to the merge base of
`origin/main`. This is an estimate of the eventual PR diff, not a substitute for the GitHub check. If the baseline is
missing, the preflight fails closed.

A deliberately stacked PR is measured against the branch it targets, after fetching that branch:

- `gh pr create --base target-branch` (also `-B`, `-Btarget-branch` or `--base=`) is measured against
  `origin/target-branch`. As for `gh`, the value is a branch name: `--base=origin/x` means a branch named `origin/x`.
  Text inside other options' values, such as a title containing `--base`, is never read as the base.
- Without `--base`, `gh pr create` targets the branch's `branch.<branch>.gh-merge-base` setting when one is set, and
  the preflight measures against it too.
- `git push` uses the base the current branch declares with
  `git config branch.<branch>.prPreflightBase origin/target-branch`.
- The manual command accepts `--base origin/target-branch`, and `PR_PREFLIGHT_BASE=origin/target-branch` applies to
  any run.

Every base, however it is given, must resolve to an existing commit other than the branch being published (its own
`origin/` ref); otherwise the preflight fails closed. The `PR file count` check in CI runs for pull requests into any
branch, so a stacked PR is also counted against the branch it actually targets.

### Claude Code

1. Open the repository as a trusted project and review `.claude/settings.json`. It contains a project-scoped
   `PreToolUse` hook for shell tool calls, pointing to the canonical `.agents/scripts/pr-size-preflight.mjs`.
2. Start or restart Claude Code from the repository. Approve/trust the project hook if prompted; inspect it with
   `/hooks` in Claude Code.
3. When Claude Code attempts `git push` or `gh pr create` through Bash, the hook checks local changes and
   blocks the call with a split-PR instruction if the limit is exceeded.

No additional `~/.claude/settings.json` file or plugin installation is required. This shared setting is meant only for
this repository; never approve repository hooks you have not inspected.

### OpenAI Codex (CLI and desktop)

1. Review `.codex/hooks.json`. Only this Codex hook configuration is versioned; personal `.codex/` files remain
   ignored by Git. It points to the same preflight under `.agents/scripts/`.
2. Trust the repository's Codex project configuration and approve the `PreToolUse` hook when Codex requests review.
   Start the **interactive Codex CLI** from the repository root and use `/hooks` to inspect hooks; if the desktop
   app shows a hook but does not offer a working trust prompt, grant trust through the interactive CLI, then restart
   the desktop session. An untrusted hook may be silently skipped.
3. Verify the hook with a disposable branch or run the manual preflight command above before pushing.

Codex must have project hooks enabled in the installed version; enterprise-managed settings can disable project
hooks. See the [Codex hook reference](https://learn.chatgpt.com/docs/hooks) and the
[Claude Code hook reference](https://code.claude.com/docs/en/hooks) for current client setup.

**Limitations:** These `PreToolUse` hooks target Bash calls containing `git push` or `gh pr create`; other publication
routes (including direct GitHub/MCP tools, non-shell UI actions, or unsupported Codex clients) are not intercepted.
Use the manual command when uncertain. Do not delete tests or documentation merely to fit the limit; split the
implementation into independently reviewable PRs. The protected GitHub status check still blocks oversized merges.

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
