#!/usr/bin/env bash
# ==============================================================================
# verify-keycloak-bff-e2e.sh
#
# Deterministic End-to-End Verification of Keycloak -> Spring Boot BFF -> Session
#
# Covers Acceptance Criteria for Issue #52:
# 1. Real Keycloak Authorization Code flow and session creation.
# 2. Token isolation: OAuth tokens (access, ID, refresh) and client secrets
#    are never exposed to browser-visible contracts or URLs.
# 3. Session cookie attributes: HttpOnly and SameSite enforcement.
# 4. Session fixation protection: pre-login session cannot be reused.
# 5. CSRF protection on state-changing endpoints (rejection & success).
# 6. Unauthenticated (401) and forbidden (403) handling.
# 7. Local session logout, session invalidation, and post-logout reuse rejection.
# ==============================================================================

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COOKIE_JAR="$(mktemp /tmp/dokene-e2e-cookies.XXXXXX)"
PRE_AUTH_COOKIE_JAR="$(mktemp /tmp/dokene-e2e-preauth.XXXXXX)"
HEADER_LOG="$(mktemp /tmp/dokene-e2e-headers.XXXXXX)"
BODY_LOG="$(mktemp /tmp/dokene-e2e-body.XXXXXX)"

cleanup() {
    rm -f "$COOKIE_JAR" "$PRE_AUTH_COOKIE_JAR" "$HEADER_LOG" "$BODY_LOG"
}
trap cleanup EXIT

# Read configuration from .env if available
if [ -f "$ROOT_DIR/.env" ]; then
    # shellcheck disable=SC1091
    set -a; . "$ROOT_DIR/.env"; set +a
fi

KEYCLOAK_PORT="${KEYCLOAK_PORT:-8081}"
BFF_URL="${BFF_URL:-http://localhost:8080}"
KEYCLOAK_URL="http://localhost:${KEYCLOAK_PORT}"
TEST_USER="testuser"
TEST_PASSWORD="${DOKENE_TEST_USER_PASSWORD:-testpassword}"

echo "======================================================================"
echo "Dokene BFF End-to-End Security Verification (Issue #52)"
echo "Target BFF:      $BFF_URL"
echo "Target Keycloak: $KEYCLOAK_URL"
echo "======================================================================"

# ------------------------------------------------------------------------------
# 1. Verify Prerequisites and Connectivity
# ------------------------------------------------------------------------------
command -v curl >/dev/null 2>&1 || { echo "Error: curl is required." >&2; exit 1; }
command -v jq >/dev/null 2>&1 || { echo "Error: jq is required." >&2; exit 1; }

echo -n "[1/8] Verifying Keycloak OIDC discovery endpoint... "
DISCOVERY_STATUS="$(curl -s -o /dev/null -w "%{http_code}" "$KEYCLOAK_URL/realms/dokene/.well-known/openid-configuration" || true)"
if [ "$DISCOVERY_STATUS" != "200" ]; then
    echo "FAILED (HTTP $DISCOVERY_STATUS)"
    echo "Keycloak is not running or unreachable at $KEYCLOAK_URL."
    echo "Please start local infrastructure: docker compose up -d"
    exit 1
fi
echo "OK (HTTP 200)"

echo -n "[2/8] Verifying Spring Boot BFF is active... "
BFF_STATUS="$(curl -s -o /dev/null -w "%{http_code}" "$BFF_URL/api/session" || true)"
if [ "$BFF_STATUS" != "401" ]; then
    echo "FAILED (HTTP $BFF_STATUS)"
    echo "Expected BFF at $BFF_URL to return 401 Unauthorized for anonymous /api/session."
    echo "Please launch backend: (cd backend && ./gradlew bootRun)"
    exit 1
fi
echo "OK (HTTP 401 fail-closed for anonymous /api/session)"

# ------------------------------------------------------------------------------
# 2. Initiate Login Flow (Authorization Code Grant)
# ------------------------------------------------------------------------------
echo -n "[3/8] Initiating Authorization Code flow (/oauth2/authorization/dokene)... "
AUTH_INIT_STATUS="$(curl -s -c "$COOKIE_JAR" -D "$HEADER_LOG" -o "$BODY_LOG" -w "%{http_code}" "$BFF_URL/oauth2/authorization/dokene")"

if [ "$AUTH_INIT_STATUS" != "302" ]; then
    echo "FAILED (Expected HTTP 302, got $AUTH_INIT_STATUS)"
    exit 1
fi

