# GitHub Actions pinning

Every workflow under `.github/workflows/` runs third party code. A tag such as `@v7` is a movable label: whoever controls it controls what runs in CI, with the job token in hand (and in `dependency-check.yml` also `NVD_API_KEY`). This page states the rule that closes audit finding M5 of the [security audit of 2026-11-02](security-audit-2026-11-02.md) (issue [#309](https://github.com/stevdrey/dokene/issues/309), spec 0015), how to pin an action by hand, how Dependabot keeps the pins current, what a reviewer checks on a bump, and the two repository settings the owner can turn on. Security invariant 31 in [security-invariants.md](security-invariants.md) points here.

## The rule

Every `uses:` value in `.github/workflows/` that names something outside this repository (a step action or a job level reusable workflow) has the form

```yaml
- uses: owner/repo[/path]@<40 lowercase hex commit sha> # <tag>
```

- The SHA is the commit the release tag points at, proven with `git ls-remote` against the upstream repository (below), never a commit that merely exists.
- The comment is exactly the tag the SHA was resolved from, as one token, and nothing else. It is what a reviewer reads and what Dependabot rewrites on a bump.
- Every reference to the same action repository (`owner/repo`, the part before any subpath) carries the same SHA in every workflow. `actions/cache/restore` and `actions/cache/save`, and `github/codeql-action/init` and `analyze`, break when their versions drift apart.
- Tags, branches, short SHAs, uppercase SHAs, pins without the tag comment, and `docker://image:tag` references are all forbidden.

Exceptions: an action or reusable workflow in this repository (`uses: ./...`) is versioned with the tree and needs no pin; a `docker://` reference passes only with an `@sha256:` digest of 64 lowercase hex characters, which is its pin. There are no other exceptions.

## Enforcement

`scripts/check-workflow-pins.sh [dir]` applies the rule to every `*.yml` and `*.yaml` file directly under `dir` (default `.github/workflows`) and prints one `<dir>/<file>:<line>: <reference>` line per violation. Exit 0 means every reference is pinned, 1 means at least one violation, 2 means the directory holds no workflow file. It needs bash, `find`, `sort` and `awk`, no network and no Docker, and runs the same on the bash 3.2 that macOS ships and on the runner.

`.github/workflows/workflow-pins.yml` runs `scripts/tests/check-workflow-pins-test.sh` and then the check on every pull request and every push to `main`, with no `paths:` filter, so the owner can mark the `Workflow pins` check as required without it sitting pending on a pull request that touches no workflow. The test compares the check's exact output against fixture directories under `scripts/tests/fixtures/check-workflow-pins/`, one per rule.

Limits of the check, by design: it is line based. It inspects only lines that start with optional whitespace, an optional list dash and `uses:`, and skips comment lines. A `uses:` string inside a `run: |` block or a `with:` value can produce a false positive, which is fixed by rewording; a flow style mapping (`{uses: x}`) is not matched, so that form is forbidden and review covers it. The comment rule is "one token", so a single word that is not a tag passes the mechanical check; the reviewer step below catches it. Until the owner marks the check required, a red `Workflow pins` run is visible to every reviewer but does not block a merge.

## Manual pin steps

Use these once for every new action, and whenever you bump one by hand.

1. For a reference `owner/repo[/path]@<ref>`, list the upstream tags once:
   ```sh
   git ls-remote --tags https://github.com/<owner>/<repo>
   ```
   An annotated tag appears twice, as `refs/tags/<tag>` (the tag object) and `refs/tags/<tag>^{}` (the commit it points at). A lightweight tag appears once, already as a commit.
2. Find the commit C that `<ref>` points at: its `^{}` line, or its plain line when there is none.
3. Choose the tag to record: the exact release tag (`vX.Y.Z` form) whose commit is C; if several, the highest version; if the repository publishes no exact tag for C, keep `<ref>` itself.
4. Write `@C # <chosen tag>`. If another workflow already pins this repository, it must already be C. Otherwise stop: the two tags point at different commits and the repository must be brought to one version first, by Dependabot in its own pull request, not inside your change.
5. Add the evidence row to the verification record of the change (table format below), then run `./scripts/check-workflow-pins.sh`.

Resolve the version you already run, do not upgrade while pinning. Upgrading is Dependabot's job in a later pull request, so a pinning change stays a pure security change.

## Evidence table format

One row per reference, in the verification record of the change that added or moved it:

| workflow:line | action | previous ref | recorded tag | tag kind | commit SHA | resolved on | method |
| --- | --- | --- | --- | --- | --- | --- | --- |

`tag kind` is `annotated` or `lightweight`, `resolved on` is the UTC date, `method` is `git ls-remote`. The first table is in [the issue #309 verification record](../verification/issue-309-github-actions-pinning-verification.md).

## How Dependabot keeps the pins current

The `github-actions` entry in `.github/dependabot.yml` runs weekly, groups every action bump into one pull request (`groups: github-actions: patterns: ["*"]`) and waits seven days after a release before proposing it (`cooldown: default-days: 7`). Dependabot rewrites both the SHA and the tag comment, so a bump arrives as `@<new sha> # <new tag>` and the check stays green. The cooldown applies to version updates only; a Dependabot security update driven by an advisory on an action is not delayed, and an urgent bump can always be made by hand through the steps above.

## Reviewer step on a bump

For each changed `uses:` line in a Dependabot (or hand made) bump:

1. Run step 1 against the new tag in the comment, or directly:
   ```sh
   git ls-remote https://github.com/<owner>/<repo> 'refs/tags/<tag>' 'refs/tags/<tag>^{}'
   ```
2. Confirm the SHA on the line equals the `^{}` commit (annotated tag) or the plain line (lightweight tag).
3. Confirm the comment is an exact release tag, not a major alias or prose.
4. Confirm `Workflow pins` and the workflows the bump touches are green, then merge.

Why tag equality and not "the commit exists": GitHub's commits endpoint also returns commits that exist only in a fork of the same network (an impostor commit), so existence proves nothing. `git ls-remote` against the upstream repository lists the tag and its commit in one call.

## Settings the repository owner can turn on

Neither is required by this rule, and neither can be set from the contributing side; both add a second layer.

- **Require actions to be pinned to a full length commit SHA** (repository Settings, Actions, General, under "Actions permissions"): the platform refuses to run a workflow that references an action by tag.
- **Mark `Workflow pins` as a required status check on `main`** (Settings, Branches, the `main` rule): a red check then blocks the merge. The workflow runs on every pull request with no paths filter, so it never sits pending.

## Related

- Audit finding L13 (the Trivy and gitleaks binaries the actions download at run time are still selected by the actions' defaults) is outside this rule and tracked separately.
- A broader workflow linter (`actionlint` or `zizmor`, itself pinned under this rule) would catch expression injection, excessive permissions and impostor commits; it is a separate decision.
