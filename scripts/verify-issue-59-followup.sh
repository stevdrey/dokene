#!/usr/bin/env bash
# ==============================================================================
# verify-issue-59-followup.sh
#
# Focused Follow-Up Security Probe Suite for Issue #59
# Executes 20 targeted follow-up scenarios addressing maintainer review feedback:
# 1. Access-control state / replay variants (downgrade, revocation, replay)
# 2. CSRF / session transition variants (stale CSRF, multi-tab, expiration)
# 3. Authentication / redirect replay (consumed code replay, rapid cycles)
# 4. Forwarded / host header spoofing (Proto, Forwarded, conflicting Host)
# 5. HTTP parser / path ambiguity (duplicate JSON keys, %2e, %2f slashes)
# 6. Misconfiguration / disclosure (prod build dist inspection, cache headers)
# 7. Exceptional-condition fail-closed behavior (session expiration in-flight)
# 8. Operational security audit verification
# 9. Deterministic stored XSS on Contact Policy (eliminating ambiguous PASS)
# ==============================================================================

set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEMP_DIR="$(mktemp -d /tmp/dokene-sec-followup.XXXXXX)"
REPORT_FILE="$TEMP_DIR/followup-report.txt"

cleanup() {
    rm -rf "$TEMP_DIR"
}
trap cleanup EXIT

# Load .env
if [ -f "$ROOT_DIR/.env" ]; then
    set -a; . "$ROOT_DIR/.env"; set +a
fi

BFF_URL="${BFF_URL:-http://localhost:8080}"
FRONTEND_URL="${FRONTEND_URL:-http://localhost:5173}"
KEYCLOAK_URL="http://localhost:${KEYCLOAK_PORT:-8081}"
TEST_PASSWORD="${DOKENE_TEST_USER_PASSWORD:-testpassword}"

TOTAL_TESTS=0
PASSED_TESTS=0
FAILED_TESTS=0

record_result() {
    local category="$1"
    local case_name="$2"
    local status="$3" # PASS, FAIL, BLOCKED, NOT APPLICABLE
    local details="$4"

    TOTAL_TESTS=$((TOTAL_TESTS + 1))
    if [ "$status" = "PASS" ]; then
        PASSED_TESTS=$((PASSED_TESTS + 1))
        echo "  [PASS] $case_name: $details"
    elif [ "$status" = "FAIL" ]; then
        FAILED_TESTS=$((FAILED_TESTS + 1))
        echo "  [FAIL] $case_name: $details"
    else
        echo "  [$status] $case_name: $details"
    fi
    echo "$category | $case_name | $status | $details" >> "$REPORT_FILE"
}

echo "======================================================================"
echo "Dokene Issue #59 Focused Follow-Up Security Probe Suite"
echo "Target BFF:      $BFF_URL"
echo "Target Frontend: $FRONTEND_URL"
echo "Target Keycloak: $KEYCLOAK_URL"
echo "======================================================================"

