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
#
# And for issue #331:
# 10. The client secret the leak checks search for is the one Keycloak accepts for
#     dokene-bff, and no failure message prints a secret, an authorization code,
#     OAuth state, a CSRF token or a session cookie: failures name what was found
#     and show at most a URL's origin. Tested offline by
#     scripts/tests/verify-keycloak-bff-e2e-test.sh.
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

# docker compose takes a variable from the caller's environment before .env, so the stack runs with an exported client
# secret rather than the one in .env. Keep the caller's value across the .env load below, or the leak checks would
# search for a value the stack does not use.
CALLER_CLIENT_SECRET="${SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_DOKENE_CLIENT_SECRET:-}"

# Read configuration from .env if available
if [ -f "$ROOT_DIR/.env" ]; then
    set -a
    # shellcheck disable=SC1091
    . "$ROOT_DIR/.env"
    set +a
fi

KEYCLOAK_PORT="${KEYCLOAK_PORT:-8081}"
BFF_URL="${BFF_URL:-http://localhost:8080}"
KEYCLOAK_URL="http://localhost:${KEYCLOAK_PORT}"
# shellcheck source=lib/test-passwords.sh
. "$ROOT_DIR/scripts/lib/test-passwords.sh"
# shellcheck source=lib/secret-match.sh
. "$ROOT_DIR/scripts/lib/secret-match.sh"
# Keeps the callback URL with its authorization code, the CSRF header and JSESSIONID values off curl's command line.
# shellcheck source=lib/curl-protected.sh
. "$ROOT_DIR/scripts/lib/curl-protected.sh"
TEST_USER="testuser"
TOTAL_STEPS=12
# The BFF's real client secret, resolved the way docker compose resolves it: the caller's environment, then .env
# (DOKENE_OIDC_CLIENT_SECRET is its name inside the oidc-bridge container). No default: a placeholder would make the
# leak checks below look for a value the stack never uses. Step 3 proves Keycloak accepts it.
TEST_CLIENT_SECRET="${CALLER_CLIENT_SECRET:-${SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_DOKENE_CLIENT_SECRET:-${DOKENE_OIDC_CLIENT_SECRET:-}}}"
unset CALLER_CLIENT_SECRET
: "${TEST_CLIENT_SECRET:?SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_DOKENE_CLIENT_SECRET is not set; run ./scripts/dev-env.sh up, which generates it in .env}"

echo "======================================================================"
echo "Dokene BFF End-to-End Security Verification (Issue #52)"
echo "Target BFF:      $BFF_URL"
echo "Target Keycloak: $KEYCLOAK_URL"
echo "======================================================================"

# Prints nothing and fails when the last response had no Location header. Under errexit a plain assignment from it or
# from start_login would end the script before the caller's FAILED message, so those assignments add `|| true`.
location_header() { grep -i "^location:" "$HEADER_LOG" | tr -d '\r\n' | awk '{print $2}'; }

# submit_credentials JAR FORM_ACTION USERNAME PASSWORD: posts the Keycloak login form and prints the HTTP status. The
# password reaches curl on stdin, never its command line. Headers land in HEADER_LOG, the body in BODY_LOG.
submit_credentials() {
    printf '%s' "$4" | curl -s -c "$1" -b "$1" -D "$HEADER_LOG" -o "$BODY_LOG" -w "%{http_code}" \
        --data-urlencode "username=$3" --data-urlencode "password@-" "$2"
}

# Names of OAuth values that must never reach a browser-visible response. The names themselves are not secret, so a
# failure may print them; the client secret is searched for too, but a failure only ever names it by a fixed label.
FORBIDDEN_NAMES=(access_token id_token refresh_token client_secret)

# leaked_label FILE: prints the label of the first forbidden value that FILE ("-" for stdin) contains, literally, and
# fails when it contains none. The label is a fixed string, never text taken from FILE.
leaked_label() {
    local content name
    content="$(cat -- "$1")"
    for name in "${FORBIDDEN_NAMES[@]}"; do
        if printf '%s' "$content" | contains_literal - "$name"; then echo "$name"; return 0; fi
    done
    if printf '%s' "$content" | contains_literal - "$TEST_CLIENT_SECRET"; then echo "<OIDC client secret>"; return 0; fi
    return 1
}

