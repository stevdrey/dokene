# Suite #121 scripts

Run from the root of a QA worktree with the stack up (`./scripts/dev-env.sh up --seed`), synthetic data only.
`env121.sh` is sourced by all scripts (logs in as the three seeded users, resolves the tenants). Helper paths inside the scripts point to `/home/stevdrey/qa121/` (the original working folder); adjust `STUB` and `OUTD` if you move them.

- `sec121-a.sh` fixtures + SEC-01 + SEC-02; `sec121-b.sh` SEC-03; `sec121-c.sh` SEC-04 (runtime role + signed context); `sec121-d.sh` SEC-05/SEC-06 LIVE OpenAI.
- `Stub.java` is the **fault-injection** OpenAI stub (compile with `javac --release 26`, run inside the backend network namespace with `docker run --network container:<oidc-bridge> ... java -cp /stub Stub`, then set `DOKENE_AI_OPENAI_BASE_URL=http://127.0.0.1:18080/v1` and restart the backend). Scripts `e`, `f`, `g`, `h`, `i`, `j`, `k` need it; their results are fault-injection results, not live-model results.
- `sec121-e.sh` malicious provider payloads vs the gate; `sec121-f.sh` / `sec121-g.sh` grounding and bypass probes; `sec121-h.sh` SEC-10 and a first SEC-09 pass (its 9c block had a fixture error and is superseded by `sec121-i.sh`); `sec121-i.sh` SEC-09 in-flight and retry; `sec121-j.sh` SEC-08; `sec121-k.sh` SEC-07 fixture.
