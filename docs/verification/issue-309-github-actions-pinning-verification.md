# Issue #309 GitHub Actions pinning: verification record

Status: single PR. It closes audit finding M5 of the [security audit of 2026-11-02](../security/security-audit-2026-11-02.md): every workflow under `.github/workflows/` referenced its actions by a movable tag, so whoever controlled a tag controlled what ran in CI with the job token (and `NVD_API_KEY` in `dependency-check.yml`). The rule is in [github-actions-pinning.md](../security/github-actions-pinning.md) and security invariant 32.

This record is sanitized: it names commit SHAs, tags, file lines, commands and run identifiers only. It contains no token, key or secret.

## Environment

- **Run:** 2026-11-09 (UTC) for the pins and the evidence table, 2026-11-10 (UTC) for the review corrections, branch `fix/309-pin-github-actions`, based on `main` at `38deb90`. The tree was rechecked on that base before the change: `grep -n "uses:" .github/workflows/*.yml` listed 26 references in nine workflows, all by tag, so M5 still applied as reported. The spec counted 27; the tree held 26, and the twenty seventh reference in the table below is the `checkout` step of the new `workflow-pins.yml`.
- **Tested revision:** the commit named in [CI runs on the pull request](#ci-runs-on-the-pull-request). The local results below were run on the working tree that became that commit. A record cannot name its own commit hash, so the CI section is added in a later commit that changes only this record.
- **Review corrections:** the check, its test and fixtures, `workflow-pins.yml` and the documentation were corrected after the review of `6cb083d3` on pull request #369 (ten findings); the 27 pins did not change. The branch was merged with `main` at `6c54473` at the same time, which moved the invariant from 31 to 32. Results from the first revision that were not rerun are marked as such in the results table: the evidence table and its `git ls-remote` resolution (no pin or workflow line changed) and the run of the check on the base tree. Every other result is from the corrected tree.
- **Toolchain:** bash 5.3.15 (Homebrew) and `/bin/bash` 3.2.57 (macOS) for the check and its test, with the system `find`, `sort`, `awk`, `cut`, `tr` and `dirname`; `git` 2.x for `git ls-remote`; ruby's `YAML.load_file` to parse the two edited YAML files. No Docker, no Gradle, no network beyond `git ls-remote` to `github.com`.

## Evidence table

Resolved with `git ls-remote --tags https://github.com/<owner>/<repo>` on 2026-11-09 and reconfirmed with `git ls-remote https://github.com/<owner>/<repo> 'refs/tags/<tag>' 'refs/tags/<tag>^{}'` (AC-2). For an annotated tag the SHA is the `^{}` line; for a lightweight tag it is the plain line. Each floating tag (`v7`, `v6`, ...) peeled to the same commit as the recorded exact tag, so no action changed version.

| workflow:line | action | previous ref | recorded tag | tag kind | commit SHA | resolved on | method |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `backend.yml:24` | `actions/checkout` | `v7` | `v7.0.1` | lightweight | `3d3c42e5aac5ba805825da76410c181273ba90b1` | 2026-11-09 | `git ls-remote` |
| `backend.yml:25` | `actions/setup-java` | `v6` | `v6.1.0` | lightweight | `b843ebf9b913146b38da2eaa7aeddf76074ae1ad` | 2026-11-09 | `git ls-remote` |
| `backend.yml:29` | `gradle/actions/setup-gradle` | `v6` | `v6.4.1` | annotated | `8da46b539cd8fe6ab27ca75128919a18d757604c` | 2026-11-09 | `git ls-remote` |
| `codeql.yml:28` | `actions/checkout` | `v7` | `v7.0.1` | lightweight | `3d3c42e5aac5ba805825da76410c181273ba90b1` | 2026-11-09 | `git ls-remote` |
| `codeql.yml:32` | `actions/setup-java` | `v6` | `v6.1.0` | lightweight | `b843ebf9b913146b38da2eaa7aeddf76074ae1ad` | 2026-11-09 | `git ls-remote` |
| `codeql.yml:39` | `gradle/actions/setup-gradle` | `v6` | `v6.4.1` | annotated | `8da46b539cd8fe6ab27ca75128919a18d757604c` | 2026-11-09 | `git ls-remote` |
| `codeql.yml:42` | `github/codeql-action/init` | `v4` | `v4.39.3` | annotated | `33d7b85da1a04171e211befe2c739303b9a4c066` | 2026-11-09 | `git ls-remote` |
| `codeql.yml:54` | `github/codeql-action/analyze` | `v4` | `v4.39.3` | annotated | `33d7b85da1a04171e211befe2c739303b9a4c066` | 2026-11-09 | `git ls-remote` |
| `dependency-check.yml:38` | `actions/checkout` | `v7` | `v7.0.1` | lightweight | `3d3c42e5aac5ba805825da76410c181273ba90b1` | 2026-11-09 | `git ls-remote` |
| `dependency-check.yml:40` | `actions/setup-java` | `v6` | `v6.1.0` | lightweight | `b843ebf9b913146b38da2eaa7aeddf76074ae1ad` | 2026-11-09 | `git ls-remote` |
| `dependency-check.yml:45` | `gradle/actions/setup-gradle` | `v6` | `v6.4.1` | annotated | `8da46b539cd8fe6ab27ca75128919a18d757604c` | 2026-11-09 | `git ls-remote` |
| `dependency-check.yml:49` | `actions/cache/restore` | `v6` | `v6.1.0` | lightweight | `55cc8345863c7cc4c66a329aec7e433d2d1c52a9` | 2026-11-09 | `git ls-remote` |
| `dependency-check.yml:63` | `actions/cache/save` | `v6` | `v6.1.0` | lightweight | `55cc8345863c7cc4c66a329aec7e433d2d1c52a9` | 2026-11-09 | `git ls-remote` |
| `dependency-check.yml:70` | `actions/upload-artifact` | `v7` | `v7.0.2` | lightweight | `cf430e030ddbb5b0abf93d22962f4752f3646cd9` | 2026-11-09 | `git ls-remote` |
| `frontend.yml:30` | `actions/checkout` | `v7` | `v7.0.1` | lightweight | `3d3c42e5aac5ba805825da76410c181273ba90b1` | 2026-11-09 | `git ls-remote` |
| `frontend.yml:31` | `actions/setup-node` | `v7` | `v7.1.0` | lightweight | `949feb2413d6458794dcd2491c4babbbce0c15c1` | 2026-11-09 | `git ls-remote` |
| `frontend.yml:44` | `actions/checkout` | `v7` | `v7.0.1` | lightweight | `3d3c42e5aac5ba805825da76410c181273ba90b1` | 2026-11-09 | `git ls-remote` |
| `pr-preflight-tests.yml:26` | `actions/checkout` | `v7` | `v7.0.1` | lightweight | `3d3c42e5aac5ba805825da76410c181273ba90b1` | 2026-11-09 | `git ls-remote` |
| `pr-preflight-tests.yml:27` | `actions/setup-node` | `v7` | `v7.1.0` | lightweight | `949feb2413d6458794dcd2491c4babbbce0c15c1` | 2026-11-09 | `git ls-remote` |
| `pr-size-guard.yml:18` | `actions/github-script` | `v9` | `v9.0.0` | annotated | `3a2844b7e9c422d3c10d287c895573f7108da1b3` | 2026-11-09 | `git ls-remote` |
| `publish-wiki.yml:23` | `actions/checkout` | `v7` | `v7.0.1` | lightweight | `3d3c42e5aac5ba805825da76410c181273ba90b1` | 2026-11-09 | `git ls-remote` |
| `scripts.yml:24` | `actions/checkout` | `v7` | `v7.0.1` | lightweight | `3d3c42e5aac5ba805825da76410c181273ba90b1` | 2026-11-09 | `git ls-remote` |
| `security.yml:18` | `actions/checkout` | `v7` | `v7.0.1` | lightweight | `3d3c42e5aac5ba805825da76410c181273ba90b1` | 2026-11-09 | `git ls-remote` |
| `security.yml:23` | `gitleaks/gitleaks-action` | `v3` | `v3.0.0` | lightweight | `e0c47f4f8be36e29cdc102c57e68cb5cbf0e8d1e` | 2026-11-09 | `git ls-remote` |
| `security.yml:31` | `actions/checkout` | `v7` | `v7.0.1` | lightweight | `3d3c42e5aac5ba805825da76410c181273ba90b1` | 2026-11-09 | `git ls-remote` |
| `security.yml:34` | `aquasecurity/trivy-action` | `v0.36.0` | `v0.36.0` | annotated | `ed142fd0673e97e23eac54620cfb913e5ce36c25` | 2026-11-09 | `git ls-remote` |
| `workflow-pins.yml:19` | `actions/checkout` | new file | `v7.0.1` | lightweight | `3d3c42e5aac5ba805825da76410c181273ba90b1` | 2026-11-09 | `git ls-remote` |

Ten repositories, ten SHAs, one SHA per repository across all ten workflows (AC-1). `trivy-action` was already on an exact tag, so its recorded tag is unchanged.

## Automated coverage

| Acceptance item | Check |
| --- | --- |
| The pinned tree passes; the default directory is `.github/workflows` | `check-workflow-pins-test.sh`: "the real workflows are all pinned", "the default directory is .github/workflows" |
| Each forbidden class is reported with the exact `file:line: reference` line: tag, branch, short SHA, uppercase SHA, full SHA without comment, full SHA with a prose comment, quoted tag, job level reusable workflow on a tag, `.yaml` file, two violations in one file (both lines), two SHAs for one repository (the second line), `docker://` without a digest, a double quoted, single quoted or spaced `uses` key (all three lines) |
| A floating tag in a composite action reached through a symbolic link under the actions directory is reported | built at run time in the test's temporary directory | one fixture directory each under `scripts/tests/fixtures/check-workflow-pins/`, expected output hard coded in the test |
| Two SHAs for one repository in two files, the repository spelled in another case in the second, report only the second file's line in its original spelling | fixture `cross-file-sha` (`a.yml`, `b.yml`) |
| A `./` reference outside `.github/actions` and `.github/workflows`, a `./` reference that climbs out with `..`, a `./` reference into a subdirectory of `.github/workflows`, and a floating tag inside a local composite action's `action.yml` are all reported; the `./.github/actions/setup` reference and the job level `./.github/workflows/reusable.yml` beside them are not | fixture `composite` (`workflows/workflow.yml`, `actions/setup/action.yml`) |
| An empty directory exits 2 | fixture `empty-dir` |
| A `./.github/actions/` local action, a `docker://` reference with a 64 hex digest and a commented out `uses:` line pass | fixtures `local-action`, `docker-digest`, `commented-out` |
| The check runs on every pull request and push to `main` with `contents: read`, a 5 minute timeout and a pinned `checkout`, as the first case of the test | `.github/workflows/workflow-pins.yml` (AC-4) |

## Results

| Command | Kind | Result |
| --- | --- | --- |
| `./scripts/check-workflow-pins.sh` on the base tree (before the pins) | manual | exit 1, 26 lines, one per tag reference, matching the `grep` count; run with the first revision's script, not rerun after the corrections |
| `./scripts/check-workflow-pins.sh` on the pinned tree | manual | exit 0, no output |
| `./scripts/tests/check-workflow-pins-test.sh` (bash 5.3) | automated | 22 checks passed, `All checks passed` |
| `/bin/bash scripts/tests/check-workflow-pins-test.sh` (bash 3.2) | automated | 22 checks passed |
| `/bin/bash scripts/check-workflow-pins.sh` on the pinned tree (bash 3.2) | manual | exit 0, no output |
| The new test cases on the tree before the script was corrected | automated | the key spellings, composite and cross file cases failed with exit 0 where 1 was wanted, and the symbolic link case and the `.github/workflows` subdirectory line failed before the correction that followed the first independent verification, so they test the corrections |
| The check on scratch workflows outside the tree: the three `uses` key spellings; a `./scripts/x`, a `..` escape and a composite action on a floating tag; `Actions/Cache/restore` and `actions/cache/save` on two SHAs | manual | exit 1 with every expected line in each case; all three exited 0 on `6cb083d3` |
| The check on a scratch workflow with an arbitrary 40 hex SHA beside `# v7.0.1`, and a floating `# v7` comment | manual | exit 0: the script does not prove tag equality, as the pinning page and invariant 32 now state |
| `git ls-remote https://github.com/<owner>/<repo> 'refs/tags/<tag>' 'refs/tags/<tag>^{}'` for the ten recorded tags | manual | every SHA in the table equals the peeled commit (annotated) or the plain line (lightweight) (AC-2); run on the first revision, not rerun after the corrections because no pin changed |
| `git diff origin/main -- .github/workflows` filtered for `permissions` and `read`/`write` lines | manual | no existing line changed; the diff is 26 `uses:` lines plus the new file (AC-6) |
| `ruby -ryaml -e 'YAML.load_file(...)'` on `workflow-pins.yml`, `dependabot.yml` and the new fixtures | manual | all parse |

## CI runs on the pull request

Not recorded yet for the corrected revision. The nine runs listed here before were on `50ee41e5862389c0c8bc48eb57cc5cd0f20dd726`, which predates the review corrections, so they are withdrawn rather than carried forward. The tested revision, the run ids and the pinned SHAs the runner downloaded are added here in a commit that changes only this record, after CI has run on the corrected commit. `publish-wiki.yml` runs only on push to `main`; its first post merge run is the proof for that file.

## Not verified here

No live Dependabot run: after merge, the repository's Dependabot page must show no configuration error for the `github-actions` entry, and the next scheduled run should open at most one grouped pull request (AC-5). If the page shows an error, the fix is a follow up pull request on the YAML only; the pins stay. The binaries the Trivy and gitleaks actions download at run time are audit L13 and are not pinned by this change. No live Dependabot pull request either: that `OWASP Dependency Check` runs without `NVD_API_KEY` on one comes from reading the workflow's triggers and `backend/build.gradle`, not from an observed run. None of the three repository settings named in the pinning page (platform SHA requirement, required check, code owner review) can be set from the contributing side, so `.github/CODEOWNERS` was not seen to block a merge.
