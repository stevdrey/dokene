# Issue #311 anonymous session creation: verification record

Status: single PR. It closes audit finding M2 of the [security audit of 2026-11-02](../security/security-audit-2026-11-02.md): any anonymous request could make the backend create a server session that lived for 30 minutes, so unauthenticated traffic could grow the heap without limit. The design is recorded in [ADR 0007](../adr/0007-oidc-server-side-session.md), section "Anonymous Requests and Login Start Sessions".

This record is sanitized: it gives counts, limits, outcomes and test names only. It contains no cookie, token, session id or reproduction script.

## Environment

- **Base:** branch `fix/311-prevent-anonymous-sessions`, based on `main` at `6c54473`.
- **Tested revision:** `6c54473` plus the working tree of this branch before its first commit. The evidence run below must be repeated on the committed revision and this line replaced with that commit before the record counts as evidence.
- **Toolchain:** Gradle Wrapper with the Java 26 toolchain declared by the build; Docker 29.7.2.
- **Backend tests:** embedded Tomcat on a random port, Testcontainers PostgreSQL 17, and an in-process OpenID Connect provider stub (`OidcProviderStub`). No real identity provider was called.
- **Dev stack run:** `./scripts/dev-env.sh up` (nginx, backend, Keycloak, PostgreSQL), entered through the nginx origin `http://localhost:5173`.
- **Data:** synthetic requests only. No user signed in during the evidence run.

## Recheck before the change

The sweep test `AnonymousSessionCreationIntegrationTest` was written first and run against the unchanged code. It counts every `HttpSession` the embedded server creates. Of its 38 anonymous requests, 28 created a session. M2 still applied, from three sources:

| Source | Requests that created a session | Audit status |
| --- | --- | --- |
| Request cache (stores the rejected request) | `GET` to a protected or unknown route without `Accept: application/json` | reported |
| CSRF token store (saves a token in a new session) | every `POST`, `PUT`, `PATCH` and `DELETE`, with or without a token, including `POST /logout` and `POST` to the login routes | not in the audit, which tested `GET` only |
| Container health check | the raw `GET /api/session` request the image sends every 5 seconds, which has no `Accept` header | not in the audit; one session per probe with no attacker involved |

Requests that already created no session: `GET` with `Accept: application/json`, `HEAD`, the CORS preflight, the back-channel logout endpoint, a callback with no login start, and the permitted routes that end in `404` or `500`.

## Change

| Part | Behavior |
| --- | --- |
| Request cache | `NullRequestCache`: no rejected request is stored. The post-login target was already fixed, so nothing read it. |
| CSRF token store | `SessionBoundCsrfTokenRepository`: the session store, saving only into a session that already exists. An anonymous unsafe request is compared with a fresh random token and refused with the same status as before. |
| Login start session | `PreLoginAuthorizationRequestRepository`: the session a login start creates idles out after `dokene.security.session.pre-login-timeout` (default `5m`) and ends once it is older than that, however often its cookie is used (`BffSessionLifetimeFilter`). |
| Login success | The session id rotates as before and the idle timeout returns to the container's session timeout. |
| Login failure | The login start session is invalidated and its cookie expired before the failure redirect. A signed-in session is never ended by a failed callback. |
| Setting | `pre-login-timeout` must be at least `1s` and at most `max-lifetime`, or startup fails with a message naming the property. |
| Edge | nginx limits `/oauth2/authorization/...` to 30 requests per minute per client address with a burst of 10. A refused request gets `429`, `Retry-After: 2` and a short plain text body, and is not passed to the backend. No other route is limited. |

Every status code in the sweep is the one recorded against the unchanged code.

## Automated verification