KEYCLOAK_AUTH_URL="$(grep -i "^location:" "$HEADER_LOG" | tr -d '\r\n' | awk '{print $2}')"
if [[ "$KEYCLOAK_AUTH_URL" != *"/realms/dokene/protocol/openid-connect/auth"* ]]; then
    echo "FAILED (Unexpected redirect location: $KEYCLOAK_AUTH_URL)"
    exit 1
fi

# Capture pre-auth JSESSIONID
PRE_AUTH_SESSION_ID="$(grep "JSESSIONID" "$COOKIE_JAR" | awk '{print $NF}' | tail -n 1)"
if [ -z "$PRE_AUTH_SESSION_ID" ]; then
    echo "FAILED (Pre-auth JSESSIONID cookie not set)"
    exit 1
fi
cp "$COOKIE_JAR" "$PRE_AUTH_COOKIE_JAR"
echo "OK (Redirected to Keycloak; pre-auth session captured)"

# ------------------------------------------------------------------------------
# 3. Authenticate with Keycloak and Obtain Authorization Code
# ------------------------------------------------------------------------------
echo -n "[4/8] Executing browser credentials submit against Keycloak... "
# Fetch the Keycloak login HTML page to extract form action URL
curl -s -c "$COOKIE_JAR" -b "$COOKIE_JAR" -D "$HEADER_LOG" -o "$BODY_LOG" "$KEYCLOAK_AUTH_URL"