# present_or_missing VALUE: "present" or "missing", so a failure can say which session field was wrong without printing
# its value (the CSRF token is a credential, the identity id personal data).
present_or_missing() { if [ -n "$1" ]; then echo present; else echo missing; fi; }

# redact_url URL: only the origin of URL (scheme, host and port), so a failure shows where the redirect went without
# printing any part that can carry a token or the client secret (userinfo, path, query, fragment; invariant 35). The
# URL reaches sed through a here-string, not its command line; LC_ALL=C keeps invalid UTF-8 from stopping the match.
redact_url() {
    local origin
    origin="$(LC_ALL=C sed -nE \
        's#^([A-Za-z][A-Za-z0-9+.-]*://)([^/?#@]*@)?([A-Za-z0-9.-]+|\[[0-9A-Fa-f:.]+\])(:[0-9]{1,5})?([/?#].*)?$#\1\3\4#p' \
        <<<"$1")"
    # A second line could hold a second "URL" whose host is a token; such input is not a redirect, so print no origin.
    case "$1" in *$'\n'*) origin="" ;; esac
    case "$origin" in *"$TEST_CLIENT_SECRET"*) origin="" ;; esac
    printf '%s (path, query and fragment withheld)' "${origin:-<unparsable URL>}"
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
    location="$(location_header || true)"
    if [ "$status" != "302" ] || [[ "$location" != *"error=invalid_request"* ]] || grep -q 'action="' "$BODY_LOG"; then
        if [ -n "$location" ]; then location="$(redact_url "$location")"; else location="<none>"; fi
        echo "FAILED ($2 was not refused: HTTP $status, Location $location)"
        exit 1
    fi
}

# ------------------------------------------------------------------------------
# 1. Verify Prerequisites and Connectivity
# ------------------------------------------------------------------------------
# curl is the curl-protected.sh wrapper here, so `command -v curl` would always succeed.
curl_installed || { echo "Error: curl is required." >&2; exit 1; }
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
# 1b. The configured client secret is the one the stack uses (issue #331)
# ------------------------------------------------------------------------------
# A token request with an unusable code: Keycloak authenticates the client before it looks at the code, so the right
# secret gets 400 invalid_grant and a wrong one 401 (unauthorized_client in Keycloak 26, invalid_client in the OAuth
# specification). No code is redeemed and no token is issued. The
# secret reaches curl on stdin; the response body is never printed, and only an error code of plain letters is.
echo -n "[3/$TOTAL_STEPS] Verifying Keycloak accepts the configured OIDC client secret for dokene-bff... "
CLIENT_CHECK_STATUS="$(printf '%s' "$TEST_CLIENT_SECRET" | curl -s -o "$BODY_LOG" -w "%{http_code}" \
    --data-urlencode "grant_type=authorization_code" --data-urlencode "client_id=dokene-bff" \
    --data-urlencode "code=dokene-e2e-unusable-code" --data-urlencode "redirect_uri=$BFF_URL/login/oauth2/code/dokene" \
    --data-urlencode "client_secret@-" "$KEYCLOAK_URL/realms/dokene/protocol/openid-connect/token" || true)"
CLIENT_CHECK_ERROR="$(jq -r '.error // empty' "$BODY_LOG" 2>/dev/null || true)"
[[ "$CLIENT_CHECK_ERROR" =~ ^[a-z_]{1,64}$ ]] || CLIENT_CHECK_ERROR="<none>"
if [ "$CLIENT_CHECK_STATUS" = "401" ] || [ "$CLIENT_CHECK_ERROR" = "invalid_client" ] \
        || [ "$CLIENT_CHECK_ERROR" = "unauthorized_client" ]; then
    echo "FAILED (Keycloak refused the configured <OIDC client secret>: HTTP $CLIENT_CHECK_STATUS, error $CLIENT_CHECK_ERROR)"
    echo "The leak checks would search for a value the stack does not use. Make SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_DOKENE_CLIENT_SECRET in your environment and .env match the running stack."
    exit 1
