Found during #118 / #119 (REG-01), tested SHA `2e0a2549c43855b783cd8d0e318bc8c8fe662b4d`. Origin: **pre-existing defect in QA tooling** (script from #66); not a product regression.

**Severity:** Low (intermittent false FAIL in the reproducible QA fixture; blocks `--verify` on a clean run ~1 in 9 executions).

**Environment:** Linux (Fedora), JDK 26.0.2 (Temurin), Docker Compose postgres:17 + Keycloak, backend `bootRun`, synthetic data only.

**Steps**
1. Clean stack, `./scripts/seed-local-qa.sh --verify`.
2. The check "[OPERATOR] Creating customer (CUSTOMER_WRITE)" posts `+569` + a random 8-digit suffix in `[10000000, 99999999]` with `region: CL`.

**Expected:** the OPERATOR check returns 201 on every run.

**Actual:** on my first clean run the check printed `FAIL (400)` and the script exited (`set -e`), skipping all later RBAC checks. Re-running 6 times gave 6 PASS, so it is intermittent.

**Root cause (reproduced via direct API):** Chilean mobile numbers starting `+5691…` are rejected by the phone normalizer with `400 {"message":"El formato del teléfono es inválido para la región seleccionada.","field":"phones[0].number"}`; other prefixes are accepted:

| number | HTTP |
|---|---|
| +56912345678 | 400 |
| +56911111111 | 400 |
| +56922345678 | 201 |
| +56940000001 | 201 |
| +56999999999 | 201 |

A random suffix starting with `1` produces `+5691…` (≈1/9 of draws).

**Suggested fix:** generate the suffix in `[20000000, 99999999]` (or a valid fixed pattern) in `scripts/seed-local-qa.sh` (`RANDOM_SUFFIX`, ~line 290).

**Evidence:** run output above (sanitized, no tokens/passwords). Related: #66, #118, #119.
