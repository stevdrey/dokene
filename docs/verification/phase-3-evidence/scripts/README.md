# Scripts for the issue 140 certification run

These scripts drove the Phase 3 certification run on `main` `f4cb0ec`. They run against the local QA stack and use synthetic data, except for the two authorized live sends (Spanish and English templates).

| Script | Purpose |
|---|---|
| `regress.sh`, `regress2.sh` | Phase 1/2 regression: the suite #119 API scripts, suite #121 a–m and suite #122 UI. `regress2.sh` resumes from #121 e with the fault-injection stub (see the report). They reuse the harnesses checked in for #119, #121 and #122. |
| `rerun-ui.sh` | Reruns `ux06` after refreshing the emoji fixture, then `ux07` on the stub. |
| `phase3-ui.mjs` | The Phase 3 browser journey on the live-override stack with outbound off. It uses the #122 Chrome harness (`cdp.mjs`). |
| `send140en.py`, `live-en.sh` | The same guarded send with the English template (`en-US`). The wrapper opens the window with a `1m` interval and restores tenant off, global off, `24h` and the Spanish mapping. |
| `check140.py` | Read-only on Meta: the integration state and a template approval refresh through Graph. |
| `send140.py` | One guarded, explicitly authorized template send through Dokene's API, with fixed idempotency keys, followed by a read-only status. |
| `restore-templates.py` | Disables the temporary `REPEAT_PURCHASE` mapping the browser journey needed. |
| `control.sh`, `compose.qa140.yaml` | The live-override wrapper. It starts the stack built from the commit, flips the global switch and handles the tunnel. |

The live helpers import the operator's private local files (`local_api.py`, `live_cases_oct24.py`) and read credentials from them. Those files are not in this repository, and neither are any secret values. Follow [phase-3-live-meta-procedure.md](../../phase-3-live-meta-procedure.md) before running anything live.