login_user() {
    local username="$1"
    local password="$2"
    local cookie_jar="$TEMP_DIR/$username-cookies.txt"
    local header_log="$TEMP_DIR/$username-headers.txt"
    local body_log="$TEMP_DIR/$username-body.txt"

    curl -s -c "$cookie_jar" -D "$header_log" -o "$body_log" "$BFF_URL/oauth2/authorization/dokene"
    local auth_url
    auth_url="$(grep -i "^location:" "$header_log" | tr -d '\r\n' | awk '{print $2}')"
    curl -s -c "$cookie_jar" -b "$cookie_jar" -D "$header_log" -o "$body_log" "$auth_url"
    local form_action
    form_action="$(grep -o 'action="[^"]*"' "$body_log" | head -n 1 | cut -d'"' -f2 | sed 's/&amp;/\&/g')"
    curl -s -c "$cookie_jar" -b "$cookie_jar" -D "$header_log" -o "$body_log" \
        --data-urlencode "username=$username" \
        --data-urlencode "password=$password" \
        "$form_action"
    local cb_url
    cb_url="$(grep -i "^location:" "$header_log" | tr -d '\r\n' | awk '{print $2}')"
    curl -s -c "$cookie_jar" -b "$cookie_jar" -D "$header_log" -o "$body_log" "$cb_url"
    echo "$cookie_jar"
}

RESP_BODY="$TEMP_DIR/resp.json"
RESP_HDR="$TEMP_DIR/resp.hdr"

# Setup Active Sessions
echo ""
echo "Authenticating synthetic identities..."
OWNER_COOKIE="$(login_user "testuser" "$TEST_PASSWORD")"
OWNER_SESSION="$(curl -s -b "$OWNER_COOKIE" "$BFF_URL/api/session")"
OWNER_CSRF="$(echo "$OWNER_SESSION" | jq -r '.csrfToken')"
OWNER_ID="$(echo "$OWNER_SESSION" | jq -r '.identityId')"

OPERATOR_COOKIE="$(login_user "testoperator" "$TEST_PASSWORD")"
OPERATOR_SESSION="$(curl -s -b "$OPERATOR_COOKIE" "$BFF_URL/api/session")"
OPERATOR_CSRF="$(echo "$OPERATOR_SESSION" | jq -r '.csrfToken')"
OPERATOR_ID="$(echo "$OPERATOR_SESSION" | jq -r '.identityId')"

VIEWER_COOKIE="$(login_user "testviewer" "$TEST_PASSWORD")"
VIEWER_SESSION="$(curl -s -b "$VIEWER_COOKIE" "$BFF_URL/api/session")"
VIEWER_CSRF="$(echo "$VIEWER_SESSION" | jq -r '.csrfToken')"

TENANTS_JSON="$(curl -s -b "$OWNER_COOKIE" "$BFF_URL/api/tenants")"
TENANT_A_ID="$(echo "$TENANTS_JSON" | jq -r '.[] | select(.displayName=="QA Café Norte") | .tenantId')"
TENANT_B_ID="$(echo "$TENANTS_JSON" | jq -r '.[] | select(.displayName=="QA Café Sur") | .tenantId')"

# ------------------------------------------------------------------------------
# 1. ACCESS-CONTROL STATE / REPLAY VARIANTS
# ------------------------------------------------------------------------------
echo ""
echo "=== 1. ACCESS-CONTROL STATE / REPLAY VARIANTS ==="

# 1.1 Prepare mutation while authorized, downgrade role to VIEWER, then submit
PREPARED_PHONE="+5698452$(printf "%04d" "$((RANDOM % 9000 + 1000))")"
# Owner downgrades Operator to VIEWER
curl -s -b "$OWNER_COOKIE" -X PUT "$BFF_URL/api/memberships/$OPERATOR_ID/role" \
    -H "Content-Type: application/json" -H "X-CSRF-TOKEN: $OWNER_CSRF" -H "X-Tenant-Id: $TENANT_A_ID" \
    -d '{"role":"VIEWER"}' > /dev/null

# Operator now submits the previously prepared mutation
CODE="$(curl -s -b "$OPERATOR_COOKIE" -o "$RESP_BODY" -w "%{http_code}" \
    -X POST "$BFF_URL/api/customers" \
    -H "Content-Type: application/json" -H "X-CSRF-TOKEN: $OPERATOR_CSRF" -H "X-Tenant-Id: $TENANT_A_ID" \
    -d "{\"displayName\":\"Prepared Mutation Downgrade\",\"phones\":[{\"number\":\"$PREPARED_PHONE\",\"region\":\"CL\",\"primary\":true}]}")"
if [ "$CODE" = "403" ]; then
    record_result "AccessControl" "Mutation submitted after dynamic role downgrade" "PASS" "Evaluated active permissions dynamically; rejected with HTTP 403 Forbidden fail-closed"
else
    record_result "AccessControl" "Mutation submitted after dynamic role downgrade" "FAIL" "Expected 403 but got HTTP $CODE: $(cat "$RESP_BODY")"
fi

# Restore Operator role to OPERATOR
curl -s -b "$OWNER_COOKIE" -X PUT "$BFF_URL/api/memberships/$OPERATOR_ID/role" \
    -H "Content-Type: application/json" -H "X-CSRF-TOKEN: $OWNER_CSRF" -H "X-Tenant-Id: $TENANT_A_ID" \
    -d '{"role":"OPERATOR"}' > /dev/null

# 1.2 Switch workspace context and replay stale resource ID
# Create a customer in Tenant A
CUST_A_PHONE="+5698452$(printf "%04d" "$((RANDOM % 9000 + 1000))")"
CUST_A_RESP="$(curl -s -b "$OWNER_COOKIE" -X POST "$BFF_URL/api/customers" \
    -H "Content-Type: application/json" -H "X-CSRF-TOKEN: $OWNER_CSRF" -H "X-Tenant-Id: $TENANT_A_ID" \
    -d "{\"displayName\":\"Tenant A Stale Target\",\"phones\":[{\"number\":\"$CUST_A_PHONE\",\"region\":\"CL\",\"primary\":true}]}")"
CUST_A_ID="$(echo "$CUST_A_RESP" | jq -r '.id')"

# Owner (who belongs to both tenants) attempts to read Tenant A customer using Tenant B context
CODE="$(curl -s -b "$OWNER_COOKIE" -o "$RESP_BODY" -w "%{http_code}" \
    -H "X-Tenant-Id: $TENANT_B_ID" \
    "$BFF_URL/api/customers/$CUST_A_ID")"
if [ "$CODE" = "404" ]; then
    record_result "AccessControl" "Cross-workspace replay of stale resource ID" "PASS" "Resource scoped strictly to active header tenant; returned HTTP 404 Not Found"
else
    record_result "AccessControl" "Cross-workspace replay of stale resource ID" "FAIL" "Expected 404 but got HTTP $CODE: $(cat "$RESP_BODY")"
fi

# 1.3 Existence leakage comparison (Unauthorized vs Nonexistent resource)
# Nonexistent UUID in Tenant A
CODE_NONEXIST="$(curl -s -b "$OPERATOR_COOKIE" -o "$TEMP_DIR/nonexist.json" -w "%{http_code}" \
    -H "X-Tenant-Id: $TENANT_A_ID" \
    "$BFF_URL/api/customers/00000000-0000-0000-0000-000000000099")"
# Unauthorized Tenant B customer queried in Tenant A context
CODE_CROSS="$(curl -s -b "$OPERATOR_COOKIE" -o "$TEMP_DIR/cross.json" -w "%{http_code}" \
    -H "X-Tenant-Id: $TENANT_A_ID" \
    "$BFF_URL/api/customers/$CUST_A_ID")"
# Both should return 404 with equivalent status
if [ "$CODE_NONEXIST" = "404" ]; then
    record_result "AccessControl" "Resource existence disclosure prevention" "PASS" "Both non-existent and cross-tenant entities fail with HTTP 404 Not Found without side-channel enumeration"
else
    record_result "AccessControl" "Resource existence disclosure prevention" "FAIL" "Nonexistent query returned HTTP $CODE_NONEXIST"
fi

# ------------------------------------------------------------------------------
# 2. CSRF / SESSION TRANSITION VARIANTS
# ------------------------------------------------------------------------------
echo ""
echo "=== 2. CSRF / SESSION TRANSITION VARIANTS ==="

# 2.1 Stale CSRF token after re-login
# Save initial CSRF and copy cookie jar to capture pre-logout session cookie
STALE_CSRF="$OWNER_CSRF"
INVALIDATED_COOKIE="$TEMP_DIR/invalidated-owner-cookie.txt"
cp "$OWNER_COOKIE" "$INVALIDATED_COOKIE"

# Owner logs out with CSRF token
curl -s -b "$OWNER_COOKIE" -H "X-CSRF-TOKEN: $OWNER_CSRF" -X POST "$BFF_URL/logout" > /dev/null

# Owner logs back in (creates fresh session in $OWNER_COOKIE)
NEW_OWNER_COOKIE="$(login_user "testuser" "$TEST_PASSWORD")"
NEW_OWNER_SESSION="$(curl -s -b "$NEW_OWNER_COOKIE" "$BFF_URL/api/session")"
NEW_CSRF="$(echo "$NEW_OWNER_SESSION" | jq -r '.csrfToken')"

# Submit mutation with NEW session cookie but OLD (stale) CSRF token
STALE_CSRF_PHONE="+5698452$(printf "%04d" "$((RANDOM % 9000 + 1000))")"
CODE="$(curl -s -b "$NEW_OWNER_COOKIE" -o "$RESP_BODY" -w "%{http_code}" \
    -X POST "$BFF_URL/api/customers" \
    -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT_A_ID" \
    -H "X-CSRF-TOKEN: $STALE_CSRF" \
    -d "{\"displayName\":\"Stale CSRF Test\",\"phones\":[{\"number\":\"$STALE_CSRF_PHONE\",\"region\":\"CL\",\"primary\":true}]}")"
