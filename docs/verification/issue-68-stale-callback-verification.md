# Verification Evidence: Issue #68

## Objective
Verify the resolution of [Issue #68](https://github.com/stevdrey/dokene/issues/68): intercepting stale or failed OIDC callbacks in the BFF, redirecting back to the user-facing SPA, suppressing Spring Security default internal login error pages, and rendering a recoverable localized error alert in Dokene's frontend.

---

## 1. Automated Verification

### Backend Verification
- **Command:** `./gradlew test --tests io.github.stevdrey.dokene.tenant.security.TenantSecurityConfigurationTest`
  - **Result:** PASSED (5 tests). Verified dynamic resolution of `dokene.security.post-login-failure-redirect-url` across configured, root, local Vite dev (`http://localhost:5173/`), and query param permutations.
- **Command:** `./gradlew test --tests io.github.stevdrey.dokene.identity.security.BffTenantBoundarySecurityIntegrationTest.staleOrFailedOidcCallbackRedirectsToFrontendFailureUrlWithoutExposingInternalPages`
  - **Result:** PASSED. Verified:
    1. Stale callback (`/login/oauth2/code/dokene?code=...&state=...`) returns HTTP 302 redirecting to `/?error=login_failed`.
    2. Response does not leak tokens, secrets, or internal provider discovery paths.
    3. Session is not created on authentication failure (`/api/session` returns 401).
    4. Accessing `/login?error` does not render Spring Security's default HTML login page.
- **Command:** `./gradlew check`
  - **Result:** BUILD SUCCESSFUL.

### Frontend Verification
- **Command:** `npm test -- --run src/features/auth/LoginView.test.tsx src/App.test.tsx`
  - **Result:** PASSED (11 tests). Verified LoginView error alert rendering and recovery interaction.
- **Command:** `npm test -- --run`
  - **Result:** PASSED (181 tests across 17 test files).

---

## 2. End-to-End Live Stack Verification

### Reproduction & Fix Verification via cURL
1. **Stale Callback cURL:**
   ```bash
   curl -i "http://localhost:8080/login/oauth2/code/dokene?code=stale_code_xyz&state=stale_state_xyz"
   ```
   **Response:**
   ```http
   HTTP/1.1 302 
   Location: http://localhost:5173/?error=login_failed
   Content-Length: 0
   ```
   *Behavior:* Intercepted and cleanly redirected to the user-facing frontend with `?error=login_failed`.

2. **Direct `/login?error` probe on port 8080:**
   ```bash
   curl -i "http://localhost:8080/login?error"
   ```
   **Response:**
   ```http
   HTTP/1.1 404 
   Content-Type: application/json
   {"timestamp":"...","status":404,"error":"Not Found","path":"/login"}
   ```
   *Behavior:* Spring Security default login page generator is suppressed; internal Keycloak URLs and raw forms are never rendered.

---

## 3. Browser Manual Verification

- Tested navigating to:
  `http://localhost:5173/login/oauth2/code/dokene?code=stale_code_xyz&state=stale_state_xyz`
- **Observed Flow:**
  - Browser automatically redirected to `http://localhost:5173/?error=login_failed`.
  - DOM rendered localized error message:
    > *"No se pudo completar el inicio de sesión o el enlace de autenticación ha expirado. Por favor intenta iniciar sesión nuevamente."*
  - Re-authentication button *"Iniciar sesión con OIDC"* is prominently displayed.
  - Clicking the button successfully initiates a fresh confidential authorization code redirect to Keycloak.

### Visual Evidence
Screenshot captured: `docs/verification/issue-68-evidence/01-stale-callback-recovered-ui.png`
