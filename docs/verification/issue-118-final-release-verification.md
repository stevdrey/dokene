# Release certification record for campaign #118

- Date: 2026-10-24.
- **Tested release candidate: `main` @ `acb7856`** (merge of PR #282). `main` did not move while this record was produced.
- Scope: Dokene Phase 1 and Phase 2 (customers, purchases, consent/DNC, follow-up queue and policies, tenant isolation, RBAC, BFF, AI recommendation and editable draft with a real OpenAI provider). **Phase 3 (WhatsApp messaging, webhooks, template approval) is out of scope** and is not certified here, even though its code is on `main`.
- Environment: local Docker stack (PostgreSQL 17, Keycloak, backend, frontend), Chrome 155 and Firefox 157 headless, synthetic data only. Real OpenAI Responses API, model `gpt-6-luna`, through the production adapter, wherever a case says "live". Everything else about the provider was injected by a local fault-injection stub and is labelled as such.
- Evidence: `issue-118-evidence/outputs.txt` and `issue-118-evidence/scripts/`. The per-suite reports (`issue-119-…` to `issue-124-…`) keep the original, detailed case tables.

## Why a re-execution was needed

The suites were originally run on `9e1d039` and the last fixes were retested on `6689d27`. Between `6689d27` and `acb7856` `main` received Phase 3 work that also touched certified areas, which invalidates their evidence until retested (campaign contract):

| Changed area | Commits | Re-verified by |
| --- | --- | --- |
| `ai/domain/DraftSafetyValidator` (+485 lines, #268 amount rules) | #268 | SEC-06 grounding matrix and gate matrix, live draft regression, deterministic evaluation suite |
| `RequiredPermission` / `RequiredPermissionInterceptor` | #268 | v162 (authorize before binding, 16 endpoints), SEC-02 role matrix |
| Flyway V29 to V32 | #139, #268 | upgrade of an existing database V28 to V32, and V1 to V32 on a clean database |
| `frontend/shared/api/httpClient.ts` (`Retry-After` on 429) | #268 | UI matrix, SEC-10 429 behaviour, keyboard journey |

## Results on `acb7856`

| Suite | What was executed | Result |
| --- | --- | --- |
| #119 regression | `reg-api` 80/80 (clean database), `v159` 28/28, `v162` 45/45, `recurrence` 16/16 | **PASS** |
| Migrations | Existing QA database (217 customers) upgraded V28 to V32, backend healthy; clean database V1 to V32 plus seed | **PASS** |
| #120 live AI journeys | Live recommendation and draft through the UI with keyboard only (`ux03`: focus moves to the result, `aria-live` "Recomendación lista", draft editable); `sec121-d` live matrix 108/108 (8 customers, 8/8 drafts delivered); live paths of `ux06`, `ux08`, `ux09`, `ux10_11` | **PASS** |
| #121 security | `a` 52, `b` 62, `c` 43, `d` 108 (live), `e` 25, `f` 31, `g` 21 (+6 expected lines, see below), `h` 25, `i` 13, `j` 28, `l` 3, `m` 10: 421 checks passed, 0 unexpected failures | **PASS** (residual risk below) |
| #122 UI/UX | Responsive matrix 320 to 1440 px (no overflow, 0 contrast failures, 0 unnamed controls), 200 % and 400 % zoom (no overflow), accessibility tree, VIEWER view, content stress live and with injected AI text (16/16), stale edited draft 13/13, pagination 18/18, ineligibility and races, breakpoint crossing, Firefox journey | **PASS**, except UX-04 with a real screen reader (not executed) |
| #123 resilience | RES-09 database outage retested on `6689d27` (see PR #261): 503 with `Retry-After` in about 3 s, automatic recovery. The rest of the matrix was **not** re-executed on `acb7856` | **PASS on `6689d27`**; unchanged code paths |
| #124 live evaluation | Baseline from `9e1d039` (3 live runs, graded and confirmed by the maintainer). On `acb7856` only the deterministic suite was re-run: 89 tests, 0 failures. The live baseline was **not** repeated | **PASS** (baseline from `9e1d039`) |

### Lines that are not plain passes

- `sec121-g`: 6 lines are expected. One is the control (no claim, ACCEPT is correct). Five are documented residual risk, not defects: English wording, `10 lucas`, number words without a currency marker, and "mitad" without "precio" are still accepted by the validator (ADR 0018). The outcome is identical to the retest of #184, so the larger validator change did not regress any previously rejected variant.
- `ux07`: 1 FAIL line is a stale expectation in the script (the 401 message observed is correct); the same line is in the original run.
- `ff-journey` (Firefox): 3 FAIL lines are stale expectations. Two assert the old Tab order (a "Ver valores originales" control was added on purpose in #194) and one asserts that the long-name overflow exists (it was fixed in #191, the measurement now shows `overflow=false`).

### Harness problems found and fixed during this run (none is a product defect)

- `sec121-h`: the compose override `DOKENE_SESSION_TIMEOUT=1m` expired the session during the 65 s wait, so later calls used an empty customer id. The override is needed only by `sec121-m`; `h` now re-logs in after each wait and runs without the override.
- A first `h` rerun showed a 409 on a membership already revoked by an interrupted earlier execution; fixtures were recreated and `h` passed 25/25.
- `ux06b`, `ux07`, `ux09`, `ux10_11` need fixtures that a clean database does not have (customer "Dormant Té QA", 81 due customers, workspace "QA Café Sur"). They are now created by `fx-ui-extra.sh`; the first attempts without them are not counted.

## Live OpenAI usage in this re-execution

About 60 live calls (live regression `sec121-d`, `reg-api`, and the live UI journeys); token counts are not exposed by the app and the estimated cost is well below US$0.10. Campaign total, adding the earlier reports: roughly 350 calls, far below the US$5 monthly budget; the provider dashboard has the exact figure.

## Defect disposition

| Issues | Severity | State |
| --- | --- | --- |
| #183, #185 | Medium | Fixed, retested, closed |
| #184, #186, #246, #253 | Low | Fixed, retested, closed |
| #191 to #197 | Medium to Low | Fixed, retested, closed |
| #125 to #127 and earlier | various | Fixed, retested, closed |

No defect found by this campaign is open.

## Known limitations

1. **UX-04 (real screen reader, NVDA or Orca) was not executed.** It needs a headed browser on the operator's machine. The accessibility-tree audit passed, but it is not a screen-reader session. Accepted by the maintainer when #122 was closed; it makes the verdict qualified.
2. Validator residual risk listed above (ADR 0018); a human reviews every draft before any manual send.
3. Genuine OpenAI 429, 5xx and refusals cannot be forced; they were injected.
4. Resilience (#123) and the live evaluation (#124) were not fully repeated on `acb7856` (see the table). Their code paths did not change in the diff; if the maintainer wants a fully fresh run, it is a separate pass.
5. Phase 3 is not certified.
6. Browser matrix: Chrome 155 and Firefox 157 headless on Linux; no Safari, no real mobile devices.

## Verdict

**PASS with known limitations** for the executed scope of `main` @ `acb7856`: no unresolved defect was found, no required scenario is represented as passing when it was not executed, and the one required scenario that was not executed (UX-04) is disclosed and accepted. Any later change to `ai/`, `tenant/`, the migrations or the shared HTTP client invalidates the affected evidence until retested.
