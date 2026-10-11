# QA scripts for suite #119

Synthetic data only. Run each script from the repository root with the stack up (`./scripts/dev-env.sh up --seed`) and a `.env` that defines the local secrets. They log in as the seeded `testuser` / `testoperator` / `testviewer` (password `DOKENE_TEST_USER_PASSWORD`, default `testpassword`).

| Script | Covers | Notes |
|---|---|---|
| `reg-api.sh` | REG-01, REG-03, REG-04, REG-05, REG-06 and the tenant-policy role check | run once on a clean database (fixed phone numbers) |
| `v159.sh` | #159 tenant-wide follow-up policy restricted to OWNER/ADMIN | re-runnable |
| `v162.sh` | #162 authorize before request binding/validation (16 mutating endpoints) | re-runnable |
| `qa.sh` | shared helpers (`qa_login`, `api`, `chk`) | sourced by the others |

Each script prints `PASS`/`FAIL` lines and a `SUMMARY`. Browser journeys (REG-02, REG-07 to REG-09) were run manually; see the verification record.