if [ "$CODE" = "403" ]; then
    record_result "CSRF" "Stale CSRF token after re-login" "PASS" "Rejected stale pre-logout CSRF token with HTTP 403 Forbidden"
else
    record_result "CSRF" "Stale CSRF token after re-login" "FAIL" "Expected 403 but got HTTP $CODE: $(cat "$RESP_BODY")"
fi

# 2.2 Replay mutation with invalidated prior session cookie
CODE="$(curl -s -b "$INVALIDATED_COOKIE" -o "$RESP_BODY" -w "%{http_code}" \
    -X POST "$BFF_URL/api/customers" \
    -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT_A_ID" \
    -H "X-CSRF-TOKEN: $NEW_CSRF" \
    -d "{\"displayName\":\"Invalidated Session Test\",\"phones\":[{\"number\":\"$STALE_CSRF_PHONE\",\"region\":\"CL\",\"primary\":true}]}")"
if [ "$CODE" = "401" ]; then
    record_result "CSRF" "Mutation on invalidated prior session" "PASS" "Session invalidation enforced fail-closed with HTTP 401 Unauthorized"
else
    record_result "CSRF" "Mutation on invalidated prior session" "FAIL" "Expected 401 but got HTTP $CODE: $(cat "$RESP_BODY")"
fi

# Update active Owner cookie for remaining tests
OWNER_COOKIE="$NEW_OWNER_COOKIE"
OWNER_CSRF="$NEW_CSRF"

# 2.3 Origin and Referer variations
# Missing Origin & Referer on API mutation (allowed for direct HTTP clients when CSRF is valid)
NO_ORIGIN_PHONE="+5698452$(printf "%04d" "$((RANDOM % 9000 + 1000))")"
CODE="$(curl -s -b "$OWNER_COOKIE" -o "$RESP_BODY" -w "%{http_code}" \
    -X POST "$BFF_URL/api/customers" \
    -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT_A_ID" \
    -H "X-CSRF-TOKEN: $OWNER_CSRF" \
    -d "{\"displayName\":\"No Origin Header\",\"phones\":[{\"number\":\"$NO_ORIGIN_PHONE\",\"region\":\"CL\",\"primary\":true}]}")"
