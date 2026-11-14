# Issue #376 benchmark pilot and adoption decision

Status: [Epic #371](https://github.com/stevdrey/dokene/issues/371), step 5 of 5. This record compares the existing verification workflow with the practices adapted in Issues #373 to #375, on the same candidates and budgets, and decides what to keep. It changes no application behavior.

The record is sanitized: test names, counts, statuses, and redacted probe output only. All data in the probes is synthetic. No live service, provider, or message send was involved.

## Decision

**No-go** for making any adapted practice compulsory or a default step. **Go** for keeping the adversarial pass and the operator journey as optional, risk-triggered practices: the journey has evidence of a defect class the existing suite misses, and the pass gave stronger proof on its one run at a bounded cost. The failing-before evidence format stays optional and unchanged, and blast-radius analysis stays a pilot with no new evidence.

| Practice | Decision | Evidence | What changes now |
| --- | --- | --- | --- |
| Adversarial pass ([#374](issue-374-adversarial-review-exercise.md)) | **Revise**, keep optional | This benchmark did not test its detection: no trigger matched K1 or K2, so the pass did not run on either seeded defect, and the pilot arm found both through the ordinary hunt, as the baseline did. Its one run (K3, no defect) added a controlled equivalence probe and raised no false positive. The arm totals differ by about 7.6% in tokens, which one run per arm cannot separate from run-to-run variance. The #374 exercise showed the reproduction format and blocking bar work. | Wording only: it stays optional, and the revisions below are proposed, not applied. |
| Runnable operator journey ([#375](issue-375-operator-journey-pilot.md)) | **Adopt** as optional for its narrow trigger | #375 proof run: a CSRF regression (R2) passed the full backend suite (2630 tests, 0 failures) and failed the journey at S6. That is a defect class the suite cannot observe. Not re-run here. | The skill index drops the "Pilot" label; the trigger stays narrow and optional. |
| Blast-radius analysis ([#373](issue-373-blast-radius-exercise.md)) | **Keep as pilot**, no adoption | Not exercised by this benchmark: it is an implementer step, and the candidates were fixed inputs. The #373 exercise was written with the merged PR in view, so it shows the format, not detection. | Wording only: still optional and unproven. |
| Failing-before evidence (`tdd`, format only) | **Keep as optional format** | Not measured. `.agents/README.md` already requires tests. | None. |
| `correct`, `architect`, `swarm`, `poteto-mode` | **Reject** (unchanged from the [audit](pstack-applicability-audit.md#decision-matrix)) | No new evidence. | None. |

No productivity claim follows from this pilot. Three candidates, one module, and one model are too few to estimate a detection rate, and nothing here measured human review time.

## Design

### Arms

Both arms ran the verifier role of `independent-implementation-verification` as a fresh subagent, with the same model, handoff, candidates, and budget. Only the guidance differed.

| Arm | Guidance | Adapted steps available |
| --- | --- | --- |
| Baseline | `.agents/` at `c827914`, before any adapted skill (the audit commit changed only documentation) | None |
| Pilot | `.agents/` at `88d7523`, the base of the candidates | Adversarial pass, operator journey; blast-radius record as an optional input (none was supplied) |

Each verifier received [the same prompt template](issue-376-evidence/verifier-prompt-template.txt). The pilot prompt adds the one handoff line its guidance requires ("Adversarial triggers seen by the implementer: none"). Guidance was read from extracted copies of each revision, and the baseline arm was told not to open the pilot-only files in the candidate worktree.

### Candidates

All three are uncommitted changes on base `88d7523`, presented with the same neutral task ("refactor `PurchaseService` for readability, no change to observable behavior") and the same criteria: AC1, no observable change and ADR 0011 and the security invariants still hold; AC2, the existing purchase tests pass. The issue number in the handoff (#9376) does not exist, so no verifier could read this Issue.

| Candidate | Patch | Tree | Surface | Seeded defect (sealed before any run) |
| --- | --- | --- | --- | --- |
| K1 | [`K1.patch`](issue-376-evidence/K1.patch) | `c8856365` | Security and API: idempotency, customer consistency | The submission fingerprint drops the customer id. Keys are unique per tenant, so a replay on customer B returns customer A's purchase instead of `409` ([ADR 0011](../adr/0011-purchase-history-and-last-purchase.md), invariant 18). Secondary: stored fingerprints no longer match a pre-deploy retry. |
| K2 | [`K2.patch`](issue-376-evidence/K2.patch) | `897e19fd` | API: pagination | `PurchasePage.of` builds the exclusive cursor from the look-ahead row, so every page boundary skips one purchase (ADR 0011, Pagination). |
| K3 | [`K3.patch`](issue-376-evidence/K3.patch) | `d470bc0d` | Authorization helpers | None. A behavior-preserving merge of the two customer-loading helpers; any blocker is a false positive. |

The [ground truth](issue-376-evidence/ground-truth.md) was written before the first verifier started and is committed unchanged; the `seed.py` it mentions was a local script that applied exactly the edits now stored as the three patches. K1 and K2 were built so that the shipped suite misses them, which every run then confirmed. These are new seeds: neither repeats the #374 or #375 regressions, whose descriptions are in the repository.

### Budget and environment

- At most 45 tool calls and about 25 minutes per verifier; Gradle only with `--tests` filters (Testcontainers allowed); no full `test`, `check`, or `build`, because the host had about 1 GB of free memory.
- Linux, JDK 26.0.2 Temurin through the Gradle Wrapper, Docker for Testcontainers. The running Compose stack was off-limits, so no verifier ran the operator journey; none of the candidates changes a journey.
- Runs were sequential, one verifier at a time, all on 2026-11-13.

## Results

Defect counts are scored against the sealed ground truth. A blocker that matches no seeded defect and is not a real defect is a false positive.

| Run | Verdict | Seeded defect found | Secondary found | False-positive blockers | Optional findings | Tool calls | Tokens | Duration |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| K1 baseline | `fail` | Yes, F1, probe plus base control | Yes, F2, from code | 0 | 1 (stray blank line) | 17 | 96,579 | 304 s |
| K1 pilot | `fail` | Yes, F1, probe plus base control | Yes, F2, from code | 0 | 0 | 14 | 100,923 | 186 s |
| K2 baseline | `fail` | Yes, F1, Testcontainers probe plus base control | n/a | 0 | 0 | 13 | 97,362 | 194 s |
| K2 pilot | `fail` | Yes, F1, mocked-repository probe plus base control | n/a | 0 | 1 (no multi-page test) | 14 | 98,731 | 186 s |
| K3 baseline | `pass` | n/a | n/a | 0 | 1 (readability of a boolean flag) | 10 | 86,959 | 132 s |
| K3 pilot | `pass` | n/a | n/a | 0 | 0 | 17 | 102,661 | 223 s |

| Arm | Seeded defects found | False-positive blockers | Tool calls | Tokens | Duration |
| --- | --- | --- | --- | --- | --- |
| Baseline | 2 of 2 | 0 | 40 | 280,900 | 629 s |
| Pilot | 2 of 2 | 0 | 45 | 302,315 | 595 s |

Tool calls, tokens, and durations are the harness-reported totals for each subagent, not self-reports. Every verdict validated with `verification-verdict.mjs` against its candidate: exit `1` for the four `fail` verdicts and exit `0` for the two `pass` verdicts. The verdicts are in [`verdicts/`](issue-376-evidence/verdicts/) and the probe sources in [`probes/`](issue-376-evidence/probes/), stored as `.java.txt` so that no build or scanner treats them as code.

With one run per candidate and arm, the arm totals cannot separate a cost difference from run-to-run variance: the same candidate took 304 s in one arm and 186 s in the other (K1). Read the 7.6% token difference as one sample, not a measured cost.

Each seeded defect was reproduced independently by two verifiers, each with a base control (the same probe passing on `88d7523`). Every run also passed the existing purchase suites on the candidate (`PurchaseControllerTest` 15, `PurchaseTest` 2, `PurchaseManagementIntegrationTest` 5, `PurchaseCurlSystemTest` 1), which confirms that the shipped tests miss K1 and K2.

### Observations

- **Detection:** no difference. The baseline's ordinary defect hunt already names "retries and idempotency" and "backward compatibility of APIs", and both baseline verifiers wrote a failing probe with a base control without being told to. The adversarial pass added no detection on these candidates.
- **Trigger coverage gap:** K1 breaks an idempotency and customer-consistency contract outside messaging, and K2 an API pagination contract. Neither matches a row of the [trigger table](../../.agents/skills/independent-implementation-verification/adversarial-review.md#when-to-run), so the pass was correctly not required. The defects were caught by the ordinary hunt, not by the pass.
- **Compliance gap:** the pass requires a `limitations` line ("adversarial pass not run: no trigger") when it is skipped. Neither K1 pilot nor K2 pilot recorded one, so their verdicts do not say whether the pass ran. The validator does not check this line.
- **Assurance on the benign control:** K3 pilot found the authorization trigger, ran the pass with five hypotheses, a 29-case equivalence probe, a base control, and a negative control (swapping the lock flags failed 14 of 29 cases). It cost 7 more tool calls and about 15,700 more tokens than K3 baseline, and produced no finding. The baseline cleared K3 by code reading plus the existing `AuthorizationBeforeValidationIntegrationTest`.
- **Blocking bar in practice:** both K1 verifiers raised the secondary defect (F2) as a blocker from code alone. It is real, and it was the same in both arms, so the pilot's stricter bar for adversarial findings did not change classification here; F2 came from the ordinary hunt.
- **False positives:** none in either arm, on any candidate.

## Tradeoffs and maintenance cost

| Practice | Cost observed | Maintenance surface |
| --- | --- | --- |
| Adversarial pass | Measured only on K3, its one run: +15,702 tokens (+18%) and +7 tool calls over the baseline verifier. The K1 and K2 deltas (+4.5% and +1.4% tokens) are not its cost, since it did not run there; they reflect the longer guidance or run-to-run variance | `adversarial-review.md` (119 lines), two steps in the verification skill, `--previous` handling in the validator and loop helper with tests |
| Operator journey | About 4 s per run on an already running stack; about 42 s per backend rebuild; the stack itself dominates | 531-line script, 199-line offline test, one journey file, one CI workflow entry |
| Blast-radius analysis | Not measured here; #373 used 2 probes | One 115-line skill and one example |

The `--previous` check from #374 (a re-verification cannot drop an open blocker) protects the existing loop regardless of the adversarial pass, and it stays.

## Proposed revisions, not applied

These need evidence from real Issues before they change guidance:

1. Add "Idempotency and API contracts" (keys, fingerprints, cursors, status codes) to the adversarial trigger table, so that K1-like and K2-like diffs are attacked by design rather than by chance.
2. Make the "not run: no trigger" line checkable, for example a verdict check id or a validator warning, so that the record shows whether the pass ran.
3. Measure blast-radius analysis on an implementer run: give one high-risk Issue to two implementers, one with the skill, and compare the defects the verifier then finds.

## Rollout and rollback

Rollout is incremental, and each step can stop:

1. **Now (this PR):** both retained practices stay optional and risk-triggered; nothing becomes a default or a gate. The operator journey drops its "pilot" wording, blast-radius analysis keeps "pilot practice", and the adversarial pass never carried that label.
2. **Next five Issues that hit a trigger** (tracked in [Issue #398](https://github.com/stevdrey/dokene/issues/398)): record, in each PR's verification section, whether the adversarial pass ran, its trigger, its added tool calls, and any finding only it produced.
3. **Review:** with that data, decide revisions 1 to 3 above in Issue #398. Adopt the pass as a default only if it found at least one confirmed defect that the ordinary hunt missed, with no increase in false-positive blockers.

Rollback for each practice is independent:

- **Adversarial pass:** remove `adversarial-review.md` and the sentences that link to it from the verification skill (steps 4 and 7, the handoff field, and re-verification step 5). Keep the `--previous` blocker carry-forward in `verification-verdict.mjs` and `issue-loop.mjs`.
- **Operator journey:** remove the skill directory, the skill-index row, the step 5 sentence of the verification skill, the script and its test, and the `scripts.yml` entry. No application code depends on them.
- **Blast-radius analysis:** remove the skill directory and the pointers in `agent-task-workflow`, `backend-java-spring`, and `frontend-react-typescript`.

No rollback touches CI gates, the PR size guard, security invariants, or application code.

## Reproduce

From the repository root, with JDK 26 active and Docker available:

```bash
BENCH=<scratch directory outside the repository>
mkdir -p "$BENCH/guidance/baseline" "$BENCH/guidance/pilot"
git worktree add --detach "$BENCH/K1" 88d7523
git -C "$BENCH/K1" apply "$PWD/docs/verification/issue-376-evidence/K1.patch"
(cd "$BENCH/K1" && node .agents/scripts/verification-verdict.mjs --identity --base 88d7523)   # tree c8856365…
git archive c827914 .agents | tar -C "$BENCH/guidance/baseline" -xf -
git archive 88d7523 .agents | tar -C "$BENCH/guidance/pilot" -xf -
```

Repeat the `git worktree add`, `git apply`, and identity commands for K2 (tree `897e19fd…`) and K3 (tree `d470bc0d…`). Fill the [prompt template](issue-376-evidence/verifier-prompt-template.txt) for each candidate and arm: `{CAND}` is the candidate copy, `{GUIDE}` is `$BENCH/guidance/<arm>`, `{RUN}` is `$BENCH/runs/<candidate>-<arm>`, `{IDENTITY}` is the identity JSON, and `{BENCH}` is `$BENCH`. For the pilot arm, `{TRIGGERS_LINE}` is `Adversarial triggers seen by the implementer (check against the diff): none` and `{EXTRA_EXCLUDE}` is empty. For the baseline arm, `{TRIGGERS_LINE}` is empty and `{EXTRA_EXCLUDE}` is:

```text
 Do not read .agents/skills/independent-implementation-verification/adversarial-review.md, .agents/skills/blast-radius-analysis/, or .agents/skills/operator-journey-verification/ in the candidate.
```

Start each verifier as a fresh subagent with no other context, then score its verdict against the ground truth.

To rerun one stored probe instead of a whole verifier, copy the matching `probes/*.java.txt` into `backend/src/test/java/` of a candidate copy under its package path, drop the `.txt`, and run it with `./gradlew test --tests '<ClassName>'`. Expected: it fails on K1 or K2 and passes on `88d7523`; the K3 probe passes on both.

## Not measured

- **Review minutes:** no human review of the six verdicts was timed. Agent wall-clock time is reported instead and is not a substitute.
- **Token breakdown:** the harness reports one total per subagent; input, output, and cached tokens were not observable.
- **Variance:** one run per candidate and arm. With identical outcomes in both arms, more runs would mainly measure cost variance.
- **Operator journey and blast-radius analysis:** not exercised in this benchmark, for the reasons above. Their evidence is the [#375 proof run](issue-375-operator-journey-pilot.md) and the [#373 exercise](issue-373-blast-radius-exercise.md).
- **Full backend suite:** not run on the candidates because of host memory; the purchase suites and the verifiers' probes were.
- **Blinding:** verifiers could read the repository, including the pilot guidance under `.agents/`; the baseline arm was instructed not to open the pilot-only files. Whether every verifier complied was not audited.