# Extract form action URL (handling HTML encoding &amp;)
FORM_ACTION="$(grep -o 'action="[^"]*"' "$BODY_LOG" | head -n 1 | cut -d'"' -f2 | sed 's/&amp;/\&/g')"
if [ -z "$FORM_ACTION" ]; then
    echo "FAILED (Could not parse login form action from Keycloak HTML)"
    exit 1
fi

# Submit credentials
curl -s -c "$COOKIE_JAR" -b "$COOKIE_JAR" -D "$HEADER_LOG" -o "$BODY_LOG" \
    --data-urlencode "username=$TEST_USER" \
    --data-urlencode "password=$TEST_PASSWORD" \
    "$FORM_ACTION"

CALLBACK_URL="$(grep -i "^location:" "$HEADER_LOG" | tr -d '\r\n' | awk '{print $2}')"
if [[ "$CALLBACK_URL" != *"/login/oauth2/code/dokene"* ]]; then
    echo "FAILED (Authentication failed; redirect was not callback: $CALLBACK_URL)"
    exit 1
fi
echo "OK (Keycloak issued authorization code)"

# ------------------------------------------------------------------------------
# 4. Exchange Authorization Code and Establish BFF Session
# ------------------------------------------------------------------------------
echo -n "[5/8] Completing BFF code exchange and session establishment... "
curl -s -c "$COOKIE_JAR" -b "$COOKIE_JAR" -D "$HEADER_LOG" -o "$BODY_LOG" "$CALLBACK_URL"

POST_LOGIN_REDIRECT="$(grep -i "^location:" "$HEADER_LOG" | tr -d '\r\n' | awk '{print $2}')"
# Assert tokens are NOT present in redirect URL
if [[ "$POST_LOGIN_REDIRECT" == *"access_token"* || "$POST_LOGIN_REDIRECT" == *"id_token"* || "$POST_LOGIN_REDIRECT" == *"refresh_token"* ]]; then
    echo "FAILED (OAuth tokens exposed in post-login redirect URL: $POST_LOGIN_REDIRECT)"
    exit 1
fi

# Check rotated JSESSIONID
POST_AUTH_SESSION_ID="$(grep "JSESSIONID" "$COOKIE_JAR" | awk '{print $NF}' | tail -n 1)"
if [ -z "$POST_AUTH_SESSION_ID" ]; then
    echo "FAILED (Post-auth JSESSIONID cookie missing)"
    exit 1
fi

if [ "$POST_AUTH_SESSION_ID" = "$PRE_AUTH_SESSION_ID" ]; then
    echo "FAILED (Session fixation violation: session ID was not rotated)"
    exit 1
fi

# Verify cookie attributes
SET_COOKIE_HEADER="$(grep -i "^set-cookie:" "$HEADER_LOG" | grep -i "JSESSIONID" || true)"
if [[ "$SET_COOKIE_HEADER" != *"HttpOnly"* ]]; then
    echo "FAILED (Session cookie missing HttpOnly attribute)"
    exit 1
fi
if [[ "$SET_COOKIE_HEADER" != *"SameSite=Lax"* ]]; then
    echo "FAILED (Session cookie missing SameSite=Lax attribute)"
    exit 1
fi

# Session fixation negative check: replay pre-auth session
PRE_AUTH_REPLAY_STATUS="$(curl -s -b "JSESSIONID=$PRE_AUTH_SESSION_ID" -o /dev/null -w "%{http_code}" "$BFF_URL/api/session")"
if [ "$PRE_AUTH_REPLAY_STATUS" != "401" ]; then
    echo "FAILED (Pre-auth session adoption allowed! HTTP $PRE_AUTH_REPLAY_STATUS)"
    exit 1
fi
echo "OK (Session rotated, HttpOnly & SameSite enforced, pre-auth session rejected)"

# ------------------------------------------------------------------------------
# 5. Verify Session Contract and Token Isolation
# ------------------------------------------------------------------------------
echo -n "[6/8] Verifying application session contract and token isolation... "
curl -s -b "$COOKIE_JAR" -D "$HEADER_LOG" -o "$BODY_LOG" "$BFF_URL/api/session"

SESSION_JSON="$(cat "$BODY_LOG")"
IS_AUTHENTICATED="$(echo "$SESSION_JSON" | jq -r '.authenticated // false')"
IDENTITY_ID="$(echo "$SESSION_JSON" | jq -r '.identityId // empty')"
CSRF_TOKEN="$(echo "$SESSION_JSON" | jq -r '.csrfToken // empty')"

if [ "$IS_AUTHENTICATED" != "true" ] || [ -z "$IDENTITY_ID" ] || [ -z "$CSRF_TOKEN" ]; then
    echo "FAILED (Invalid session contract: $SESSION_JSON)"
    exit 1
fi

# Assert OAuth tokens and secrets are strictly absent
for forbidden_key in access_token id_token refresh_token client_secret; do
    if echo "$SESSION_JSON" | grep -q "$forbidden_key"; then
        echo "FAILED (Forbidden key '$forbidden_key' exposed in session response!)"
        exit 1
    fi
done
echo "OK (Safe session contract verified; tokens strictly isolated)"

# ------------------------------------------------------------------------------
# 6. Verify CSRF Protection on State-Changing Endpoint
# ------------------------------------------------------------------------------
echo -n "[7/8] Verifying CSRF enforcement on state-changing requests... "
# Missing CSRF token -> 403 Forbidden
CSRF_MISSING_STATUS="$(curl -s -b "$COOKIE_JAR" -X POST -o /dev/null -w "%{http_code}" "$BFF_URL/logout")"
if [ "$CSRF_MISSING_STATUS" != "403" ]; then
    echo "FAILED (Mutation without CSRF token returned $CSRF_MISSING_STATUS, expected 403)"
    exit 1
fi

# Invalid CSRF token -> 403 Forbidden
INVALID_CSRF_HEADER="X-CSRF-TOKEN: invalid"
CSRF_INVALID_STATUS="$(curl -s -b "$COOKIE_JAR" -H "$INVALID_CSRF_HEADER" -X POST -o /dev/null -w "%{http_code}" "$BFF_URL/logout")"
if [ "$CSRF_INVALID_STATUS" != "403" ]; then
    echo "FAILED (Mutation with forged CSRF token returned $CSRF_INVALID_STATUS, expected 403)"
    exit 1
fi
echo "OK (CSRF enforcement strictly rejected missing and invalid tokens)"

# ------------------------------------------------------------------------------
# 7. Verify Logout and Session Invalidation
# ------------------------------------------------------------------------------
echo -n "[8/8] Verifying session logout, invalidation, and replay prevention... "
# Valid logout -> 204 No Content
VALID_CSRF_HEADER="X-CSRF-TOKEN: $CSRF_TOKEN"
LOGOUT_STATUS="$(curl -s -b "$COOKIE_JAR" -H "$VALID_CSRF_HEADER" -X POST -D "$HEADER_LOG" -o /dev/null -w "%{http_code}" "$BFF_URL/logout")"
if [ "$LOGOUT_STATUS" != "204" ]; then
    echo "FAILED (Logout returned HTTP $LOGOUT_STATUS, expected 204)"
    exit 1
fi

# Post-logout request with logged-out session -> 401 Unauthorized
POST_LOGOUT_STATUS="$(curl -s -b "JSESSIONID=$POST_AUTH_SESSION_ID" -o /dev/null -w "%{http_code}" "$BFF_URL/api/session")"
if [ "$POST_LOGOUT_STATUS" != "401" ]; then
    echo "FAILED (Logged-out session was not invalidated! HTTP $POST_LOGOUT_STATUS)"
    exit 1
fi
echo "OK (Session terminated and subsequent calls fail closed with 401)"

echo "======================================================================"
echo "SUCCESS: All 8 End-to-End BFF Security Assertions PASSED!"
echo "======================================================================"
