# Issue 51 HTTP & cURL Verification

Date: 2026-09-13

## Environment

- Java: Temurin 26.0.2.1
- Docker Engine: 29.7.2
- PostgreSQL: `postgres:17-alpine` container via Docker Compose (port 5432)
- Keycloak: `quay.io/keycloak/keycloak:26.7.3` container via Docker Compose (loopback port 8081)
- Application: Spring Boot 4.1.1 modular monolith (port 8080)
- Client: Host `curl` executable
- Schema: Flyway migrations V1 through V11 applied successfully
- Realm & Client: `dokene` realm with confidential client `dokene-bff` (authorization code flow) and test user `testuser`

All credentials, session tokens, CSRF tokens, and identity UUIDs used during testing are synthetic and ephemeral.

## Reproduction

```shell
# 1. Start local dependencies
docker compose up -d

# 2. Run backend
set -a; [ -f .env ] && . ./.env; set +a
(cd backend && ./gradlew bootRun)

# 3. Execute automated test suite
(cd backend && ./gradlew test --tests "io.github.stevdrey.dokene.identity.security.OidcBrowserSessionIntegrationTest")
(cd backend && ./gradlew check)
```

## Verified Scenarios

### 1. Fail-Closed & Anonymous Access

| Scenario | Request | Expected | Observed | Status |
| --- | --- | --- | --- | --- |
| Anonymous session status | `GET /api/session` | `401 Unauthorized` | `HTTP/1.1 401` with `Set-Cookie: JSESSIONID=...; Path=/; HttpOnly; SameSite=Lax` | PASS |
| Anonymous tenant list | `GET /api/tenants` | `401 Unauthorized` | `HTTP/1.1 401` | PASS |
| Anonymous state-changing logout | `POST /logout` | `403 Forbidden` | `HTTP/1.1 403` (CSRF check triggers before auth) | PASS |

### 2. Attack Vectors & Security Invariants

| Attack / Breaking Attempt | Vector | Expected | Observed | Status |
| --- | --- | --- | --- | --- |
| Tampered / forged session cookie | `GET /api/session` with `Cookie: JSESSIONID=forged-tampered-token-12345` | `401 Unauthorized` | `HTTP/1.1 401` (fail-closed, no session created) | PASS |
| Tampered session cookie on protected resource | `GET /api/tenants` with `Cookie: JSESSIONID=forged-tampered-token-12345` | `401 Unauthorized` | `HTTP/1.1 401` | PASS |
| Session fixation attack | Replaying pre-authentication session ID after successful login | `401 Unauthorized` | `HTTP/1.1 401` (pre-auth session rotated and invalidated) | PASS |
| Unauthorized cross-origin request | Request with `Origin: https://attacker.example.com` | Deny CORS | No `Access-Control-Allow-Origin` header emitted | PASS |
| Mutation without CSRF token | Authenticated `POST /logout` without `X-CSRF-TOKEN` | `403 Forbidden` | `HTTP/1.1 403` | PASS |
| Mutation with forged CSRF token | Authenticated `POST /logout` with invalid `X-CSRF-TOKEN` | `403 Forbidden` | `HTTP/1.1 403` | PASS |
| Host-header poisoning / untrusted proxy header | Request with `X-Forwarded-Host: attacker.example.com` under default `none` strategy | Ignore untrusted header | Auth redirect uses socket authority (`http://localhost:8080`), attacker host ignored | PASS |
| Cross-tenant boundary breach | Authenticated identity accessing `GET /api/customers` with foreign `X-Tenant-Id` | `403 Forbidden` | `HTTP/1.1 403` | PASS |

### 3. Happy Path: Full OIDC BFF Flow & Two-Tier Logout

| Step | Operation | Details | Status |
| --- | --- | --- | --- |
| 1. Initiate login | `GET /oauth2/authorization/dokene` | `302 Found` redirecting to Keycloak auth endpoint with PKCE (`code_challenge`) and state. Set initial pre-auth session cookie. | PASS |
| 2. Submit credentials | `POST .../login-actions/authenticate` | Submitted `testuser` credentials to Keycloak; received `302 Found` redirect to Dokene callback with single-use authorization code. | PASS |
| 3. Code exchange & rotation | `GET /login/oauth2/code/dokene?code=...&state=...` | Spring Boot executed confidential code exchange server-side; rotated session to fresh `JSESSIONID`; responded with `302 Found` redirecting to `/api/session`. | PASS |
| 4. Session inspection | `GET /api/session` | Returned `200 OK` with JSON `{ "authenticated": true, "identityId": "...", "csrfToken": "..." }`. No provider tokens (`access_token`, `id_token`, `refresh_token`) or client secret present. | PASS |
| 5. Local session logout | `POST /logout` with `X-CSRF-TOKEN` | Returned `204 No Content`, cleared server session and deleted `JSESSIONID` cookie. Subsequent `GET /api/session` returned `401 Unauthorized`. | PASS |
| 6. Provider session logout | `POST /logout?provider=true` with `X-CSRF-TOKEN` | Returned `302 Found` redirecting to Keycloak `protocol/openid-connect/logout?id_token_hint=...&post_logout_redirect_uri=...`. Server session cleared; raw tokens never exposed to client. Subsequent `GET /api/session` returned `401 Unauthorized`. | PASS |

### 4. Edge Cases

| Scenario | Request | Expected | Observed | Status |
| --- | --- | --- | --- | --- |
| Tampered OAuth2 callback state | Callback with altered `state` query parameter | `302` to error, no session | Session remains unauthenticated (`GET /api/session` -> `401`) | PASS |
| Consumed authorization code replay | Replay of already-used authorization code | Fails exchange | `GET /api/session` -> `401` | PASS |