fi
if [ "$CLIENT_CHECK_STATUS" != "400" ] || [ "$CLIENT_CHECK_ERROR" != "invalid_grant" ]; then
    echo "FAILED (Expected HTTP 400 invalid_grant for an unusable code, got HTTP $CLIENT_CHECK_STATUS, error $CLIENT_CHECK_ERROR)"
    exit 1
fi
echo "OK (client authenticated; the unusable code was refused with invalid_grant)"

# ------------------------------------------------------------------------------
# 2. Initiate Login Flow (Authorization Code Grant)
# ------------------------------------------------------------------------------
echo -n "[4/$TOTAL_STEPS] Initiating Authorization Code flow (/oauth2/authorization/dokene)... "
AUTH_INIT_STATUS="$(curl -s -c "$COOKIE_JAR" -D "$HEADER_LOG" -o "$BODY_LOG" -w "%{http_code}" "$BFF_URL/oauth2/authorization/dokene")"

if [ "$AUTH_INIT_STATUS" != "302" ]; then
    echo "FAILED (Expected HTTP 302, got $AUTH_INIT_STATUS)"
    exit 1
fi

KEYCLOAK_AUTH_URL="$(grep -i "^location:" "$HEADER_LOG" | tr -d '\r\n' | awk '{print $2}')"
if [[ "$KEYCLOAK_AUTH_URL" != *"/realms/dokene/protocol/openid-connect/auth"* ]]; then
    echo "FAILED (Redirect is not the dokene realm authorization endpoint: $(redact_url "$KEYCLOAK_AUTH_URL"))"
    exit 1
fi

# Assert PKCE RFC 7636 S256 parameters generated by Spring Boot BFF
if [[ "$KEYCLOAK_AUTH_URL" != *"code_challenge="* || "$KEYCLOAK_AUTH_URL" != *"code_challenge_method=S256"* ]]; then
    echo "FAILED (PKCE RFC 7636 S256 parameters missing from the redirect to $(redact_url "$KEYCLOAK_AUTH_URL"))"
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
echo -n "[5/$TOTAL_STEPS] Verifying Keycloak refuses authorization requests without S256 PKCE... "
NO_PKCE_URL="$(without_pkce "$KEYCLOAK_AUTH_URL")"
if [[ "$NO_PKCE_URL" == *"code_challenge"* ]]; then
    echo "FAILED (could not strip the PKCE parameters from the redirect to $(redact_url "$KEYCLOAK_AUTH_URL"))"
    exit 1
fi
expect_pkce_refused "$NO_PKCE_URL" "a request with no code_challenge"
expect_pkce_refused "$NO_PKCE_URL&code_challenge_method=plain&code_challenge=$(printf 'p%.0s' {1..43})" \
    "a request with code_challenge_method=plain"
echo "OK (no challenge and plain both get invalid_request, no login form)"

# ------------------------------------------------------------------------------
# 3. Authenticate with Keycloak and Obtain Authorization Code
# ------------------------------------------------------------------------------
echo -n "[6/$TOTAL_STEPS] Executing browser credentials submit against Keycloak... "
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
    echo "FAILED (Authentication failed; the redirect to $(redact_url "$CALLBACK_URL") is not the BFF callback)"
    exit 1
fi
echo "OK (Keycloak issued authorization code)"

# ------------------------------------------------------------------------------
# 4. Exchange Authorization Code and Establish BFF Session
# ------------------------------------------------------------------------------
echo -n "[7/$TOTAL_STEPS] Completing BFF code exchange and session establishment... "
curl -s -c "$COOKIE_JAR" -b "$COOKIE_JAR" -D "$HEADER_LOG" -o "$BODY_LOG" "$CALLBACK_URL"

POST_LOGIN_REDIRECT="$(grep -i "^location:" "$HEADER_LOG" | tr -d '\r\n' | awk '{print $2}')"
# Assert tokens and secrets are NOT present in redirect URL or response headers
if LEAKED="$(printf '%s' "$POST_LOGIN_REDIRECT" | leaked_label -)"; then
    echo "FAILED (Forbidden value '$LEAKED' exposed in post-login redirect URL to $(redact_url "$POST_LOGIN_REDIRECT"))"
    exit 1
