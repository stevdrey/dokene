# ADR 0007: Backend for Frontend (BFF) OIDC Authentication with Server-Side Sessions

## Status

Accepted

Amended in place on 2026-11-06 ([#314](https://github.com/stevdrey/dokene/issues/314), audit L9): the local realm now
requires S256 PKCE on the server side as well, so a provider that receives a request without it refuses the login
(§OIDC Authorization Code Flow); the realm settings script that upgrades an existing realm also applies the PKCE
attribute, a password policy and per user test passwords (§Upgrading an existing realm).

## Context

Dokene needs provider-neutral internal identities before an operator can select and enter a tenant. Provider
tokens must not become domain credentials or browser-persistent state, and authentication must not imply tenant
membership. The application is composed of a Spring Boot backend and a React/TypeScript frontend. We evaluated
browser-managed token storage versus a Backend for Frontend (BFF) server-side session model to manage OIDC
authentication and authorization lifecycle securely.

## Decision

Dokene adopts the **Backend for Frontend (BFF)** architecture for browser authentication.

Under this model, Spring Boot acts as the confidential OAuth2/OpenID Connect (OIDC) client and primary security
boundary. The browser interacts solely through an opaque, server-side application session cookie (`JSESSIONID`)
and never receives or handles OAuth access tokens, refresh tokens, ID tokens, or the OIDC client secret.

### OIDC Authorization Code Flow & Identity Resolution

Login begins when the browser navigates to `/oauth2/authorization/{registrationId}`, and the registered provider
callback is `/login/oauth2/code/{registrationId}`. Spring Security handles the Authorization Code exchange as a
confidential client, performs provider discovery, and enforces PKCE (RFC 7636 with S256 `code_challenge` and `code_verifier` validation) alongside state, token signatures, issuer,
audience, nonce, and temporal validity claims before any internal identity adaptation occurs. The provider enforces
PKCE too: the local realm sets `pkce.code.challenge.method=S256` on `dokene-bff`, so Keycloak answers an
authorization request without a challenge, or with the `plain` method, with `invalid_request` instead of the login
form. Any other deployment's realm must set the same client attribute.

The adapter takes only the framework-validated, case-sensitive `(issuer, subject)` pair. PostgreSQL resolves it
through a single atomic upsert into `oidc_identity_mappings`, whose unique constraint prevents duplicate mappings
during concurrent logins. The resulting `IdentityId` is the sole identity exposed to tenant resolution. Provider
claims such as email, names, groups, and roles are neither account-linking keys nor authorization inputs. The mapping
table contains no credentials or tokens and is accessible to the runtime role only through the narrow resolver
function.

### Session Lifecycle and Cookie Attributes

After authentication, the authenticated security context is stored exclusively in the server-side `HttpSession`.
Provider tokens remain server-side and must never be serialized into frontend API responses, error payloads,
logs, or audit events.

- **Session Creation**: `SessionCreationPolicy.IF_REQUIRED`. A browser without a session gets one only from a login
  start, `GET /oauth2/authorization/{registrationId}`; see
  [Anonymous Requests and Login Start Sessions](#anonymous-requests-and-login-start-sessions).
- **Session Fixation**: Session identifiers rotate immediately upon successful authentication (`changeSessionId`),
  and pre-authentication sessions are rendered invalid.
- **Session Expiration**: Sessions expire after 30 minutes of inactivity by default (`server.servlet.session.timeout=30m`)
  and fail closed, returning `401 Unauthorized` for `/api/**` endpoints.
- **Absolute Lifetime and Provider Revalidation**: Every session also ends at an absolute lifetime and is revalidated
  against the provider; see [Session Revocation and Absolute Lifetime](#session-revocation-and-absolute-lifetime).
- **Invalid Session Handling**: Requests presenting invalid, expired, or tampered session cookies to `/api/**` fail
  closed with `401 Unauthorized` rather than redirecting to an HTML login page or creating redirect loops.
- **Cookie Security**: The session cookie is configured with `HttpOnly=true`, `SameSite=Lax`, and `Secure=true`
  (with local plain HTTP development permitted only via an explicit `DOKENE_SESSION_COOKIE_SECURE=false` override).
- **Reverse Proxy & Forwarded Headers (Opt-In)**: Forwarded header trust defaults to `none` (`server.forward-headers-strategy=${SERVER_FORWARD_HEADERS_STRATEGY:none}`)
  to prevent untrusted clients from spoofing host or protocol headers. In deployments behind a trusted reverse proxy
  or ingress controller with TLS offloading, operators explicitly configure `SERVER_FORWARD_HEADERS_STRATEGY=framework`.
  The edge proxy/load balancer MUST strip and overwrite inbound `Forwarded`, `X-Forwarded-Proto`, `X-Forwarded-Host`,
  and `X-Forwarded-Port` headers from untrusted clients before proxying traffic to Dokene.
  Amended by issue #320 (audit finding L8): the repository's own nginx edge (`infra/docker/frontend`) meets that rule
  as follows.
  - *Headers dropped*: every proxied location (`/api`, `/oauth2`, `/login`, `/logout`, `/webhooks`) includes one
    snippet, `proxy-upstream.conf`, and sets no upstream header of its own. It never sends `Forwarded`,
    `X-Forwarded-Host`, `X-Forwarded-Prefix`, `X-Forwarded-Ssl`, `X-Forwarded-Server`,
    `X-Forwarded-Scheme`, `X-Original-Forwarded-For`, `X-Real-IP`, `X-Client-IP` or `True-Client-IP`, whatever the
    client sent, repeated or in any letter case.
  - *Headers set*: `Host` is always `localhost:8080`, which pins `{baseUrl}`. `X-Forwarded-For` is the edge's peer
    address, or the client address restored from a trusted proxy (below). `X-Forwarded-Proto` is always the edge's own
    scheme; no client or proxy value is believed for it. `X-Forwarded-Port` is always `8080`, the port of the pinned
    `Host`: under `framework`, Spring clears the port when it sees `X-Forwarded-Proto` and restores it only from
    `X-Forwarded-Port`, so without it `{baseUrl}` would lose `:8080` and the registered redirect URI would not match.
  - *Trusted proxies*: the image ships `/etc/nginx/snippets/trusted-proxies.conf` with no entry, so no peer is
    trusted. An operator trusts an outer proxy (a tunnel or load balancer) by mounting their own file over it, holding
    only `set_real_ip_from <address or CIDR>;` lines. From a listed peer the edge takes the rightmost address in its
    `X-Forwarded-For` that is not itself listed (`real_ip_recursive on`); when every address is listed, nginx uses the
    leftmost one; an entry that is not an address leaves the peer address in place. The login start limit and the
    webhook connection limits key on that restored address. The header source is fixed in `nginx.conf`, so the
    operator file cannot change it, and an invalid file stops nginx from starting.
  - *Supported strategies behind this edge*: `none` (the default) and `framework`, for traffic that arrives through the
    edge. `native` is not supported until scope feature 15 (issue #318) settles which peers the backend trusts.
  - *Gaps left to feature 15*: the direct backend port (`127.0.0.1:8080`, used by `oidc-bridge` and the local scripts)
    reaches the backend without the edge, so under `framework` it still accepts client forwarding headers. A TLS
    tunnel's scheme is not believed, so behind one the backend sees `http` and, under `framework`, sends no HSTS.

### Anonymous Requests and Login Start Sessions

Added by issue #311 (audit finding M2). An anonymous request must not cost session memory, and the one anonymous
request that needs a session, the login start, is bounded.

- **No session for anonymous requests**: the chain stores no rejected request (`NullRequestCache`; the post-login
  target is fixed, so a stored request was never read) and saves a CSRF token only into a session that already exists
  (`SessionBoundCsrfTokenRepository`). An anonymous request to a protected, unknown or state-changing route, the
  container health check included, gets the same status as before, no session and no `JSESSIONID` cookie.
  `AnonymousSessionCreationIntegrationTest` sweeps these routes and fails the build if one creates a session again.
- **Login start session**: a login start creates one session for the OAuth state, nonce and PKCE verifier. Until the
  login succeeds it idles out after `dokene.security.session.pre-login-timeout` (`DOKENE_SESSION_PRE_LOGIN_TIMEOUT`,
  `5m`) and also ends once it is older than that, however often its cookie is used. The age counts from the first
  login start in that session: starting login again does not restart it. That age check
  (`PreLoginSessionExpiryFilter`) runs ahead of Spring Security's filter, so it sees every request whichever
  security chain serves it: neither a request the webhook chain answers nor an unsafe request the CSRF filter
  refuses can keep the session alive. A callback that arrives later
  ends at the login failure redirect. Startup fails unless the value is at least `1s`, at most `max-lifetime` and
  at most `server.servlet.session.timeout`, so a login start session never idles longer than a signed in one. A
  user who takes longer at the provider's login page has to start again, and a user who starts login a second time
  in the same browser has only what is left of the first start's time.
- **Login success**: the session id rotates as before, and the idle timeout returns to
  `server.servlet.session.timeout`.
- **Login failure**: a failed callback invalidates its login start session and expires the session cookie
  (`JSESSIONID`, or the name in `server.servlet.session.cookie.name`) before the failure redirect. It never ends a session that is already signed in: the cookie is `SameSite=Lax`, so a
  cross-site link to the callback URL could otherwise sign a user out. Such a link can still cancel a login that is
  in progress, as a forced login start already could.
- **Edge limit**: nginx allows 30 login starts per minute per client address with a burst of 10 and answers the
  rest with `429` and `Retry-After: 2` without calling the backend (`infra/docker/frontend/nginx.conf`). Together
  with the timeout this bounds one client address to about 190 live login start sessions (30 a minute for the 5
  minute lifetime plus up to a minute until the container's expiry sweep, plus the burst). There is no global
  ceiling on sessions, the limit does not cover direct access to the backend port, and behind a proxy that hides
  client addresses it is one shared limit until the edge restores the real address.

### Session Revocation and Absolute Lifetime

Added by issue #307 (audit finding M3). A Dokene session must not outlive the identity provider's decision about the
user, nor live forever while it stays active. The contract, with the default bounds:

| Event | Mechanism | Bound |
|---|---|---|
| Provider logout of the user or session (user, admin or SSO max lifespan) | OIDC Back-Channel Logout: the provider posts a logout token to `POST /logout/connect/back-channel/{registrationId}`; matching sessions are invalidated | immediate on delivery; otherwise caught by revalidation |
| User disabled at the provider, or provider session ended without a delivered logout token | Revalidation: every `revalidation-interval` the session's refresh token is redeemed at the token endpoint; `invalid_grant` invalidates the session | `revalidation-interval`, 5 min |
| Any session, however active | Absolute lifetime measured from the login that created it | `max-lifetime`, 8 h |
| Provider unreachable during revalidation (network failure, 5xx, malformed response) | The session survives and every request retries; it is invalidated once the last successful revalidation is older than the grace | `revalidation-grace`, 15 min |
| Tenant membership revoked in Dokene | Unchanged: membership is resolved on every tenant-scoped request | immediate |

- **Settings**: `dokene.security.session.max-lifetime` (`DOKENE_SESSION_MAX_LIFETIME`, `8h`),
  `revalidation-interval` (`DOKENE_SESSION_REVALIDATION_INTERVAL`, `5m`) and `revalidation-grace`
  (`DOKENE_SESSION_REVALIDATION_GRACE`, `15m`), and `revalidation-timeout` (`DOKENE_SESSION_REVALIDATION_TIMEOUT`,
  `5s`), the connect and response deadline of one revalidation call. A revalidation holds the session's lock, so a
  timeout counts as "provider unreachable" and never hangs the session's requests. Startup fails unless all are
  positive, the grace is longer than the interval, the timeout is shorter than the interval and the grace does not
  exceed the lifetime.
- **Fail closed**: a revoked or expired session is invalidated and the request continues unauthenticated, so
  `/api/**` answers `401 Unauthorized` exactly as for idle expiry. An OIDC session without a recorded login time, without
  an authorized client or without a refresh token is revoked at its first check. Provider error descriptions and tokens
  are never logged; only the revocation reason or the OAuth error code is.
- **Token storage**: the authorized client (access and refresh token) is stored in the `HttpSession`
  (`HttpSessionOAuth2AuthorizedClientRepository`), so it is discarded with the session instead of living in an
  application-wide map.
- **Back-channel logout**: the endpoint is anonymous, CSRF-exempt and creates no session; Spring Security validates
  the logout token signature against the provider JWKS, `iss`, `aud`, `iat`, the back-channel `events` claim, `sid` or
  `sub`, and the absence of `nonce`. It then replays the logout once per matching session to
  `dokene.security.oidc.back-channel-logout-uri` (`DOKENE_BACK_CHANNEL_LOGOUT_URI`), which defaults to loopback on the
  port the provider called; override it when the provider reaches Dokene through a proxy on another port. The provider
  client must register the back-channel URL (local realm: `http://oidc-bridge:8080/logout/connect/back-channel/dokene`)
  with `backchannel.logout.session.required=true`. Back-channel logout is an accelerator: when the provider cannot reach
  Dokene (for example a backend run on the host), revalidation still bounds the delay.
- **Provider alignment**: the local realm sets `ssoSessionMaxLifespan` to 8 h so that the provider and Dokene lifetimes
  agree. Revalidation keeps the provider SSO session active while the Dokene session is in use; the provider's idle
  timeout must not be shorter than `revalidation-interval`.
- **Upgrading an existing realm**: Keycloak's `--import-realm` skips a realm that already exists, so the realm import
  file configures new realms only. An existing realm receives the back-channel URL, its session and offline-token
  attributes and the SSO max lifespan from `scripts/keycloak-apply-realm-settings.sh`, which is idempotent,
  changes only the values it manages (these four, plus the #314 values in the amendment below) and has a `--check`
  mode that exits non-zero on drift. `scripts/dev-env.sh up` ran
  it after every start until issue 319; the Compose Keycloak now imports the realm file at every start
  ([ADR 0022](0022-containerized-local-environment.md)), so that call was removed. Other deployments apply the same four values to their realm and `dokene-bff` client (Admin
  Console or `kcadm.sh`) when they deploy this change. Until then IdP logout is bounded by revalidation instead of
  being immediate. *Amended 2026-11-06 ([#314](https://github.com/stevdrey/dokene/issues/314)):* the script, renamed
  from `keycloak-apply-session-revocation.sh`, now also applies the `S256` PKCE attribute and the realm password
  policy `length(12) and notUsername and notEmail`, and then sets each local test user's password from its own
  generated `.env` variable. `--check` covers the six config values; passwords cannot be checked. Other deployments
  set the PKCE attribute and a password policy of their own (see `infra/docker/README.md`, local template versus
  shared deployments).
- **Residual: local logout**: the SPA logout is local (`POST /logout` without `provider=true`); it ends the Dokene
  session but not the provider SSO session, so on a shared device the next login can be silent. Provider logout remains
  available through `POST /logout?provider=true` and is not the SPA default.

### Frontend BFF Contract & CSRF Protection

The BFF exposes a minimal same-origin session contract:

1. **Login Initiation**: Navigating to `/oauth2/authorization/{registrationId}` (e.g., `/oauth2/authorization/dokene`)
   initiates the confidential authorization-code redirect to Keycloak/OIDC provider.
2. **Post-Login Target**: The authentication success handler redirects to the application root (`/` by default in same-origin deployments, or configurable via `DOKENE_POST_LOGIN_REDIRECT_URL` / `dokene.security.post-login-redirect-url`), where the SPA bootstraps and queries `GET /api/session` to obtain initial session state.
3. **Authentication Failure & Stale Callback Recovery**: The authentication failure handler intercepts failed, canceled, or stale callback attempts (e.g. `/login/oauth2/code/{registrationId}`) and redirects the browser back to the frontend application (`/?error=login_failed` by default, or configurable via `DOKENE_POST_LOGIN_FAILURE_REDIRECT_URL` / `dokene.security.post-login-failure-redirect-url`). Framework-level default login page generation is suppressed (`.loginPage("/oauth2/authorization/dokene")`) and session creation is disabled on failure (`setAllowSessionCreation(false)`), ensuring internal provider paths or raw framework error pages are never exposed to browser users. A failed callback also ends the session its login start created, and never a signed-in session (issue #311).
4. **Session Check**: `GET /api/session` returns application-safe session metadata:
   `{ "authenticated": true, "identityId": "<uuid>", "csrfToken": "<token>" }`.
   Unauthenticated calls return `401 Unauthorized`.
5. **CSRF Protection**: CSRF protection is enforced for all state-changing HTTP methods (POST, PUT, DELETE, PATCH).
   The frontend retrieves the CSRF token from `GET /api/session` and transmits it in the `X-CSRF-TOKEN` header on
   mutation requests. State-changing requests to `/api/**` on expired or unauthenticated sessions fail closed with
   `401 Unauthorized` (rather than `403 Forbidden`), allowing the client SPA to trigger session-expired workflows.
   Authenticated requests with invalid or missing CSRF tokens fail closed with `403 Forbidden`. The token is stored
   in the session and only in a session that already exists, so an anonymous request is compared with a fresh
   random token, refused as before, and leaves nothing stored (issue #311).
6. **Logout (Local vs Provider SSO)**:
   - **Local Session Logout**: `POST /logout` requires `X-CSRF-TOKEN`, invalidates the server `HttpSession`, clears the
     security context, deletes the `JSESSIONID` cookie, and returns `204 No Content` for API clients.
   - **Provider SSO Logout**: `POST /logout?provider=true` requires `X-CSRF-TOKEN` (or `_csrf` form field), invalidates the
     local session, deletes `JSESSIONID`, and delegates to Spring Security's native `OidcClientInitiatedLogoutSuccessHandler`.
     The handler extracts the `idToken` from the server-side `OidcUser` (never exposed to client JavaScript) and redirects
     (`302 Found`) the browser to the provider's `end_session_endpoint` with `id_token_hint` and `post_logout_redirect_uri={baseUrl}/`.
     Keycloak terminates its SSO session and redirects the browser back to the application.
7. **CORS & Origin Model**: Same-origin deployment is the default architectural expectation. Cross-origin requests
   are rejected unless exact origins are explicitly configured in `dokene.security.cors.allowed-origins` (e.g.,
   `http://localhost:5173` for Vite local development).

### Tenant Authorization Boundary

Authentication establishes no `TenantContext`. Tenant-scoped operations must nominate a workspace via `X-Tenant-Id`,
and the server-side resolution pipeline verifies active membership before permitting access. Missing or foreign
memberships fail closed at the application and RLS boundaries with `403 Forbidden`.

## Rejected Alternative: Token-in-Browser Architecture

We explicitly rejected the alternative architecture where the React frontend acts as a public OAuth2 client and
stores access and refresh tokens directly in the browser (e.g., `localStorage`, `sessionStorage`, IndexedDB, or
JavaScript memory):

1. **XSS Vulnerability & Token Exfiltration**: Any cross-site scripting flaw (including vulnerabilities in third-party
   NPM dependencies) allows immediate exfiltration of raw JWT access tokens and long-lived refresh tokens from browser
   storage or memory. With a BFF, an XSS attacker cannot extract cryptographic tokens because the browser only holds
   an `HttpOnly` cookie.
2. **Client Secret Exposure**: A browser-based client cannot securely hold an OAuth2 client secret, requiring public
   client registration and increasing exposure to authorization code interception and token reuse across domains.
3. **Token Leakage in Browser Logging & Metrics**: Raw access and ID tokens stored in the browser frequently leak into
   browser error logs, monitoring payloads, and third-party analytics.
4. **Revocation & Invalidation Latency**: Stateless JWTs held in the browser cannot be instantly invalidated on logout
   without maintaining distributed server-side revocation blocklists. Server-side session invalidation immediately
   revokes all access upon logout or timeout.

## Consequences

- Tokens and client secrets remain entirely on the backend and are never exposed to client-side code.
- Horizontal multi-node deployments require sticky sessions or an external session store (e.g., Spring Session with
  Redis or PostgreSQL) when scaled beyond a single instance. The OIDC session registry used by back-channel logout is
  in memory as well and needs the same treatment; until then revalidation still bounds revocation on every node.
- Each active session redeems its refresh token at the provider once per `revalidation-interval`, which adds one token
  endpoint call per active session per interval.
- Single-page frontend architecture remains simple, issuing standard same-origin cookie-authenticated `fetch()` calls
  with `X-CSRF-TOKEN` headers.
- Compliance with Security Invariants #14 and #15 is structurally enforced at the framework boundary.