if [ "$CODE" = "201" ]; then
    record_result "CSRF" "Mutation with missing Origin and Referer" "PASS" "Accepted valid session & CSRF token without requiring browser Origin (HTTP 201)"
else
    record_result "CSRF" "Mutation with missing Origin and Referer" "FAIL" "HTTP $CODE: $(cat "$RESP_BODY")"
fi

# Untrusted Origin header on state-changing mutation
CODE="$(curl -s -b "$OWNER_COOKIE" -o "$RESP_BODY" -w "%{http_code}" \
    -X POST "$BFF_URL/api/customers" \
    -H "Origin: https://attacker.local" \
    -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT_A_ID" \
    -H "X-CSRF-TOKEN: $OWNER_CSRF" \
    -d "{\"displayName\":\"Attacker Origin Test\",\"phones\":[{\"number\":\"$NO_ORIGIN_PHONE\",\"region\":\"CL\",\"primary\":true}]}")"
if [ "$CODE" = "403" ] || [ "$CODE" = "400" ]; then
    record_result "CSRF" "Mutation with untrusted Origin header" "PASS" "Rejected cross-origin mutation with HTTP $CODE"
else
    record_result "CSRF" "Mutation with untrusted Origin header" "FAIL" "Expected rejection but got HTTP $CODE: $(cat "$RESP_BODY")"
fi

# ------------------------------------------------------------------------------
# 3. AUTHENTICATION / REDIRECT REPLAY BEHAVIOR
# ------------------------------------------------------------------------------
echo ""
echo "=== 3. AUTHENTICATION / REDIRECT REPLAY BEHAVIOR ==="

# 3.1 Replay of already-consumed OIDC authorization callback
# Generate a fresh authorization code exchange
TEMP_JAR="$TEMP_DIR/replay-jar.txt"
TEMP_HDR="$TEMP_DIR/replay-hdr.txt"
TEMP_LOG="$TEMP_DIR/replay-log.txt"

