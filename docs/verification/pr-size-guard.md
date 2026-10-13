# Pull Request Size Guard

Dokene favors small, independently reviewable pull requests. The
[`Pull Request Size Guard`](../../.github/workflows/pr-size-guard.yml) workflow
checks every pull request targeting `main` (including draft PRs and documentation-only
changes) without checking out or executing the proposed source code.

## Policy

| Changed files in the PR | Result |
| --- | --- |
| 0–30 | Pass |
| 31–40 | Pass with a warning |
| 41 or more | Fail: split the PR before merging |

The count comes from GitHub's `pulls.get().data.changed_files` metadata. It covers
all changed files, including added, modified, deleted, renamed, test, migration,
documentation, and generated files. Exactly **40** files are allowed. The workflow
uses a read-only `pull-requests: read` token and makes no source checkout.

Do not delete meaningful tests, security controls, or required documentation to
pass the limit. Instead, divide larger changes into coherent, independently testable
PRs, with explicit dependencies if a sequence is necessary. Each PR should
preserve a buildable and secure intermediate state.

## Enable required enforcement on `main`

**Repository admin action required:** a failing workflow alone does not prevent
merging until its job becomes a required status check.

1. Merge the workflow PR so the check definition is present on `main`. Ensure
   the `Pull Request Size Guard / PR file count` job has run on at least one PR.
2. Open **Settings → Rules → Rulesets → New ruleset → New branch ruleset**.
3. Name the ruleset **Require PR size guard**, set enforcement to **Active**,
   and target the branch `main` (not all branches).
4. Enable **Require a pull request before merging** to protect against direct
   pushes that would skip PR checks, unless an equivalent rule already enforces it.
5. Enable **Require status checks to pass before merging**. Add the job named
   **PR file count** from **GitHub Actions** as a required status check.
6. Save the ruleset. Avoid routine bypass actors if the limit must apply to
   administrators and collaborators alike.
7. Verify with a PR changing 41 files: the job must fail and GitHub must block
   merging. Verify that a PR changing 40 files succeeds.

If the status check is not offered yet, run a PR that targets `main`, then
refresh the ruleset editor. The required check is the **job name**
(`PR file count`), not merely the workflow name (`Pull Request Size Guard`).
Keep this name unique across workflows.

GitHub Actions executes after a PR opens or updates; the protection blocks
**merging**, not PR creation. Warnings at 31–40 files do not block merging.
Branch-protection/ruleset configuration is an administrator action and is not
deployed by the workflow PR itself.

## Troubleshooting

- **Check absent on a PR:** confirm the PR targets `main`, that GitHub Actions
  is enabled, and that the job ran after the workflow became available.
- **Limit appears wrong:** inspect the PR's *Files changed* tab, which includes
  more than just application source files.
- **API failure:** the status fails closed instead of treating an unknown count
  as a pass.
