# Issue #307 session revocation and absolute lifetime: verification record

Status: single PR. It closes audit finding M3 of the [security audit of 2026-11-02](../security/security-audit-2026-11-02.md): a Dokene BFF session outlived Keycloak, so a user disabled or logged out in the provider kept reading and writing, and an active session never expired. The contract is in [ADR 0007, Session Revocation and Absolute Lifetime](../adr/0007-oidc-server-side-session.md#session-revocation-and-absolute-lifetime).

This record is sanitized: it gives bounds, outcomes and test names only. It contains no reproduction script, token, credential or session identifier.

## Environment

- **Run:** 2026-11-03, branch `307-securitym3-enforce-idp-logout-and-absolute-bff-session-lifetime`, based on `main` at `6068295` (the audit tree `c4b62d1` plus the audit record and the #306 fix). The code was rechecked on that base before the change: M3 still applied as reported (no back-channel logout, idle timeout only, tokens never re-checked). **Tested revision:** the working tree that became the first commit of this branch on top of `6068295` (the commit that adds this record; a commit cannot name its own hash). Every check and run below used that tree.
- **Toolchain:** Gradle Wrapper with the Java 26 toolchain declared by the build; Spring Boot 4.1.2, Spring Security 7.1.2.
- **Automated tests:** Testcontainers PostgreSQL and an in-process OIDC provider stub that signs ID and logout tokens with a test RSA key.
- **Live run:** the local Compose stack (`compose.yaml`) with Keycloak 26.7.3 and the repository realm, synthetic users only. The backend ran with shortened bounds `DOKENE_SESSION_REVALIDATION_INTERVAL=1m`, `DOKENE_SESSION_REVALIDATION_GRACE=2m`, `DOKENE_SESSION_MAX_LIFETIME=4m` so that each bound could be observed in minutes; the defaults are unchanged.

## Configured bounds

| Setting | Default | Effect |
| --- | --- | --- |
| `dokene.security.session.max-lifetime` (`DOKENE_SESSION_MAX_LIFETIME`) | 8h | Every session ends this long after its login, however active |
| `dokene.security.session.revalidation-interval` (`DOKENE_SESSION_REVALIDATION_INTERVAL`) | 5m | The refresh token is redeemed at the provider once per interval; `invalid_grant` ends the session |
| `dokene.security.session.revalidation-grace` (`DOKENE_SESSION_REVALIDATION_GRACE`) | 15m | While the provider is unreachable the session survives until this long after its last successful revalidation |
| `dokene.security.session.revalidation-timeout` (`DOKENE_SESSION_REVALIDATION_TIMEOUT`) | 5s | Connect and response deadline of one revalidation call; a timeout is handled like an unreachable provider |
| `dokene.security.oidc.back-channel-logout-uri` (`DOKENE_BACK_CHANNEL_LOGOUT_URI`) | loopback on the called port | Where the back-channel handler replays the logout for each matching session |
| Realm `ssoSessionMaxLifespan` | 28800 s (8 h) | Provider lifetime aligned with the Dokene lifetime |
| Realm client `backchannel.logout.url` | `http://oidc-bridge:8080/logout/connect/back-channel/dokene` | Keycloak delivers logout tokens to the backend's network namespace |

Startup fails unless all durations are positive, the grace is longer than the interval, the timeout is shorter than the interval, and the grace does not exceed the lifetime.

## Automated coverage

| Acceptance item | Test |
| --- | --- |
| A valid logout token ends only the session it names; the endpoint sets no session cookie | `OidcBrowserSessionIntegrationTest.backChannelLogoutEndsOnlyTheMatchingSession` |
| Logout tokens signed by a foreign key, for another audience, carrying a `nonce`, without the back-channel event, or not a JWT are refused and the session stays valid | `OidcBrowserSessionIntegrationTest.backChannelLogoutRejectsUntrustedLogoutTokens` |
| A user whose refresh token the provider rejects (disabled) keeps access only until the interval passes, then `GET` and `POST` on `/api/**` are `401` | `OidcBrowserSessionIntegrationTest.disabledProviderUserIsDeniedOnceTheRevalidationIntervalHasPassed` |
| A successful revalidation calls the token endpoint once per interval, keeps the session and exposes no token | `OidcBrowserSessionIntegrationTest.revalidationRefreshesTheProviderSessionAndKeepsTheBrowserSession`; `BffSessionLifetimeFilterTest.revalidatesAfterTheIntervalAndStoresTheRotatedTokens` |
| An active session is `200` one minute before the lifetime and `401` at it; a new login starts a new lifetime | `OidcBrowserSessionIntegrationTest.activeSessionEndsAtTheAbsoluteLifetime`; `BffSessionLifetimeFilterTest.endsAnActiveSessionAtTheAbsoluteLifetime` (a request every interval for 8 h) |
| An unreachable provider keeps the session within the grace and ends it after; recovery inside the grace restarts the grace | `OidcBrowserSessionIntegrationTest.unreachableProviderKeepsTheSessionOnlyWithinTheGrace`; `BffSessionLifetimeFilterTest.keepsTheSessionWhileTheProviderIsUnavailableWithinTheGrace`, `recoversWhenTheProviderReturnsWithinTheGrace` |
| A provider that accepts the refresh call and then stalls (10 s) does not hang the request: with a 1 s timeout the request answers `200` inside the grace and `401` after it, each in under 3 s | `OidcBrowserSessionIntegrationTest.stalledProviderTimesOutIntoTheGraceInsteadOfHangingTheRequest` |
| An OIDC session without a login time or without a refresh token fails closed; non-OIDC sessions are untouched | `BffSessionLifetimeFilterTest.revokesAnOidcSessionWithoutALifetimeRecord`, `revokesWhenTheSessionHasNoRefreshToken`, `ignoresSessionsThatWereNotEstablishedByOidcLogin` |
| Revocation logs carry the reason only, never tokens | `BffSessionLifetimeFilterTest.revokesWhenTheProviderRejectsTheRefreshToken`; `OidcBrowserSessionIntegrationTest.oauthClientSecretAndTokensAreNeverLogged` |
| Inconsistent bounds fail startup | `BffSessionLifetimeFilterTest.rejectsInconsistentProperties` |
| Login, session fixation, CSRF, idle expiry, local and provider logout are unchanged | the existing `OidcBrowserSessionIntegrationTest` cases |
| Membership revocation still ends tenant access | `TenantProvisioningIntegrationTest.revokedOrSuspendedMembershipCannotEstablishTenantContext`, `MembershipManagementIntegrationTest` |

**Full run:** `./gradlew check` passed: 2350 tests, 0 failures, 0 skipped.

**Known framework behavior:** Spring Security 7.1.2 answers `500` instead of `400` to a signed logout token that has no `events` claim (its validator throws on the null claim). The token is still refused and no session is affected; the test asserts that outcome.

## Live run against Keycloak

| Scenario | Outcome |
| --- | --- |
| Normal login; `POST /logout` without CSRF; with CSRF; new login | `200`; `403`; `204` then `401`; `200` |
| Two sessions of one user; admin `POST /users/{id}/logout` | Both sessions `401` within 1 s, well inside the 1 min interval: the back-channel logout was delivered |
| Admin `PUT enabled=false` without logging the user out | `200` immediately after; `401` 60 s later (interval 1 min). Backend log: `BFF session revoked: PROVIDER_REJECTED` |
| A request every 15 s from login on | `401` after 241 s (lifetime 4 min). Backend log: `BFF session revoked: MAX_LIFETIME` |
| Owner provisions a workspace, adds a viewer, then revokes the membership | Viewer `GET /api/customers` `200`, then `403` on the same session while `/api/session` stays `200` |

The backend logs of the live run contain no access, refresh or ID token. Keycloak did not end the user's sessions when the user was disabled; revalidation, not back-channel logout, is what bounds the disablement case.

## Residuals

- The SPA logout stays local (`POST /logout`), so the Keycloak SSO session survives it and the next login on a shared device can be silent. `POST /logout?provider=true` remains available. Recorded in ADR 0007.
- Sessions and the OIDC session registry are in memory: a multi-node deployment needs sticky sessions or a shared store before back-channel logout reaches every node. Revalidation still bounds revocation on each node.
