#!/usr/bin/env bash
# ABOUTME: End to end check of the real Keycloak -> Spring Boot BFF login, session, CSRF and logout (issue #52).
# ABOUTME: Also proves Keycloak refuses logins without S256 PKCE and that each test user has its own password (#314).
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
#
# And for issue #314 (audit L9):
# 8. Keycloak refuses an authorization request without PKCE or with the plain method.
# 9. testuser, testoperator and testviewer each log in with their own password from
#    .env, and one user's password does not log in as another user.
# ==============================================================================

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COOKIE_JAR="$(mktemp /tmp/dokene-e2e-cookies.XXXXXX)"
PRE_AUTH_COOKIE_JAR="$(mktemp /tmp/dokene-e2e-preauth.XXXXXX)"
HEADER_LOG="$(mktemp /tmp/dokene-e2e-headers.XXXXXX)"
BODY_LOG="$(mktemp /tmp/dokene-e2e-body.XXXXXX)"
LOGIN_JAR="$(mktemp /tmp/dokene-e2e-login.XXXXXX)"

cleanup() {
    rm -f "$COOKIE_JAR" "$PRE_AUTH_COOKIE_JAR" "$HEADER_LOG" "$BODY_LOG" "$LOGIN_JAR"
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
MISSING_PASSWORD_HINT="is not set; run ./scripts/dev-env.sh up, which generates it in .env"
OWNER_PASSWORD="${DOKENE_TEST_OWNER_PASSWORD:?DOKENE_TEST_OWNER_PASSWORD $MISSING_PASSWORD_HINT}"
OPERATOR_PASSWORD="${DOKENE_TEST_OPERATOR_PASSWORD:?DOKENE_TEST_OPERATOR_PASSWORD $MISSING_PASSWORD_HINT}"
VIEWER_PASSWORD="${DOKENE_TEST_VIEWER_PASSWORD:?DOKENE_TEST_VIEWER_PASSWORD $MISSING_PASSWORD_HINT}"
TEST_USER="testuser"
TOTAL_STEPS=11
TEST_CLIENT_SECRET="${DOKENE_OIDC_CLIENT_SECRET:-dokene-bff-secret}"

echo "======================================================================"
echo "Dokene BFF End-to-End Security Verification (Issue #52)"
echo "Target BFF:      $BFF_URL"
echo "Target Keycloak: $KEYCLOAK_URL"
echo "======================================================================"

location_header() { grep -i "^location:" "$HEADER_LOG" | tr -d '\r\n' | awk '{print $2}'; }

# submit_credentials JAR FORM_ACTION USERNAME PASSWORD: posts the Keycloak login form and prints the HTTP status. The
# password reaches curl on stdin, never its command line. Headers land in HEADER_LOG, the body in BODY_LOG.
submit_credentials() {
    printf '%s' "$4" | curl -s -c "$1" -b "$1" -D "$HEADER_LOG" -o "$BODY_LOG" -w "%{http_code}" \
        --data-urlencode "username=$3" --data-urlencode "password@-" "$2"
}

# start_login JAR: starts a BFF login in a fresh cookie jar and prints the Keycloak login form action.
start_login() {
    : >"$1"
    curl -s -c "$1" -D "$HEADER_LOG" -o /dev/null "$BFF_URL/oauth2/authorization/dokene"
    curl -s -c "$1" -b "$1" -o "$BODY_LOG" "$(location_header)"
    grep -o 'action="[^"]*"' "$BODY_LOG" | head -n 1 | cut -d'"' -f2 | sed 's/&amp;/\&/g'
}

# without_pkce URL: URL with its code_challenge and code_challenge_method parameters removed.
without_pkce() {
    sed -E 's/([?&])code_challenge(_method)?=[^&]*/\1/g; s/&&+/\&/g; s/[?]&/?/; s/&$//' <<<"$1"
}

# expect_pkce_refused URL DESCRIPTION: Keycloak must answer 302 with error=invalid_request, never the login form.
expect_pkce_refused() {
    local status location
    status="$(curl -s -D "$HEADER_LOG" -o "$BODY_LOG" -w "%{http_code}" "$1")"
    location="$(location_header)"
    if [ "$status" != "302" ] || [[ "$location" != *"error=invalid_request"* ]] || grep -q 'action="' "$BODY_LOG"; then
        echo "FAILED ($2 was not refused: HTTP $status, Location ${location:-<none>})"
        exit 1
    fi
}

# ------------------------------------------------------------------------------
# 1. Verify Prerequisites and Connectivity
# ------------------------------------------------------------------------------
command -v curl >/dev/null 2>&1 || { echo "Error: curl is required." >&2; exit 1; }
command -v jq >/dev/null 2>&1 || { echo "Error: jq is required." >&2; exit 1; }

echo -n "[1/$TOTAL_STEPS] Verifying Keycloak OIDC discovery endpoint... "
DISCOVERY_STATUS="$(curl -s -o /dev/null -w "%{http_code}" "$KEYCLOAK_URL/realms/dokene/.well-known/openid-configuration" || true)"
if [ "$DISCOVERY_STATUS" != "200" ]; then
    echo "FAILED (HTTP $DISCOVERY_STATUS)"
    echo "Keycloak is not running or unreachable at $KEYCLOAK_URL."
    echo "Please start local infrastructure: ./scripts/dev-env.sh up"
    exit 1
fi
echo "OK (HTTP 200)"

echo -n "[2/$TOTAL_STEPS] Verifying Spring Boot BFF is active... "
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
echo -n "[3/$TOTAL_STEPS] Initiating Authorization Code flow (/oauth2/authorization/dokene)... "
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

# Assert PKCE RFC 7636 S256 parameters generated by Spring Boot BFF
if [[ "$KEYCLOAK_AUTH_URL" != *"code_challenge="* || "$KEYCLOAK_AUTH_URL" != *"code_challenge_method=S256"* ]]; then
    echo "FAILED (PKCE RFC 7636 S256 parameters missing from redirect: $KEYCLOAK_AUTH_URL)"
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
# 2b. Keycloak itself enforces S256 PKCE (issue #314)
# ------------------------------------------------------------------------------
echo -n "[4/$TOTAL_STEPS] Verifying Keycloak refuses authorization requests without S256 PKCE... "
NO_PKCE_URL="$(without_pkce "$KEYCLOAK_AUTH_URL")"
if [[ "$NO_PKCE_URL" == *"code_challenge"* ]]; then
    echo "FAILED (could not strip the PKCE parameters from $KEYCLOAK_AUTH_URL)"
    exit 1
fi
expect_pkce_refused "$NO_PKCE_URL" "a request with no code_challenge"
expect_pkce_refused "$NO_PKCE_URL&code_challenge_method=plain&code_challenge=$(printf 'p%.0s' {1..43})" \
    "a request with code_challenge_method=plain"
echo "OK (no challenge and plain both get invalid_request, no login form)"

# ------------------------------------------------------------------------------
# 3. Authenticate with Keycloak and Obtain Authorization Code
# ------------------------------------------------------------------------------
echo -n "[5/$TOTAL_STEPS] Executing browser credentials submit against Keycloak... "
# Fetch the Keycloak login HTML page to extract form action URL
curl -s -c "$COOKIE_JAR" -b "$COOKIE_JAR" -D "$HEADER_LOG" -o "$BODY_LOG" "$KEYCLOAK_AUTH_URL"

# Extract form action URL (handling HTML encoding &amp;)
FORM_ACTION="$(grep -o 'action="[^"]*"' "$BODY_LOG" | head -n 1 | cut -d'"' -f2 | sed 's/&amp;/\&/g')"
if [ -z "$FORM_ACTION" ]; then
    echo "FAILED (Could not parse login form action from Keycloak HTML)"
    exit 1
fi

# Submit credentials
submit_credentials "$COOKIE_JAR" "$FORM_ACTION" "$TEST_USER" "$OWNER_PASSWORD" >/dev/null

CALLBACK_URL="$(grep -i "^location:" "$HEADER_LOG" | tr -d '\r\n' | awk '{print $2}')"
if [[ "$CALLBACK_URL" != *"/login/oauth2/code/dokene"* ]]; then
    echo "FAILED (Authentication failed; redirect was not callback: $CALLBACK_URL)"
    exit 1
fi
echo "OK (Keycloak issued authorization code)"

# ------------------------------------------------------------------------------
# 4. Exchange Authorization Code and Establish BFF Session
# ------------------------------------------------------------------------------
echo -n "[6/$TOTAL_STEPS] Completing BFF code exchange and session establishment... "
curl -s -c "$COOKIE_JAR" -b "$COOKIE_JAR" -D "$HEADER_LOG" -o "$BODY_LOG" "$CALLBACK_URL"

POST_LOGIN_REDIRECT="$(grep -i "^location:" "$HEADER_LOG" | tr -d '\r\n' | awk '{print $2}')"
# Assert tokens and secrets are NOT present in redirect URL or response headers
for forbidden_val in access_token id_token refresh_token client_secret "$TEST_CLIENT_SECRET"; do
    if [[ "$POST_LOGIN_REDIRECT" == *"$forbidden_val"* ]]; then
        echo "FAILED (Forbidden value '$forbidden_val' exposed in post-login redirect URL: $POST_LOGIN_REDIRECT)"
        exit 1
    fi
    if grep -q "$forbidden_val" "$HEADER_LOG"; then
        echo "FAILED (Forbidden value '$forbidden_val' exposed in callback response headers!)"
        exit 1
    fi
done

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
echo -n "[7/$TOTAL_STEPS] Verifying application session contract and token isolation... "
curl -s -b "$COOKIE_JAR" -D "$HEADER_LOG" -o "$BODY_LOG" "$BFF_URL/api/session"

SESSION_JSON="$(cat "$BODY_LOG")"
IS_AUTHENTICATED="$(echo "$SESSION_JSON" | jq -r '.authenticated // false')"
IDENTITY_ID="$(echo "$SESSION_JSON" | jq -r '.identityId // empty')"
CSRF_TOKEN="$(echo "$SESSION_JSON" | jq -r '.csrfToken // empty')"

if [ "$IS_AUTHENTICATED" != "true" ] || [ -z "$IDENTITY_ID" ] || [ -z "$CSRF_TOKEN" ]; then
    echo "FAILED (Invalid session contract: $SESSION_JSON)"
    exit 1
fi

# Assert OAuth tokens and secrets are strictly absent from session body and headers
for forbidden_val in access_token id_token refresh_token client_secret "$TEST_CLIENT_SECRET"; do
    if echo "$SESSION_JSON" | grep -q "$forbidden_val"; then
        echo "FAILED (Forbidden value '$forbidden_val' exposed in session response body!)"
        exit 1
    fi
    if grep -q "$forbidden_val" "$HEADER_LOG"; then
        echo "FAILED (Forbidden value '$forbidden_val' exposed in session response headers!)"
        exit 1
    fi
done
echo "OK (Safe session contract verified; tokens strictly isolated)"

# ------------------------------------------------------------------------------
# 6. Verify CSRF Protection on State-Changing Endpoint
# ------------------------------------------------------------------------------
echo -n "[8/$TOTAL_STEPS] Verifying CSRF enforcement on state-changing requests... "
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
echo -n "[9/$TOTAL_STEPS] Verifying session logout, invalidation, and replay prevention... "
# Valid logout -> 204 No Content
VALID_CSRF_HEADER="X-CSRF-TOKEN: $CSRF_TOKEN"
LOGOUT_STATUS="$(curl -s -b "$COOKIE_JAR" -H "$VALID_CSRF_HEADER" -X POST -D "$HEADER_LOG" -o /dev/null -w "%{http_code}" "$BFF_URL/logout")"
if [ "$LOGOUT_STATUS" != "204" ]; then
    echo "FAILED (Logout returned HTTP $LOGOUT_STATUS, expected 204)"
    exit 1
fi

# Assert browser cookie deletion was instructed via Set-Cookie Max-Age=0 or expired Expires
LOGOUT_COOKIE_HEADER="$(grep -i "^set-cookie:" "$HEADER_LOG" | grep -i "JSESSIONID" || true)"
if [[ -z "$LOGOUT_COOKIE_HEADER" ]]; then
    echo "FAILED (Logout response did not set Set-Cookie header for JSESSIONID deletion)"
    exit 1
fi
if [[ "$LOGOUT_COOKIE_HEADER" != *"Max-Age=0"* && "$LOGOUT_COOKIE_HEADER" != *"Expires="* ]]; then
    echo "FAILED (Logout Set-Cookie header missing Max-Age=0 / expired date: $LOGOUT_COOKIE_HEADER)"
    exit 1
fi

# Post-logout request with logged-out session -> 401 Unauthorized
POST_LOGOUT_STATUS="$(curl -s -b "JSESSIONID=$POST_AUTH_SESSION_ID" -o /dev/null -w "%{http_code}" "$BFF_URL/api/session")"
if [ "$POST_LOGOUT_STATUS" != "401" ]; then
    echo "FAILED (Logged-out session was not invalidated! HTTP $POST_LOGOUT_STATUS)"
    exit 1
fi
echo "OK (Session terminated and subsequent calls fail closed with 401)"

# ------------------------------------------------------------------------------
# 8. One password per test user (issue #314)
# ------------------------------------------------------------------------------
echo -n "[10/$TOTAL_STEPS] Verifying testuser is refused with the VIEWER password... "
FORM_ACTION="$(start_login "$LOGIN_JAR")"
[ -n "$FORM_ACTION" ] || { echo "FAILED (Could not parse login form action from Keycloak HTML)"; exit 1; }
WRONG_USER_STATUS="$(submit_credentials "$LOGIN_JAR" "$FORM_ACTION" testuser "$VIEWER_PASSWORD")"
if [ "$WRONG_USER_STATUS" != "200" ] || ! grep -q 'action="' "$BODY_LOG" \
        || [[ "$(location_header)" == *"/login/oauth2/code/dokene"* ]]; then
    echo "FAILED (HTTP $WRONG_USER_STATUS; expected the login form again and no callback)"
    exit 1
fi
echo "OK (login form shown again, no authorization code)"

echo -n "[11/$TOTAL_STEPS] Verifying testoperator and testviewer log in with their own passwords... "
for login in "testoperator:$OPERATOR_PASSWORD" "testviewer:$VIEWER_PASSWORD"; do
    FORM_ACTION="$(start_login "$LOGIN_JAR")"
    [ -n "$FORM_ACTION" ] || { echo "FAILED (Could not parse login form action for ${login%%:*})"; exit 1; }
    submit_credentials "$LOGIN_JAR" "$FORM_ACTION" "${login%%:*}" "${login#*:}" >/dev/null
    if [[ "$(location_header)" != *"/login/oauth2/code/dokene"* ]]; then
        echo "FAILED (${login%%:*} did not reach the BFF callback)"
        exit 1
    fi
done
echo "OK (both reached the BFF callback)"

echo "======================================================================"
echo "SUCCESS: All $TOTAL_STEPS End-to-End BFF Security Assertions PASSED!"
echo "======================================================================"