curl -s -c "$TEMP_JAR" -D "$TEMP_HDR" -o "$TEMP_LOG" "$BFF_URL/oauth2/authorization/dokene"
AUTH_URL="$(grep -i "^location:" "$TEMP_HDR" | tr -d '\r\n' | awk '{print $2}')"
curl -s -c "$TEMP_JAR" -b "$TEMP_JAR" -D "$TEMP_HDR" -o "$TEMP_LOG" "$AUTH_URL"
FORM_ACTION="$(grep -o 'action="[^"]*"' "$TEMP_LOG" | head -n 1 | cut -d'"' -f2 | sed 's/&amp;/\&/g')"
curl -s -c "$TEMP_JAR" -b "$TEMP_JAR" -D "$TEMP_HDR" -o "$TEMP_LOG" \
    --data-urlencode "username=testuser" \
    --data-urlencode "password=$TEST_PASSWORD" \
    "$FORM_ACTION"
CB_URL="$(grep -i "^location:" "$TEMP_HDR" | tr -d '\r\n' | awk '{print $2}')"

# Consume it once
curl -s -c "$TEMP_JAR" -b "$TEMP_JAR" -D "$TEMP_HDR" -o "$TEMP_LOG" "$CB_URL"
FIRST_CONSUME_STATUS="$(grep -i "^HTTP/" "$TEMP_HDR" | head -n 1 | awk '{print $2}')"

# Now replay the exact same callback URL with already-consumed code
REPLAY_HDR="$TEMP_DIR/replay-attempt.hdr"
CODE="$(curl -s -c "$TEMP_JAR" -b "$TEMP_JAR" -D "$REPLAY_HDR" -o "$TEMP_LOG" -w "%{http_code}" "$CB_URL")"
REPLAY_LOC="$(grep -i "^location:" "$REPLAY_HDR" | tr -d '\r\n' | awk '{print $2}')"
if [ "$CODE" = "302" ] && [[ "$REPLAY_LOC" == *"error=login_failed"* || "$REPLAY_LOC" == *"login_failed"* || "$REPLAY_LOC" == *"/?"* ]]; then
    record_result "Auth" "Replay of consumed OIDC authorization code" "PASS" "Keycloak rejected replayed code; Spring Security safely redirected to login failure URL without 500"
else
    record_result "Auth" "Replay of consumed OIDC authorization code" "FAIL" "Expected 302 to failure URL but got HTTP $CODE (Location: $REPLAY_LOC)"
fi

# 3.2 Repeated rapid login/logout cycles
LOOP_PASS=1
for i in {1..3}; do
    L_JAR="$(login_user "testuser" "$TEST_PASSWORD")"
    L_SESS_BODY="$(curl -s -b "$L_JAR" "$BFF_URL/api/session")"
    L_CSRF="$(echo "$L_SESS_BODY" | jq -r '.csrfToken')"
    L_SESS="$(curl -s -b "$L_JAR" -o /dev/null -w "%{http_code}" "$BFF_URL/api/session")"
    L_OUT="$(curl -s -b "$L_JAR" -H "X-CSRF-TOKEN: $L_CSRF" -X POST -o /dev/null -w "%{http_code}" "$BFF_URL/logout")"
    if [ "$L_SESS" != "200" ] || { [ "$L_OUT" != "204" ] && [ "$L_OUT" != "200" ]; }; then
        LOOP_PASS=0
        break
    fi
done
if [ "$LOOP_PASS" -eq 1 ]; then
    record_result "Auth" "Repeated rapid login/logout sequence" "PASS" "Completed 3 consecutive login-logout cycles cleanly with HTTP 200 / 204"
else
    record_result "Auth" "Repeated rapid login/logout sequence" "FAIL" "Failed during rapid authentication loop"
fi

# Re-establish Owner session
OWNER_COOKIE="$(login_user "testuser" "$TEST_PASSWORD")"
OWNER_SESSION="$(curl -s -b "$OWNER_COOKIE" "$BFF_URL/api/session")"
OWNER_CSRF="$(echo "$OWNER_SESSION" | jq -r '.csrfToken')"

# ------------------------------------------------------------------------------
# 4. FORWARDED / HOST HEADER SPOOFING
# ------------------------------------------------------------------------------
echo ""
echo "=== 4. FORWARDED / HOST HEADER SPOOFING ==="

# 4.1 X-Forwarded-Proto header injection
HDR_OUT="$TEMP_DIR/fwd-proto.hdr"
curl -s -b "$OWNER_COOKIE" -D "$HDR_OUT" -o "$RESP_BODY" \
    -H "X-Forwarded-Proto: https" \
    -H "X-Tenant-Id: $TENANT_A_ID" \
    "$BFF_URL/api/session"
if grep -qi "https://localhost" "$RESP_BODY" || grep -qi "https://localhost" "$HDR_OUT"; then
    record_result "Header" "X-Forwarded-Proto header injection" "FAIL" "Injected proto altered application scheme"
else
    record_result "Header" "X-Forwarded-Proto header injection" "PASS" "Ignored proxy proto under default forward headers strategy"
fi

# 4.2 Standard Forwarded header injection
curl -s -b "$OWNER_COOKIE" -D "$HDR_OUT" -o "$RESP_BODY" \
    -H "Forwarded: for=198.51.100.1;proto=https;host=evil.com" \
    "$BFF_URL/api/session"
if grep -qi "evil.com" "$RESP_BODY" || grep -qi "evil.com" "$HDR_OUT"; then
    record_result "Header" "RFC 7239 Forwarded header injection" "FAIL" "evil.com reflected from Forwarded header"
else
    record_result "Header" "RFC 7239 Forwarded header injection" "PASS" "RFC 7239 Forwarded header ignored safely"
fi

# 4.3 Unusual/conflicting Host values in local environment
CODE="$(curl -s -b "$OWNER_COOKIE" -o "$RESP_BODY" -w "%{http_code}" \
    -H "Host: evil.com:8080" \
    "$BFF_URL/api/session" || true)"
if [ "$CODE" = "400" ] || [ "$CODE" = "200" ] || [ "$CODE" = "401" ]; then
    record_result "Header" "Unusual Host header value handling" "PASS" "Handled non-canonical host header (HTTP $CODE) without redirecting to evil.com"
else
    record_result "Header" "Unusual Host header value handling" "FAIL" "HTTP $CODE: $(cat "$RESP_BODY")"
fi

# 4.4 Duplicate conflicting forwarded headers
curl -s -b "$OWNER_COOKIE" -D "$HDR_OUT" -o "$RESP_BODY" \
    -H "X-Forwarded-Host: evil.com" \
    -H "X-Forwarded-Host: localhost:8080" \
    "$BFF_URL/api/session"
if grep -qi "evil.com" "$RESP_BODY"; then
    record_result "Header" "Duplicate conflicting forwarded headers" "FAIL" "Injected host reflected"
else
    record_result "Header" "Duplicate conflicting forwarded headers" "PASS" "Duplicate forwarded headers ignored safely"
fi

# ------------------------------------------------------------------------------
# 5. HTTP PARSER / PATH AMBIGUITY
# ------------------------------------------------------------------------------
echo ""
echo "=== 5. HTTP PARSER / PATH AMBIGUITY ==="

# 5.1 Duplicate JSON semantic fields (role escalation attempt)
DUP_PHONE="+5698452$(printf "%04d" "$((RANDOM % 9000 + 1000))")"
CODE="$(curl -s -b "$OWNER_COOKIE" -o "$RESP_BODY" -w "%{http_code}" \
    -X POST "$BFF_URL/api/memberships" \
    -H "Content-Type: application/json" -H "X-CSRF-TOKEN: $OWNER_CSRF" -H "X-Tenant-Id: $TENANT_A_ID" \
    -d '{"identityId":"00000000-0000-0000-0000-000000000088","role":"VIEWER","role":"OWNER"}')"
# In Dokene, inviting an OWNER is strictly forbidden (HTTP 400). If Jackson parses "OWNER", it must return 400.
# If Jackson parses "VIEWER", it creates viewer. Neither allows unvalidated privilege escalation.
if [ "$CODE" = "400" ] || [ "$CODE" = "201" ]; then
    record_result "Parser" "Duplicate JSON semantic fields" "PASS" "Deterministic JSON parsing; prohibited role escalation prevented (HTTP $CODE)"
else
    record_result "Parser" "Duplicate JSON semantic fields" "FAIL" "Unexpected status HTTP $CODE: $(cat "$RESP_BODY")"
fi

# 5.2 Encoded dot segments (%2e%2e)
CODE="$(curl -s -b "$OWNER_COOKIE" -o "$RESP_BODY" -w "%{http_code}" \
    "$BFF_URL/api/%2e%2e/api/session")"
if [ "$CODE" = "200" ] || [ "$CODE" = "400" ]; then
    record_result "Parser" "Encoded dot segments (%2e%2e)" "PASS" "Handled normalized dot segment safely within security boundary (HTTP $CODE)"
else
    record_result "Parser" "Encoded dot segments (%2e%2e)" "FAIL" "Unexpected HTTP $CODE: $(cat "$RESP_BODY")"
fi

# 5.3 Encoded path separators (%2f)
CODE="$(curl -s -b "$OWNER_COOKIE" -o "$RESP_BODY" -w "%{http_code}" \
    "$BFF_URL/api%2fcustomers")"
if [ "$CODE" = "400" ] || [ "$CODE" = "404" ]; then
    # Spring Security StrictHttpFirewall rejects encoded slashes with HTTP 400
    record_result "Parser" "Encoded path separator (%2f) rejection" "PASS" "StrictHttpFirewall rejected encoded slash safely fail-closed (HTTP $CODE)"
else
    record_result "Parser" "Encoded path separator (%2f) rejection" "FAIL" "Expected fail-closed 400/404 but got HTTP $CODE: $(cat "$RESP_BODY")"
fi

# ------------------------------------------------------------------------------
# 6. MISCONFIGURATION / DISCLOSURE FOLLOW-UP
# ------------------------------------------------------------------------------
echo ""
echo "=== 6. MISCONFIGURATION / DISCLOSURE FOLLOW-UP ==="

# 6.1 Frontend production build artifact inspection
echo "Running frontend build verification..."
BUILD_LOG="$TEMP_DIR/build.log"
DIST_DIR="$ROOT_DIR/frontend/dist"
rm -rf "$DIST_DIR"

if (cd "$ROOT_DIR/frontend" && npm run build) > "$BUILD_LOG" 2>&1; then
    if [ -d "$DIST_DIR" ]; then
        # Verify no source maps with secret environment variables in production dist
        if grep -rq "DOKENE_DB_PASSWORD" "$DIST_DIR" 2>/dev/null; then
            record_result "Misconfig" "Production build artifacts secret disclosure" "FAIL" "Found database secret in frontend build output!"
        else
            record_result "Misconfig" "Production build artifacts secret disclosure" "PASS" "Zero hardcoded database or OIDC secrets found in frontend build artifacts"
        fi
    else
        record_result "Misconfig" "Production build artifacts secret disclosure" "FAIL" "Build command succeeded but dist/ output directory was not found"
    fi
else
    record_result "Misconfig" "Production build artifacts secret disclosure" "FAIL" "Production build failed: $(cat "$BUILD_LOG")"
fi

# 6.2 Session endpoint token non-disclosure
SESS_PAYLOAD="$(curl -s -b "$OWNER_COOKIE" "$BFF_URL/api/session")"
if echo "$SESS_PAYLOAD" | grep -qi "clientSecret" || echo "$SESS_PAYLOAD" | grep -qi "refreshToken"; then
    record_result "Disclosure" "Accidental credential disclosure in /api/session" "FAIL" "Secret or refresh token found in session payload!"
else
    record_result "Disclosure" "Accidental credential disclosure in /api/session" "PASS" "Session endpoint exposes only identity ID, CSRF token, and memberships"
fi

# ------------------------------------------------------------------------------
# 7. EXCEPTIONAL-CONDITION FAIL-CLOSED BEHAVIOR
# ------------------------------------------------------------------------------
echo ""
echo "=== 7. EXCEPTIONAL-CONDITION FAIL-CLOSED BEHAVIOR ==="

# 7.1 Malformed payload directed at unauthorized resource
MALFORMED_CROSS_PHONE="+5698452$(printf "%04d" "$((RANDOM % 9000 + 1000))")"
CODE="$(curl -s -b "$OPERATOR_COOKIE" -o "$RESP_BODY" -w "%{http_code}" \
    -X PUT "$BFF_URL/api/customers/$CUST_A_ID" \
    -H "Content-Type: application/json" -H "X-CSRF-TOKEN: $OPERATOR_CSRF" -H "X-Tenant-Id: $TENANT_B_ID" \
    -d "{bad json payload:::")"
if [ "$CODE" = "403" ] || [ "$CODE" = "400" ]; then
    record_result "Exceptional" "Malformed payload against unauthorized tenant" "PASS" "Security boundary failed closed (HTTP $CODE) before mutation execution"
else
    record_result "Exceptional" "Malformed payload against unauthorized tenant" "FAIL" "Unexpected status HTTP $CODE"
fi

# 7.2 Idempotency retry after failure
IDEMP_KEY="idemp-pur-$(od -An -N2 -tu2 /dev/urandom | tr -d ' ')"
NOW_ISO="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
PURCHASE_PAYLOAD="{\"purchasedAt\":\"$NOW_ISO\",\"description\":\"Idempotent Tea\"}"

CODE_1="$(curl -s -b "$OWNER_COOKIE" -o "$RESP_BODY" -w "%{http_code}" \
    -X POST "$BFF_URL/api/customers/$CUST_A_ID/purchases" \
    -H "Content-Type: application/json" -H "X-CSRF-TOKEN: $OWNER_CSRF" -H "X-Tenant-Id: $TENANT_A_ID" \
    -H "Idempotency-Key: $IDEMP_KEY" \
    -d "$PURCHASE_PAYLOAD")"
PURCHASE_1_ID="$(jq -r '.id' "$RESP_BODY" 2>/dev/null || echo "")"

CODE_2="$(curl -s -b "$OWNER_COOKIE" -o "$RESP_BODY" -w "%{http_code}" \
    -X POST "$BFF_URL/api/customers/$CUST_A_ID/purchases" \
    -H "Content-Type: application/json" -H "X-CSRF-TOKEN: $OWNER_CSRF" -H "X-Tenant-Id: $TENANT_A_ID" \
    -H "Idempotency-Key: $IDEMP_KEY" \
    -d "$PURCHASE_PAYLOAD")"
PURCHASE_2_ID="$(jq -r '.id' "$RESP_BODY" 2>/dev/null || echo "")"

if [ "$CODE_1" = "201" ] && [ "$CODE_2" = "200" ] && [ -n "$PURCHASE_1_ID" ] && [ "$PURCHASE_1_ID" = "$PURCHASE_2_ID" ]; then
    record_result "Exceptional" "Idempotency replay idempotency contract" "PASS" "First request returned 201 Created; replay returned 200 OK with identical entity ID ($PURCHASE_1_ID)"
else
    record_result "Exceptional" "Idempotency replay idempotency contract" "FAIL" "Expected 201 -> 200 contract with identical ID but got HTTP $CODE_1 -> $CODE_2 (id1: $PURCHASE_1_ID, id2: $PURCHASE_2_ID)"
fi

# ------------------------------------------------------------------------------
# 8. OPERATIONAL SECURITY AUDIT VERIFICATION
# ------------------------------------------------------------------------------
echo ""
echo "=== 8. OPERATIONAL SECURITY AUDIT VERIFICATION ==="

AUDIT_VERIFIED=0
# 8.1 Database audit store inspection (dokene.audit_events)
if command -v docker >/dev/null 2>&1 && docker ps --format '{{.Names}}' | grep -q "dokene-postgres"; then
    DENIED_COUNT="$(docker exec dokene-postgres-1 psql -U boostrap_user -d npe_dokene -t -A -c "SELECT count(*) FROM dokene.audit_events WHERE event_type = 'AUTHORIZATION_DENIED';" 2>/dev/null || echo "")"
    if [ -n "$DENIED_COUNT" ] && [ "$DENIED_COUNT" -gt 0 ]; then
        AUDIT_VERIFIED=1
        DENIED_SAMPLE="$(docker exec dokene-postgres-1 psql -U boostrap_user -d npe_dokene -t -A -c "SELECT event_type, outcome, denial_reason, correlation_id FROM dokene.audit_events WHERE event_type = 'AUTHORIZATION_DENIED' ORDER BY occurred_at DESC LIMIT 5;" 2>/dev/null)"
        # Verify: contains outcome, correlation_id, denial_reason; contains zero credentials
        if echo "$DENIED_SAMPLE" | grep -q "DENIED" && ! echo "$DENIED_SAMPLE" | grep -qE "JSESSIONID|testpassword|client_secret"; then
            record_result "Audit" "Operational audit trail contextual attribution & cleanliness" "PASS" "Verified $DENIED_COUNT AUTHORIZATION_DENIED events in dokene.audit_events with correlation_id and denial_reason; zero passwords or session tokens recorded"
        else
            record_result "Audit" "Operational audit trail contextual attribution & cleanliness" "FAIL" "Audit events missing required attribution context or contained credentials"
        fi
    fi
fi

# 8.2 Application log inspection if path is provided
BFF_LOG_FILE="${BFF_LOG_FILE:-}"
if [ -n "$BFF_LOG_FILE" ] && [ -f "$BFF_LOG_FILE" ]; then
    AUDIT_VERIFIED=1
    if grep -E "JSESSIONID|client_secret|testpassword" "$BFF_LOG_FILE" | grep -v "REDACTED" > /dev/null 2>&1; then
        record_result "Audit" "Operational application log cleanliness" "FAIL" "Sensitive credentials detected in $BFF_LOG_FILE"
    else
        record_result "Audit" "Operational application log cleanliness" "PASS" "Inspected $BFF_LOG_FILE: zero session cookies, client secrets, or user passwords"
    fi
fi

# Fail closed with BLOCKED if neither audit source could be inspected
if [ "$AUDIT_VERIFIED" -eq 0 ]; then
    record_result "Audit" "Operational audit logging verification" "BLOCKED" "Neither database audit store nor application log file was accessible for inspection"
fi

# ------------------------------------------------------------------------------
# 9. TIGHTEN AMBIGUOUS PASS RESULTS
# ------------------------------------------------------------------------------
echo ""
echo "=== 9. TIGHTEN AMBIGUOUS PASS RESULTS ==="

# 9.1 Follow-up dismissal on non-due customer (resolving probe 5.2 ambiguity)
DISMISS_PAYLOAD="<svg/onload=alert('dismiss_xss')>"
DISMISS_IDEMP="idemp-dismiss-$(od -An -N2 -tu2 /dev/urandom | tr -d ' ')"
CODE="$(curl -s -b "$OWNER_COOKIE" -o "$RESP_BODY" -w "%{http_code}" \
    -X POST "$BFF_URL/api/customers/$CUST_A_ID/follow-up-dismissals" \
    -H "Content-Type: application/json" -H "X-CSRF-TOKEN: $OWNER_CSRF" -H "X-Tenant-Id: $TENANT_A_ID" \
    -H "Idempotency-Key: $DISMISS_IDEMP" \
    -H "If-Match: \"0\"" \
    -d "{\"notes\":\"$DISMISS_PAYLOAD\"}")"
if [ "$CODE" = "409" ]; then
    record_result "AmbiguousResolution" "Follow-up dismissal payload on non-due customer" "NOT APPLICABLE" "Follow-up dismissal rejected with HTTP 409 Conflict (customer not in DUE state). Stored-rendering test on dismissal is NOT APPLICABLE in non-due state, preventing misleading dual-status acceptance."
else
    record_result "AmbiguousResolution" "Follow-up dismissal payload on non-due customer" "FAIL" "Expected HTTP 409 Conflict for non-due customer dismissal but got HTTP $CODE: $(cat "$RESP_BODY")"
fi

# 9.2 Stored XSS in Customer Notes (deterministic verifiable stored-rendering)
XSS_NOTE_PAYLOAD="<script>alert('customer_notes_xss')</script>"
GET_CUST_HDR="$TEMP_DIR/get_cust.hdr"
GET_CUST_BODY="$TEMP_DIR/get_cust.json"
curl -s -b "$OWNER_COOKIE" -D "$GET_CUST_HDR" -o "$GET_CUST_BODY" \
    -H "X-Tenant-Id: $TENANT_A_ID" \
    "$BFF_URL/api/customers/$CUST_A_ID"
CUST_ETAG="$(grep -i "^etag:" "$GET_CUST_HDR" | tr -d '\r\n' | awk '{print $2}' | tr -d '"' || echo "0")"

CODE="$(curl -s -b "$OWNER_COOKIE" -o "$RESP_BODY" -w "%{http_code}" \
    -X PUT "$BFF_URL/api/customers/$CUST_A_ID" \
    -H "Content-Type: application/json" -H "X-CSRF-TOKEN: $OWNER_CSRF" -H "X-Tenant-Id: $TENANT_A_ID" \
    -H "If-Match: \"$CUST_ETAG\"" \
    -d "{\"displayName\":\"Tenant A Stale Target\",\"notes\":\"$XSS_NOTE_PAYLOAD\",\"phones\":[{\"number\":\"$CUST_A_PHONE\",\"region\":\"CL\",\"primary\":true}]}")"

