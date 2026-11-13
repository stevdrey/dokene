---
name: operator-journey-verification
description: Use when a change alters an operator-visible journey whose effect only a running local stack can observe (real BFF session, CSRF, Keycloak roles, deployed image, migrations under the runtime role). Run the journey's executable check against ./scripts/dev-env.sh, record a sanitized journey run, and report automated, manual, and not-run steps separately. Optional, risk-triggered practice; it adds no gate.
---

# Operator Journey Verification

## When to use

This is an optional practice from [Epic #371](https://github.com/stevdrey/dokene/issues/371), adapted in Dokene's own words from the `create-verification-skill` practice that the [pstack applicability audit](../../../docs/verification/pstack-applicability-audit.md) marked **Adapt**. The [Issue #376 decision](../../../docs/verification/issue-376-benchmark-decision.md) adopted it as an optional practice for this narrow trigger, because its proof run caught a security-chain regression that the full backend suite missed; it is not a default step.

The default is not to run it. Run it, and say why, when **both** hold:

- the change alters an operator-visible journey that has a journey file under [`journeys/`](journeys/); and
- the effect is observable only on the running local stack: the real BFF security chain (OIDC login, session cookie, CSRF), seeded Keycloak roles and memberships, the deployed backend image, or migrations under the runtime database role.

Skip it, and say so in one line, when unit, integration, or system tests already observe the effect. The `@SpringBootTest` system tests replace the security chain with a test chain (for example `PurchaseCurlSystemTest.CurlSecurityConfiguration`), so a change to that chain, to session handling, or to the deployed configuration is not covered by them.

An unavailable stack is not a skip: the journey's checks are `not-run`, and the criterion they support is `blocked`.

## Boundary

This skill runs inside `agent-task-workflow` or, for the verifier, inside `independent-implementation-verification`. It does not replace the tests that `.agents/README.md` requires, the verdict tools, CI, or human review, and it can neither pass nor waive any of them. Its journey record is evidence: implementer's evidence when the implementer runs it, a check in `checks[]` when the verifier runs it.

It never:

- targets anything but the loopback stack from `./scripts/dev-env.sh` (the scripts refuse other origins);
- sends a WhatsApp or provider message, approves or dispatches an outbound message, or uses real customer data; a journey asserts that no outbound message exists instead (invariants 3, 4, 8, 24 and 26 of the [security invariants](../../../docs/security/security-invariants.md));
- relaxes CSRF, authorization, tenant isolation, RLS, CI, or any other control to make a step pass;
- writes evidence inside the candidate worktree (it would change the candidate identity); records go to a scratch directory;
- prints response bodies, tokens, cookies, passwords, names, or phone numbers. Records hold status codes, enum values, counts, booleans, and `X-Request-Id` values.

A controlled regression for a proof run is a temporary local edit that is reverted and rebuilt before any candidate is verified or committed. It is never pushed.

## Procedure

### 1. Doctor

Run the journey's `doctor` command. It checks the tools, that the targets are loopback origins, that the evidence directory is outside the repository, that Keycloak discovery answers `200`, and that the BFF answers `401` without a session. When it fails, start the stack with `./scripts/dev-env.sh up --seed` (add `restart-backend --yes` when the candidate changed backend code, so the image is the candidate). Never work around a failing doctor.

### 2. Run

Run the journey script with `--out <scratch dir>`. The script:

1. logs in the seeded actors through the real OIDC flow and reads their CSRF tokens;
2. builds a fresh, run-unique fixture with invented data, so reruns never collide with earlier runs;
3. executes the observable steps from the journey file and records each one;
4. archives the fixture, deletes its working files, and keeps the journey record.

Exit codes: `0` every step passed, `1` a step failed, `2` a precondition was unavailable (record the journey as `blocked`, not passed).

### 3. Record

The record follows the journey-run format of the [audit](../../../docs/verification/pstack-applicability-audit.md#evidence-formats): `journey`, `fixture`, `actors`, `steps[]` with `action`, `expected`, `actual`, `kind`, `result`, then `outbound_sends` and `cleanup`. A step outside the script, such as a browser check, is `kind: manual` with `result: not-run` until a person or an agent actually performs it and records what was observed. Never mark a browser or manual step `passed` because the API steps passed.

In a verdict, add one `checks[]` entry for the run (`kind: automated`, the command, the result, and the record path), and one entry per manual step that ran. Map failed steps to `findings[]` with the step id and its `X-Request-Id` as the reproduction.

### 4. Triage a failure

Start from the first failed step: later steps often fail as a consequence (a refused request that wrongly succeeded leaves the fixture in another state). Rerun the single journey once to rule out a stale stack; if the failure repeats, it is evidence. Use the step's `X-Request-Id` with `./scripts/dev-env.sh logs backend` when the backend logged the request. An exit `2` is a capability problem: report it and do not retry blindly.

## Proof run

A journey is trusted only after a proof run shows that it passes on the base and **fails** on a controlled regression that it is meant to catch. Record both runs, the regression's diff, and whether the existing test suite caught the same regression, in `docs/verification/`. The first proof run (Issue #375) is [issue-375-operator-journey-pilot.md](../../../docs/verification/issue-375-operator-journey-pilot.md).

## Adding a journey

One journey per Issue. Add `journeys/<name>.md` (scope, fixture, actors, steps with expected results, manual steps, cleanup, and rerun safety), a `scripts/verify-journey-<name>.sh` script that reuses the helpers and safety rules of the existing one, offline tests for its helpers under `scripts/tests/`, and a proof run. Do not generate journeys in bulk or keep a map of features: add a journey only when an Issue needs it, one at a time.

## Journeys

| Journey | Script | Covers |
| --- | --- | --- |
| [Follow-up disposition](journeys/follow-up-disposition.md) | `scripts/verify-journey-follow-up-disposition.sh` | Due queue and eligibility agreement, VIEWER refusal, CSRF, cross-tenant denial, `If-Match`, idempotent replay, no outbound message |