fi
if LEAKED="$(leaked_label "$HEADER_LOG")"; then
    echo "FAILED (Forbidden value '$LEAKED' exposed in callback response headers!)"
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
echo -n "[8/$TOTAL_STEPS] Verifying application session contract and token isolation... "
curl -s -b "$COOKIE_JAR" -D "$HEADER_LOG" -o "$BODY_LOG" "$BFF_URL/api/session"

# The body must be exactly one JSON object. jq prints fields it parsed before a syntax error, so a valid object followed
# by trailing garbage would otherwise pass; this check also keeps a parse error from ending the script without a FAILED line.
if ! jq -s -e 'length == 1 and (.[0] | type) == "object"' "$BODY_LOG" >/dev/null 2>&1; then
    echo "FAILED (Invalid session contract: response is not a single JSON object; body withheld)"
    exit 1
fi
IS_AUTHENTICATED="$(jq -r '.authenticated // false' "$BODY_LOG" 2>/dev/null || true)"
IDENTITY_ID="$(jq -r '.identityId // empty' "$BODY_LOG" 2>/dev/null || true)"
CSRF_TOKEN="$(jq -r '.csrfToken // empty' "$BODY_LOG" 2>/dev/null || true)"

if [ "$IS_AUTHENTICATED" != "true" ] || [ -z "$IDENTITY_ID" ] || [ -z "$CSRF_TOKEN" ]; then
    # Field states only: the body holds the CSRF token and the identity id.
    [ "$IS_AUTHENTICATED" = "true" ] && AUTHENTICATED_STATE=true || AUTHENTICATED_STATE="not true"
    echo "FAILED (Invalid session contract: authenticated $AUTHENTICATED_STATE, identityId $(present_or_missing "$IDENTITY_ID"), csrfToken $(present_or_missing "$CSRF_TOKEN"); body withheld)"
    exit 1
fi

# Assert OAuth tokens and secrets are strictly absent from session body and headers
if LEAKED="$(leaked_label "$BODY_LOG")"; then
    echo "FAILED (Forbidden value '$LEAKED' exposed in session response body!)"
    exit 1
fi
if LEAKED="$(leaked_label "$HEADER_LOG")"; then
    echo "FAILED (Forbidden value '$LEAKED' exposed in session response headers!)"
    exit 1
fi
echo "OK (Safe session contract verified; tokens strictly isolated)"

# ------------------------------------------------------------------------------
# 6. Verify CSRF Protection on State-Changing Endpoint
# ------------------------------------------------------------------------------
echo -n "[9/$TOTAL_STEPS] Verifying CSRF enforcement on state-changing requests... "
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
echo -n "[10/$TOTAL_STEPS] Verifying session logout, invalidation, and replay prevention... "
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
    echo "FAILED (Logout Set-Cookie header for JSESSIONID has neither Max-Age=0 nor an Expires date; header withheld)"
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
echo -n "[11/$TOTAL_STEPS] Verifying testuser is refused with the VIEWER password... "
FORM_ACTION="$(start_login "$LOGIN_JAR" || true)"
[ -n "$FORM_ACTION" ] || { echo "FAILED (Could not parse login form action from Keycloak HTML)"; exit 1; }
WRONG_USER_STATUS="$(submit_credentials "$LOGIN_JAR" "$FORM_ACTION" testuser "$VIEWER_PASSWORD")"
if [ "$WRONG_USER_STATUS" != "200" ] || ! grep -q 'action="' "$BODY_LOG" \
        || [[ "$(location_header)" == *"/login/oauth2/code/dokene"* ]]; then
    echo "FAILED (HTTP $WRONG_USER_STATUS; expected the login form again and no callback)"
    exit 1
fi
echo "OK (login form shown again, no authorization code)"

echo -n "[12/$TOTAL_STEPS] Verifying testoperator and testviewer log in with their own passwords... "
for login in "testoperator:$OPERATOR_PASSWORD" "testviewer:$VIEWER_PASSWORD"; do
    FORM_ACTION="$(start_login "$LOGIN_JAR" || true)"
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
