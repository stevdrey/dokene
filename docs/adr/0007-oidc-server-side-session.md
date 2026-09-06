# ADR 0007: OIDC Authentication with Server-Side Browser Sessions

## Status

Accepted

## Context

Dokene needs provider-neutral internal identities before an operator can select and enter a tenant. Provider
tokens must not become domain credentials or browser-persistent state, and authentication must not imply tenant
membership. The application is currently a servlet-based modular monolith, so a server-side browser session is
the smallest architecture that centralizes token handling and logout.

## Decision

Dokene uses Spring Security's OIDC authorization-code flow. Login starts at
`/oauth2/authorization/{registrationId}` and the registered callback is
`/login/oauth2/code/{registrationId}`. Spring Security performs discovery and validates callback state, token
signature, issuer, audience, nonce where supplied, and temporal claims before the identity adapter runs.

The adapter takes only the validated, case-sensitive `(issuer, subject)` pair. PostgreSQL resolves it through a
single atomic upsert into `oidc_identity_mappings`, whose unique constraint prevents duplicate mappings during
concurrent first logins. The generated `IdentityId` is the only identity exposed to tenant resolution. Email,
names, groups, and provider roles are neither account-linking keys nor authorization inputs. The mapping table
contains no credentials or tokens and is accessible to the runtime role only through the narrow resolver
function.

After authentication, the security context is stored in the server-side HTTP session. Provider tokens are not
returned by Dokene APIs and must not be logged or written to `localStorage`, session storage, or ordinary
application tables. The cookie is `HttpOnly`, `Secure`, and `SameSite=Lax`; local plain HTTP requires an explicit
secure-cookie override. Session fixation protection uses Spring Security's defaults. Sessions expire after 30
minutes by default and may be configured operationally.

`GET /api/session` is an authenticated global endpoint that returns the stable internal identity plus the CSRF
token and is the successful-login redirect target. It returns `401` after expiration or without authentication.
`POST /logout` requires CSRF, invalidates the
server session, clears authentication, deletes `JSESSIONID`, and returns `204`. CSRF remains enabled for all
unsafe HTTP methods. Same-origin browser traffic is the default; credentialed CORS is accepted only for exact
origins in the configured allowlist.

Authentication establishes no `TenantContext`. Tenant-scoped requests still nominate a tenant with
`X-Tenant-Id`, and the existing resolution pipeline verifies an active server-side membership before any tenant
access. Missing, inactive, or foreign membership continues to fail closed at application authorization and RLS
boundaries.

## Consequences

- OIDC providers remain replaceable through configuration without changing domain identity or tenant code.
- Horizontal deployments need sticky sessions or a shared Spring Session store before they are introduced.
- Provider-initiated logout, identity merging, workspace provisioning, and multiple-account linking are separate
  designs and are not implied by this mapping.
- Compromise of the application runtime remains capable of invoking identity resolution, but runtime SQL cannot
  enumerate or mutate the mapping table directly.
