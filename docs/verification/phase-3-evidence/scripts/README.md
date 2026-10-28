# Scripts for the issue 140 certification run

These scripts drove the Phase 3 certification run on `main` `f4cb0ec`. They run against the local QA stack and use synthetic data, except for the two authorized live sends (Spanish and English templates).

| Script | Purpose |
|---|---|
| `regress.sh` | Phase 1/2 regression: the suite #119 API scripts, suite #121 a–m and suite #122 UI. #121 a–d run on the real provider and e–l on the fault-injection stub. It reuses the harnesses checked in for #119, #121 and #122. |
| `rerun-ui.sh` | Reruns `ux06` after refreshing the emoji fixture, then `ux07` on the stub. |
| `phase3-ui.mjs` | The Phase 3 browser journey on the live-override stack with outbound off. It uses the #122 Chrome harness (`cdp.mjs`). |
| `live.sh es\|en` | One guarded live send in Spanish (`send140.py`) or English (`send140en.py`). It opens the window with a `1m` interval. An exit/signal handler registered before the window opens restores tenant off, global off, `24h` and the Spanish `GENERAL_FOLLOW_UP` mapping, even if the run is interrupted. |
| `check140.py` | Read-only on Meta: the integration state and a template approval refresh through Graph. |
| `send140.py`, `send140en.py` | One guarded, explicitly authorized template send through Dokene's API, with fixed idempotency keys, followed by a read-only status. The recipient is made due today. Run them through `live.sh`, not directly. |
| `restore-templates.py` | Disables the temporary `REPEAT_PURCHASE` mapping the browser journey needed. |
| `control.sh`, `compose.qa140.yaml`, `nginx.conf` | The live-override wrapper. It starts the stack built from the commit, flips the global switch and handles the tunnel. `nginx.conf` is the webhook gateway: it proxies only `/webhooks/whatsapp` and keeps an access log of method, path, status and size, with no headers or bodies. |

The live helpers import the operator's private local files (`local_api.py`, `live_cases_oct24.py`) and read credentials from them. Those files are not in this repository, and neither are any secret values. Follow [phase-3-live-meta-procedure.md](../../phase-3-live-meta-procedure.md) before running anything live.