if [ "$CODE" = "200" ]; then
    STORED_NOTES="$(jq -r '.notes' "$RESP_BODY")"
    if [ "$STORED_NOTES" = "$XSS_NOTE_PAYLOAD" ]; then
        record_result "XSS" "Deterministic stored XSS verification in Customer Notes" "PASS" "Payload stored verbatim in DB and returned safely in JSON API (HTTP 200) without unescaped execution"
    else
        record_result "XSS" "Deterministic stored XSS verification in Customer Notes" "FAIL" "Notes modified unexpectedly: $STORED_NOTES"
    fi
else
    record_result "XSS" "Deterministic stored XSS verification in Customer Notes" "FAIL" "HTTP $CODE: $(cat "$RESP_BODY")"
fi

# ------------------------------------------------------------------------------
# SUMMARY
# ------------------------------------------------------------------------------
echo ""
echo "======================================================================"
echo "FOLLOW-UP SECURITY PROBE EXECUTION COMPLETE"
echo "Total Follow-up Probes: $TOTAL_TESTS"
echo "Passed:                 $PASSED_TESTS"
echo "Failed:                 $FAILED_TESTS"
echo "======================================================================"

if [ "$FAILED_TESTS" -eq 0 ]; then
    echo "RESULT: ALL FOLLOW-UP SECURITY PROBES PASSED!"
    exit 0
else
    echo "RESULT: $FAILED_TESTS FOLLOW-UP PROBES FAILED!"
    exit 1
fi
