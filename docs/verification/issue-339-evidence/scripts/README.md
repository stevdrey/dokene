# Scripts for the issue 339 live Meta journey

These scripts drove suite [#339](../../issue-339-live-meta-journey-verification.md) on `main` `c53cea24`. They are
adapted from the issue 140 live helpers in [phase-3-evidence/scripts](../../phase-3-evidence/scripts/README.md). Follow
[phase-3-live-meta-procedure.md](../../phase-3-live-meta-procedure.md) before running anything live, and get a new,
explicit authorization for every run.

| Script | Purpose |
|---|---|
| `control.sh`, `compose.qa339.yaml`, `nginx.conf` | The live-override wrapper for the compose project `dokene-qa-run`: builds the `migrate`, backend and frontend images from the worktree at the candidate, starts the stack, flips the global switch (`outbound true\|false`, which recreates the backend) and handles the tunnel and gateway. The gateway proxies only `/webhooks/whatsapp` and logs method, path, status and size, with no headers or bodies. The operator starts the tunnel. |
| `status339.py` | LIVE-01: local-only readiness (integration, template mapping, recipient consent, last activity). Calls no Meta endpoint. |
| `check339.py` | LIVE-02: ownership verification and template approval refresh through Dokene, which call real Graph. Prints only redacted readiness. |
| `live339.sh send` | LIVE-03/04: registers the close-down handler on `EXIT`, `INT`, `TERM` and `HUP`, opens the window with the default `24h` interval, runs one `send339.py send`, then closes the window: at most 3 attempts, each running every shutdown step and reading back the tenant switch and the running backend's `outbound=false interval=24h`. Only that read-back counts as closed; otherwise it logs `CLEANUP FAILED` and exits 70. |
| `send339.py` | One guarded send through Dokene's API (submit, approve with the template revision, send) with fixed idempotency keys and a state file that refuses a second send, then a read-only `status`. `tenant` reads the tenant switch; `enable`/`disable` set it. Run the send through `live339.sh`, not directly. |

The helpers import the operator's private local files (`live_cases_oct24.py`, the recipient file, and a copy of
`local_api.py` that reads the owner password of issue 314 from the stack's `.env`). Those files are not in this
repository, and neither are any secret values.
