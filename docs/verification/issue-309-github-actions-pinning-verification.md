# Issue #309 GitHub Actions pinning: verification record

Status: single PR. It closes audit finding M5 of the [security audit of 2026-11-02](../security/security-audit-2026-11-02.md): every workflow under `.github/workflows/` referenced its actions by a movable tag, so whoever controlled a tag controlled what ran in CI with the job token (and `NVD_API_KEY` in `dependency-check.yml`). The rule is in [github-actions-pinning.md](../security/github-actions-pinning.md) and security invariant 31.

This record is sanitized: it names commit SHAs, tags, file lines, commands and run identifiers only. It contains no token, key or secret.

## Environment

- **Run:** 2026-11-09 (UTC), branch `fix/309-pin-github-actions`, based on `main` at `38deb90`. The tree was rechecked on that base before the change: `grep -n "uses:" .github/workflows/*.yml` listed 26 references in nine workflows, all by tag, so M5 still applied as reported. The spec counted 27; the tree held 26, and the twenty seventh reference in the table below is the `checkout` step of the new `workflow-pins.yml`.
- **Tested revision:** the pull request's head commit, named in the pull request. The pins, the check and the fixtures below were run on the working tree that became that commit. A record cannot name its own commit hash.
- **Toolchain:** bash 5.3.15 (Homebrew) and `/bin/bash` 3.2.57 (macOS) for the check and its test; `git` 2.x for `git ls-remote`; ruby's `YAML.load_file` to parse the two edited YAML files. No Docker, no Gradle, no network beyond `git ls-remote` to `github.com`.

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
| Each forbidden class is reported with the exact `file:line: reference` line: tag, branch, short SHA, uppercase SHA, full SHA without comment, full SHA with a prose comment, quoted tag, job level reusable workflow on a tag, `.yaml` file, two violations in one file (both lines), two SHAs for one repository (the second line), `docker://` without a digest | one fixture directory each under `scripts/tests/fixtures/check-workflow-pins/`, expected output hard coded in the test |
| An empty directory exits 2 | fixture `empty-dir` |
| A `./` local action, a `docker://` reference with a 64 hex digest and a commented out `uses:` line pass | fixtures `local-action`, `docker-digest`, `commented-out` |
| The check runs on every pull request and push to `main` with `contents: read`, a 5 minute timeout and a pinned `checkout` | `.github/workflows/workflow-pins.yml` (AC-4) |

## Results

| Command | Kind | Result |
| --- | --- | --- |
| `./scripts/check-workflow-pins.sh` on the base tree (before the pins) | manual | exit 1, 26 lines, one per tag reference, matching the `grep` count |
| `./scripts/check-workflow-pins.sh` on the pinned tree | manual | exit 0, no output |
| `./scripts/tests/check-workflow-pins-test.sh` (bash 5.3) | automated | 18 checks passed, `All checks passed` |
| `/bin/bash scripts/tests/check-workflow-pins-test.sh` (bash 3.2) | automated | 18 checks passed |
| `git ls-remote https://github.com/<owner>/<repo> 'refs/tags/<tag>' 'refs/tags/<tag>^{}'` for the ten recorded tags | manual | every SHA in the table equals the peeled commit (annotated) or the plain line (lightweight) (AC-2) |
| `git diff -- .github/workflows` filtered for `permissions` and `read`/`write` lines | manual | no line changed; the diff is 26 `uses:` lines plus the new file (AC-6) |
| `ruby -ryaml -e 'YAML.load_file(...)'` on `workflow-pins.yml` and `dependabot.yml` | manual | both parse |

## CI runs on the pull request

Pull request [#369](https://github.com/stevdrey/dokene/pull/369), tested revision `50ee41e5862389c0c8bc48eb57cc5cd0f20dd726` (this record's CI section was added in the commit after it; the pins and scripts are unchanged). Every workflow the pull request triggers passed on that revision (AC-6):

| Workflow | Run id | Conclusion |
| --- | --- | --- |
| Workflow pins | 42650858064 | success (18 test checks passed, check exit 0) |
| Backend CI | 42650857947 | success |
| Frontend CI | 42650857948 | success |
| CodeQL | 42650857872 | success |
| Security Scans | 42650857901 | success |
| OWASP Dependency Check | 42650857882 | success |
| Pull Request Size Guard | 42650857927 | success |
| Script tests | 42650857954 | success |
| Agent PR Preflight Tests | 42650857874 | success |

The runner resolves every action at "Set up job". The `Download action repository` lines across these nine runs name all ten pinned SHAs (`actions/checkout`, `actions/setup-java`, `actions/setup-node`, `actions/github-script`, `actions/cache`, `actions/upload-artifact`, `gradle/actions`, `gitleaks/gitleaks-action`, `aquasecurity/trivy-action`, `github/codeql-action`), each at the SHA in the evidence table; the other references in those lines (`aquasecurity/setup-trivy`, a second `actions/checkout`, two other `actions/cache` commits) are pulled in by the pinned actions themselves, not by this repository's workflows. `publish-wiki.yml` runs only on push to `main`; its first post merge run is the proof for that file.

## Not verified here

No live Dependabot run: after merge, the repository's Dependabot page must show no configuration error for the `github-actions` entry, and the next scheduled run should open at most one grouped pull request (AC-5). If the page shows an error, the fix is a follow up pull request on the YAML only; the pins stay. The binaries the Trivy and gitleaks actions download at run time are audit L13 and are not pinned by this change. Neither repository setting named in the pinning page (platform SHA requirement, required check) can be set from the contributing side.