| Check | Result |
| --- | --- |
| `AnonymousSessionCreationIntegrationTest` (38 requests: the sweep plus the health check bytes over a raw socket) | 38 pass: zero sessions, no `JSESSIONID` cookie, statuses unchanged |
| `SessionBoundCsrfTokenRepositoryTest` | 6 pass |
| `PreLoginAuthorizationRequestRepositoryTest` | 9 pass |
| `BffSessionPropertiesTest` (default, bounds, startup failure for `0s`, `500ms` and a value above `max-lifetime`) | 8 pass |
| `BffSessionLifetimeFilterTest` (3 new cases for login start sessions) | pass |
| `PreLoginSessionIntegrationTest` (one session with a 300 second idle timeout at login start; id rotation and normal timeout at login; failed callback cleanup; signed-in session kept; repeated login start) | 5 pass |
| `PreLoginSessionExpiryIntegrationTest` (`pre-login-timeout=1s`: late callback fails; a session whose cookie is used every 300 ms still ends) | 2 pass |
| `OidcBrowserSessionIntegrationTest` and `BffTenantBoundarySecurityIntegrationTest`, unedited (login, CSRF, session fixation, local, provider and back-channel logout) | pass |
| `./scripts/tests/nginx-login-rate-limit-smoke.sh` (frontend image, no backend) | 6 checks pass: of 40 login starts sent at the same moment 29 get `429` and 11 are passed on; every refusal has `Retry-After: 2`, `text/plain`, the base security headers, the text body and no cookie; 40 simultaneous requests to `/api/session` and to the callback get no `429` |

The smoke test runs in the `nginx-headers` job of `.github/workflows/frontend.yml`.

## Evidence run on the dev stack

`./scripts/verify-issue-311-anonymous-sessions.sh`, all checks passed.

| Step | Sent | Session cookies issued | `429` answers |
| --- | --- | --- | --- |
| Anonymous sweep, 34 routes through nginx, 20 at a time | 2000 | 0 | 107, all on the two login start paths in the sweep, refused by nginx |
| Login burst, sent at the same moment | 40 | 11 | 29, none of which set a cookie |

Before the change the same sweep would have issued a session cookie for most of its requests (28 of the 38 request shapes created a session in the recheck).

Backend memory, read during the same run:

| Moment | Used heap after a full collection (`jcmd 1 GC.run`, then `GC.heap_info`) | Container memory (`docker stats`) |
| --- | --- | --- |
| Start | 48 088 KiB | 619.3 MiB |
| After the sweep | 44 232 KiB | 659.3 MiB |
| After the login burst | 44 517 KiB | 660.9 MiB |

- The sweep added no live heap: used heap after collection was lower after 2000 requests than before them.
- The 11 login start sessions added 285 KiB of live heap, about 26 KiB per session. This is one small sample and includes unrelated allocation.
- Container memory rose by about 40 MiB during the sweep while live heap did not. That is heap the JVM had reserved and not yet returned, which is why the used heap reading is the measure here.

The runtime image is a JRE without `jcmd`, so the script runs `jcmd` from a JDK image that joins the backend container's process namespace.

## Resulting bound

- An anonymous request other than a login start creates no session, whatever its rate.
- One client address can start at most 30 logins per minute sustained, plus a burst of 10. Each login start session ends at most 5 minutes after it started, plus up to about a minute until the container's background sweep removes a session nobody touches.
- That gives about 30 x 6 + 10 = 190 live login start sessions per client address, about 5 MiB of heap at the measured 26 KiB per session.

Residual risk:
- The bound is per client address. There is no ceiling on the total number of sessions, so many addresses at once each get their own 190. A hard ceiling was rejected because an anonymous flood could then lock real users out of login.
- The limit is applied by nginx. Requests sent straight to the backend port skip it; that port is published on loopback only (issue #318).
- Locally, and behind any proxy that hides client addresses, every client shares one address and so one limit of 30 per minute, until the edge restores the real address.
- A user who needs more than 5 minutes at the provider's login page has to start the login again. The timeout is configurable.
- A cross-site link to the callback URL can cancel a login that is in progress. It cannot end a signed-in session.
