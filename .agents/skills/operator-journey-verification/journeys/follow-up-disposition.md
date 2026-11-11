# Journey: follow-up disposition

An OPERATOR finds a due follow-up in the queue and dismisses it. Afterwards the due queue and the eligibility API agree that the customer is no longer due, for every role that can read them. No message is sent.

- **Script:** [`scripts/verify-journey-follow-up-disposition.sh`](../../../../scripts/verify-journey-follow-up-disposition.sh)
- **Offline helper tests:** [`scripts/tests/verify-journey-follow-up-disposition-test.sh`](../../../../scripts/tests/verify-journey-follow-up-disposition-test.sh) (run in CI by the `Script tests` workflow)
- **Endpoints:** `GET /api/follow-up-queue`, `GET /api/customers/{id}/follow-up-eligibility`, `POST /api/customers/{id}/follow-up-dismissals`, `GET /api/messages?customerId=`
- **Proof run:** [issue-375-operator-journey-pilot.md](../../../../docs/verification/issue-375-operator-journey-pilot.md)

## Run it

```bash
./scripts/dev-env.sh up --seed
./scripts/verify-journey-follow-up-disposition.sh doctor
./scripts/verify-journey-follow-up-disposition.sh --out "$(mktemp -d)"
```

When the candidate changes backend code, rebuild the image first with `./scripts/dev-env.sh restart-backend --yes`. `--keep-fixture` leaves the fixture customer active for a manual look.

## Fixture

| Item | Value | Rerun safety |
| --- | --- | --- |
| Tenant A | `QA Café Norte`, from `./scripts/dev-env.sh seed` | Read only, except the new fixture customer |
| Tenant B | `QA Journey Isolation`, provisioned by `testuser` with idempotency key `qa-journey-isolation` | Idempotent: the same tenant every run; nobody else is invited |
| Customer | `QA Journey <run id>`, random `+569` number, WhatsApp consent `GRANTED` (`CUSTOMER_VERBAL`), one purchase 40 days ago, explicit next date = today in the tenant's time zone | New per run; archived by the owner at the end |
| Idempotency keys | `qa-journey-*-<run id>` | New per run |

All data is invented. The password comes from `DOKENE_TEST_USER_PASSWORD` and is never printed.

## Actors

| Actor | Role | Tenant A | Tenant B |
| --- | --- | --- | --- |
| `testoperator` | OPERATOR | member | not a member |
| `testviewer` | VIEWER | member | not a member |
| `testuser` | OWNER | member | owner |

## Steps

| Id | Action | Expected | Kind |
| --- | --- | --- | --- |
| S1 | Operator reads the customer's eligibility | `DUE` or `OVERDUE` | automated |
| S2 | Operator walks every page of the due queue | Customer present | automated |
| S3 | Viewer walks the due queue | Customer present | automated |
| S4 | Viewer dismisses with a current `If-Match` | `403` | automated |
| S5 | Eligibility after S4 | Still due | automated |
| S6 | Operator dismisses without `X-CSRF-TOKEN` | `403` | automated |
| S7 | Eligibility after S6 | Still due | automated |
| S8 | Operator reads the queue with tenant B selected | `403` | automated |
| S9 | Owner reads tenant A's customer with tenant B selected | `404` | automated |
| S10 | Owner dismisses tenant A's customer with tenant B selected | `404` | automated |
| S11 | Owner walks tenant B's queue | Customer absent | automated |
| S12 | Operator dismisses without `If-Match` | `400` | automated |
| S13 | Operator dismisses with a stale `If-Match` | `409` | automated |
| S14 | Eligibility after S12 and S13 | Still due | automated |
| S15 | Operator dismisses with the current `If-Match` and a new idempotency key | `201`, same customer | automated |
| S16 | Operator replays S15 unchanged | `200`, same dismissal id | automated |
| S17 | Eligibility after the dismissal | Not `DUE` or `OVERDUE` | automated |
| S18 | Operator walks the due queue | Customer absent | automated |
| S19 | Viewer walks the due queue | Customer absent | automated |
| S20 | Operator lists messages for the customer | `0` | automated |
| S21 | Follow-up queue screen after a reload | Customer absent | manual |

S21 is outside the script. To perform it, run with `--keep-fixture` and pause before S15, or run it on a second fixture: sign in at `http://localhost:5173` as `testoperator`, open the follow-up queue, confirm the customer is listed, dismiss it from the UI, reload, and confirm it is gone; then sign in as `testviewer` and confirm the queue shows no dismiss control and no customer. Record what was observed, who or what drove the browser, and the browser used. The tracked #122 browser drivers are not reused: they are written for that campaign and pin its working folder.

## Cleanup

The owner archives the fixture customer (`DELETE /api/customers/{id}`, `204`; OPERATOR lacks `CUSTOMER_DELETE`). Working files, including cookies and response bodies, live in a temporary directory that is deleted on exit. The journey record stays in `--out`. Tenant B is kept so later runs reuse it. If cleanup fails, the record says so and names the fixture to archive.

## What this journey does not cover

- AI recommendations or drafts, message approval, and sending: they are other journeys, and sending is out of scope for agents.
- Snooze and manual follow-up completion; `FollowUpOperatorJourneySystemTest` covers them below the real security chain.
- The frontend's rendering beyond S21.
