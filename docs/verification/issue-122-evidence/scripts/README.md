# Suite #122 scripts

Dependency-free Node (>= 22) harnesses: `cdp.mjs` drives a fresh headless Chrome through the DevTools Protocol (real input events, viewport/DPR emulation, accessibility tree, screenshots) and `bidi.mjs` drives a fresh headless Firefox through WebDriver BiDi. Run against the local stack (`./scripts/dev-env.sh up --seed`) with `DOKENE_TEST_USER_PASSWORD` exported; the login helper uses the seeded users. Paths inside the scripts point to `/home/stevdrey/qa122/` (the original working folder).

- `checks.mjs` in-page measurements (horizontal overflow, clipped text, target sizes, accessible names, computed WCAG contrast).
- `ux01` responsive matrix; `ux01b` target sizes; `sweep2`, `t390*`, `t_long` overflow investigations; `ux02` 200 %/400 % zoom; `ux03` keyboard journey; `ux04` accessibility-tree audit; `ux05` VIEWER; `ux06` content stress (live) and `ux06b` AI text stress (stub); `ux07` loading/error states (stub + fetch wrapper); `ux08` stale edited draft; `ux09` pagination; `ux10_11` ineligibility notice and races; `ux11d` breakpoint crossing; `ff-journey`, `ff-long` Firefox.
- `fx-*.sh` create the synthetic fixtures (they source the suite #121 environment helper).
- The `ux07`, `ux06b` scripts need the fault-injection stub of suite #121 (`../issue-121-evidence/scripts/Stub.java`) and `DOKENE_AI_OPENAI_BASE_URL=http://127.0.0.1:18080/v1`; their results are fault-injection results, not live-model results. `ux08` and `ux10_11` inject a client-side fetch failure (labelled in their output).
- `ux07.mjs` was amended after the run: the 401 case asserted a regular expression that did not include "no pudo generar"; the observed message is correct and the one FAIL line in `ux07-errors-chrome.txt` is that expectation.
- This directory is excluded from CodeQL (`.github/codeql/codeql-config.yml`): these are local-only evidence harnesses, not product code. Their findings (`js/http-to-file-access`, `js/bad-code-sanitization`) were reviewed as false positives.
