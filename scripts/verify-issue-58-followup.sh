#!/usr/bin/env bash
# ==============================================================================
# verify-issue-58-followup.sh
#
# Comprehensive Automated Verification for Issue #58 Follow-up Requirements:
# 1. Multi-user / Membership RBAC & Negative Scenarios
# 2. Purchase History Boundaries & Stale States (including Idempotency)
# 3. Consent & Do-Not-Contact Negative Paths & State Combinations
# 4. Follow-Up Queue & Workbench Negative & Stale Actions
# 5. Compound-Invalid Scenario (API level)
# 6. Protocol & Query Gaps (Content-Type, parameter pollution, extreme searches)
# ==============================================================================

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEMP_DIR="$(mktemp -d /tmp/dokene-followup-qa.XXXXXX)"

cleanup() {
    rm -rf "$TEMP_DIR"
}
trap cleanup EXIT

if [ -f "$ROOT_DIR/.env" ]; then
    set -a; . "$ROOT_DIR/.env"; set +a
fi

KEYCLOAK_PORT="${KEYCLOAK_PORT:-8081}"
BFF_URL="${BFF_URL:-http://localhost:8080}"
KEYCLOAK_URL="http://localhost:${KEYCLOAK_PORT}"
TEST_PASSWORD="${DOKENE_TEST_USER_PASSWORD:-testpassword}"

TOTAL_TESTS=0
PASSED_TESTS=0
FAILED_TESTS=0

RESULTS_FILE="$TEMP_DIR/results.tsv"
echo -e "Category\tScenario\tExpected\tObserved\tStatus\tDetails" > "$RESULTS_FILE"

record_result() {
    local cat="$1"
    local name="$2"
    local exp="$3"
    local obs="$4"
    local status="$5"
    local details="$6"

    TOTAL_TESTS=$((TOTAL_TESTS + 1))
    if [ "$status" = "PASS" ]; then
        PASSED_TESTS=$((PASSED_TESTS + 1))
        echo -e "[\033[32mPASS\033[0m] $cat: $name | HTTP $obs | $details"
    else
        FAILED_TESTS=$((FAILED_TESTS + 1))
        echo -e "[\033[31mFAIL\033[0m] $cat: $name | Expected $exp, got $obs | $details"
    fi
    echo -e "$cat\t$name\t$exp\t$obs\t$status\t$details" >> "$RESULTS_FILE"
}

check_leaks() {
    local body="$1"
    local leaks=("org.springframework" "io.github.stevdrey" "SQLException" "org.postgresql" "HibernateException" "stackTrace")
    for leak in "${leaks[@]}"; do
        if grep -q "$leak" <<< "$body"; then
            return 1
        fi
    done
    return 0
}

call_api() {
    local method="$1"
    local jar="$2"
    local path="$3"
    shift 3
    local h_file="$TEMP_DIR/h-$TOTAL_TESTS.txt"
    local b_file="$TEMP_DIR/b-$TOTAL_TESTS.txt"
    local code
    code="$(curl -s -X "$method" -b "$jar" -D "$h_file" -o "$b_file" -w "%{http_code}" "$BFF_URL$path" "$@")"
    LAST_CODE="$code"
    LAST_HEADERS="$h_file"
    LAST_BODY="$b_file"
}

login_user() {
    local username="$1"
    local password="$2"
    local cookie_jar="$TEMP_DIR/$username-cookies.txt"
    local header_log="$TEMP_DIR/$username-headers.txt"
    local body_log="$TEMP_DIR/$username-body.txt"

    local auth_status
    auth_status="$(curl -s -c "$cookie_jar" -D "$header_log" -o "$body_log" -w "%{http_code}" "$BFF_URL/oauth2/authorization/dokene")"
    if [ "$auth_status" != "302" ]; then return 1; fi

    local keycloak_auth_url
    keycloak_auth_url="$(grep -i "^location:" "$header_log" | tr -d '\r\n' | awk '{print $2}')"

    curl -s -c "$cookie_jar" -b "$cookie_jar" -D "$header_log" -o "$body_log" "$keycloak_auth_url"
    local form_action
    form_action="$(grep -o 'action="[^"]*"' "$body_log" | head -n 1 | cut -d'"' -f2 | sed 's/&amp;/\&/g')"
    if [ -z "$form_action" ]; then return 1; fi

    curl -s -c "$cookie_jar" -b "$cookie_jar" -D "$header_log" -o "$body_log" \
        --data-urlencode "username=$username" \
        --data-urlencode "password=$password" \
        "$form_action"

    local callback_url
    callback_url="$(grep -i "^location:" "$header_log" | tr -d '\r\n' | awk '{print $2}')"
    if [[ "$callback_url" != *"/login/oauth2/code/dokene"* ]]; then return 1; fi

    curl -s -c "$cookie_jar" -b "$cookie_jar" -D "$header_log" -o "$body_log" "$callback_url"
    echo "$cookie_jar"
}

echo "======================================================================"
echo "Dokene Manual QA Follow-Up Runner (Issue #58)"
echo "Commit SHA: $(git rev-parse HEAD)"
echo "Target BFF: $BFF_URL"
echo "======================================================================"

OWNER_JAR="$(login_user "testuser" "$TEST_PASSWORD")"
OWNER_SESSION="$(curl -s -b "$OWNER_JAR" "$BFF_URL/api/session")"
OWNER_CSRF="$(echo "$OWNER_SESSION" | jq -r '.csrfToken')"
OWNER_ID="$(echo "$OWNER_SESSION" | jq -r '.identityId')"

OP_JAR="$(login_user "testoperator" "$TEST_PASSWORD")"
OP_SESSION="$(curl -s -b "$OP_JAR" "$BFF_URL/api/session")"
OP_CSRF="$(echo "$OP_SESSION" | jq -r '.csrfToken')"
OP_ID="$(echo "$OP_SESSION" | jq -r '.identityId')"

VI_JAR="$(login_user "testviewer" "$TEST_PASSWORD")"
VI_SESSION="$(curl -s -b "$VI_JAR" "$BFF_URL/api/session")"
VI_CSRF="$(echo "$VI_SESSION" | jq -r '.csrfToken')"
VI_ID="$(echo "$VI_SESSION" | jq -r '.identityId')"

TENANT_ID="$(curl -s -b "$OWNER_JAR" "$BFF_URL/api/tenants" | jq -r '.[0].tenantId')"
echo "Canonical Tenant ID: $TENANT_ID"
echo "Identities: Owner=$OWNER_ID, Operator=$OP_ID, Viewer=$VI_ID"
echo ""

# ==============================================================================
# 1. Multi-User & Membership Scenarios
# ==============================================================================
echo "--- 1. Multi-User & Membership Scenarios ---"

# 1.1 Unauthorized invite attempt by OPERATOR
call_api POST "$OP_JAR" "/api/memberships" \
    -H "X-Tenant-Id: $TENANT_ID" -H "X-CSRF-TOKEN: $OP_CSRF" -H "Content-Type: application/json" \
    -d '{"identityId":"00000000-0000-0000-0000-000000000088","role":"VIEWER"}'
if [ "$LAST_CODE" = "403" ] && check_leaks "$(cat "$LAST_BODY")"; then
    record_result "Membership" "Unauthorized invite by OPERATOR" "403" "$LAST_CODE" "PASS" "OPERATOR cannot invite members (fail-closed 403)"
else
    record_result "Membership" "Unauthorized invite by OPERATOR" "403" "$LAST_CODE" "FAIL" "Unexpected response"
fi

# 1.2 Unauthorized invite attempt by VIEWER
call_api POST "$VI_JAR" "/api/memberships" \
    -H "X-Tenant-Id: $TENANT_ID" -H "X-CSRF-TOKEN: $VI_CSRF" -H "Content-Type: application/json" \
    -d '{"identityId":"00000000-0000-0000-0000-000000000088","role":"VIEWER"}'
if [ "$LAST_CODE" = "403" ] && check_leaks "$(cat "$LAST_BODY")"; then
    record_result "Membership" "Unauthorized invite by VIEWER" "403" "$LAST_CODE" "PASS" "VIEWER cannot invite members (fail-closed 403)"
else
    record_result "Membership" "Unauthorized invite by VIEWER" "403" "$LAST_CODE" "FAIL" "Unexpected response"
fi

# 1.3 Role-change attempt by OPERATOR without permission
call_api PUT "$OP_JAR" "/api/memberships/$VI_ID/role" \
    -H "X-Tenant-Id: $TENANT_ID" -H "X-CSRF-TOKEN: $OP_CSRF" -H "Content-Type: application/json" \
    -d '{"role":"OPERATOR"}'
if [ "$LAST_CODE" = "403" ] && check_leaks "$(cat "$LAST_BODY")"; then
    record_result "Membership" "Role change by non-owner" "403" "$LAST_CODE" "PASS" "Role change rejected for OPERATOR (fail-closed 403)"
else
    record_result "Membership" "Role change by non-owner" "403" "$LAST_CODE" "FAIL" "Unexpected response"
fi

# 1.4 Revocation attempt by VIEWER without permission
call_api DELETE "$VI_JAR" "/api/memberships/$OP_ID" \
    -H "X-Tenant-Id: $TENANT_ID" -H "X-CSRF-TOKEN: $VI_CSRF"
if [ "$LAST_CODE" = "403" ] && check_leaks "$(cat "$LAST_BODY")"; then
    record_result "Membership" "Revocation attempt by VIEWER" "403" "$LAST_CODE" "PASS" "Revocation rejected for VIEWER (fail-closed 403)"
else
    record_result "Membership" "Revocation attempt by VIEWER" "403" "$LAST_CODE" "FAIL" "Unexpected response"
fi

# 1.5 Attempt to invite prohibited role OWNER via /api/memberships
call_api POST "$OWNER_JAR" "/api/memberships" \
    -H "X-Tenant-Id: $TENANT_ID" -H "X-CSRF-TOKEN: $OWNER_CSRF" -H "Content-Type: application/json" \
    -d '{"identityId":"00000000-0000-0000-0000-000000000077","role":"OWNER"}'
if [ "$LAST_CODE" = "400" ] && check_leaks "$(cat "$LAST_BODY")"; then
    record_result "Membership" "Invite prohibited OWNER role" "400" "$LAST_CODE" "PASS" "OWNER role invitation rejected with 400"
else
    record_result "Membership" "Invite prohibited OWNER role" "400" "$LAST_CODE" "FAIL" "Unexpected response"
fi

# 1.6 Duplicate membership creation for existing member (testoperator)
call_api POST "$OWNER_JAR" "/api/memberships" \
    -H "X-Tenant-Id: $TENANT_ID" -H "X-CSRF-TOKEN: $OWNER_CSRF" -H "Content-Type: application/json" \
    -d "{\"identityId\":\"$OP_ID\",\"role\":\"OPERATOR\"}"
if [ "$LAST_CODE" = "409" ] && check_leaks "$(cat "$LAST_BODY")"; then
    record_result "Membership" "Duplicate membership invitation" "409" "$LAST_CODE" "PASS" "Duplicate membership rejected with 409 Conflict"
else
    record_result "Membership" "Duplicate membership invitation" "409" "$LAST_CODE" "FAIL" "Unexpected response"
fi

# 1.7 Update nonexistent membership
call_api PUT "$OWNER_JAR" "/api/memberships/00000000-0000-0000-0000-000000000000/role" \
    -H "X-Tenant-Id: $TENANT_ID" -H "X-CSRF-TOKEN: $OWNER_CSRF" -H "Content-Type: application/json" \
    -d '{"role":"VIEWER"}'
if [ "$LAST_CODE" = "404" ] && check_leaks "$(cat "$LAST_BODY")"; then
    record_result "Membership" "Update nonexistent membership" "404" "$LAST_CODE" "PASS" "Nonexistent membership returned 404 Not Found"
else
    record_result "Membership" "Update nonexistent membership" "404" "$LAST_CODE" "FAIL" "Unexpected response"
fi

# 1.8 Revoke nonexistent membership
call_api DELETE "$OWNER_JAR" "/api/memberships/00000000-0000-0000-0000-000000000000" \
    -H "X-Tenant-Id: $TENANT_ID" -H "X-CSRF-TOKEN: $OWNER_CSRF"
if [ "$LAST_CODE" = "404" ] && check_leaks "$(cat "$LAST_BODY")"; then
    record_result "Membership" "Revoke nonexistent membership" "404" "$LAST_CODE" "PASS" "Nonexistent revocation returned 404 Not Found"
else
    record_result "Membership" "Revoke nonexistent membership" "404" "$LAST_CODE" "FAIL" "Unexpected response"
fi

# 1.9 Verify persisted state is unchanged after rejected attempts
MEMBERS_COUNT="$(curl -s -b "$OWNER_JAR" -H "X-Tenant-Id: $TENANT_ID" "$BFF_URL/api/memberships" | jq 'length')"
if [ "$MEMBERS_COUNT" = "3" ]; then
    record_result "Membership" "Persisted membership state unchanged" "3" "$MEMBERS_COUNT" "PASS" "Total memberships remained exactly 3 (Owner, Operator, Viewer)"
else
    record_result "Membership" "Persisted membership state unchanged" "3" "$MEMBERS_COUNT" "FAIL" "Membership count altered ($MEMBERS_COUNT)"
fi

# ==============================================================================
# 2. Purchase History Boundary, Stale States & Idempotency
# ==============================================================================
echo ""
echo "--- 2. Purchase History Boundary, Stale States & Idempotency ---"

# Setup: Create target customer
RANDOM_DIGITS="$(od -An -N4 -tu4 /dev/urandom | awk '{printf "%08d\n", ($1 % 90000000) + 10000000}')"
PURCHASE_CUST="$(curl -s -b "$OWNER_JAR" -X POST "$BFF_URL/api/customers" \
    -H "X-Tenant-Id: $TENANT_ID" -H "X-CSRF-TOKEN: $OWNER_CSRF" -H "Content-Type: application/json" \
    -d "{\"displayName\":\"Cliente QA Compras\",\"phones\":[{\"number\":\"+569$RANDOM_DIGITS\",\"region\":\"CL\",\"primary\":true}]}")"
P_CID="$(echo "$PURCHASE_CUST" | jq -r '.id')"
echo "Customer for purchase tests: $P_CID"

# 2.1 Malformed purchase date format
call_api POST "$OWNER_JAR" "/api/customers/$P_CID/purchases" \
    -H "X-Tenant-Id: $TENANT_ID" -H "X-CSRF-TOKEN: $OWNER_CSRF" -H "Idempotency-Key: idemp-malformed-$RANDOM_DIGITS" \
    -H "Content-Type: application/json" -d '{"purchasedAt":"invalid-timestamp-string","description":"Café Bourbon"}'
if [ "$LAST_CODE" = "400" ] && check_leaks "$(cat "$LAST_BODY")"; then
    record_result "Purchase" "Malformed timestamp format" "400" "$LAST_CODE" "PASS" "Invalid timestamp rejected with 400"
else
    record_result "Purchase" "Malformed timestamp format" "400" "$LAST_CODE" "FAIL" "Unexpected response"
fi

# 2.2 Timezone-offset variants (+05:30)
call_api POST "$OWNER_JAR" "/api/customers/$P_CID/purchases" \
    -H "X-Tenant-Id: $TENANT_ID" -H "X-CSRF-TOKEN: $OWNER_CSRF" -H "Idempotency-Key: idemp-tz-$RANDOM_DIGITS" \
    -H "Content-Type: application/json" -d '{"purchasedAt":"2026-09-18T14:30:00+05:30","description":"Café con offset +05:30"}'
if [ "$LAST_CODE" = "201" ] && check_leaks "$(cat "$LAST_BODY")"; then
    record_result "Purchase" "Timezone-offset variant (+05:30)" "201" "$LAST_CODE" "PASS" "Accepted and normalized ISO 8601 offset"
else
    record_result "Purchase" "Timezone-offset variant (+05:30)" "201" "$LAST_CODE" "FAIL" "Unexpected response"
fi

# 2.3 Description exceeding maximum (501 characters)
LONG_DESC="$(python3 -c "print('X' * 501)")"
call_api POST "$OWNER_JAR" "/api/customers/$P_CID/purchases" \
    -H "X-Tenant-Id: $TENANT_ID" -H "X-CSRF-TOKEN: $OWNER_CSRF" -H "Idempotency-Key: idemp-long-$RANDOM_DIGITS" \
    -H "Content-Type: application/json" -d "{\"purchasedAt\":\"2026-09-19T10:00:00Z\",\"description\":\"$LONG_DESC\"}"
if [ "$LAST_CODE" = "400" ] && check_leaks "$(cat "$LAST_BODY")"; then
    record_result "Purchase" "Description exceeding max (501 chars)" "400" "$LAST_CODE" "PASS" "501-char description rejected with 400"
else
    record_result "Purchase" "Description exceeding max (501 chars)" "400" "$LAST_CODE" "FAIL" "Unexpected response"
fi

# 2.4 Purchase Idempotency replay with identical payload
P_IDEMP="idemp-purchase-$RANDOM_DIGITS"
call_api POST "$OWNER_JAR" "/api/customers/$P_CID/purchases" \
    -H "X-Tenant-Id: $TENANT_ID" -H "X-CSRF-TOKEN: $OWNER_CSRF" -H "Idempotency-Key: $P_IDEMP" \
    -H "Content-Type: application/json" -d '{"purchasedAt":"2026-09-19T12:00:00Z","description":"Compra idempotente inicial"}'
FIRST_P_CODE="$LAST_CODE"
PURCHASE_ID="$(cat "$LAST_BODY" | jq -r '.id')"
PURCHASE_VERSION="$(cat "$LAST_BODY" | jq -r '.version')"

call_api POST "$OWNER_JAR" "/api/customers/$P_CID/purchases" \
    -H "X-Tenant-Id: $TENANT_ID" -H "X-CSRF-TOKEN: $OWNER_CSRF" -H "Idempotency-Key: $P_IDEMP" \
    -H "Content-Type: application/json" -d '{"purchasedAt":"2026-09-19T12:00:00Z","description":"Compra idempotente inicial"}'
REPLAY_P_CODE="$LAST_CODE"
if [ "$FIRST_P_CODE" = "201" ] && [ "$REPLAY_P_CODE" = "200" ]; then
    record_result "Purchase" "Idempotent replay returns existing purchase" "200" "$REPLAY_P_CODE" "PASS" "First request returned 201, replay returned 200 OK without duplicate"
else
    record_result "Purchase" "Idempotent replay returns existing purchase" "200" "$REPLAY_P_CODE" "FAIL" "First=$FIRST_P_CODE, Replay=$REPLAY_P_CODE"
fi

# 2.5 Purchase Idempotency conflict with different payload
call_api POST "$OWNER_JAR" "/api/customers/$P_CID/purchases" \
    -H "X-Tenant-Id: $TENANT_ID" -H "X-CSRF-TOKEN: $OWNER_CSRF" -H "Idempotency-Key: $P_IDEMP" \
    -H "Content-Type: application/json" -d '{"purchasedAt":"2026-09-19T12:00:00Z","description":"Payload conflictivo"}'
if [ "$LAST_CODE" = "409" ] && check_leaks "$(cat "$LAST_BODY")"; then
    record_result "Purchase" "Conflicting payload with same Idempotency-Key" "409" "$LAST_CODE" "PASS" "Conflicting purchase replay returns 409 Conflict"
else
    record_result "Purchase" "Conflicting payload with same Idempotency-Key" "409" "$LAST_CODE" "FAIL" "Unexpected response ($LAST_CODE)"
fi

# 2.6 Stale If-Match version conflict on correction
call_api PUT "$OWNER_JAR" "/api/customers/$P_CID/purchases/$PURCHASE_ID" \
    -H "X-Tenant-Id: $TENANT_ID" -H "X-CSRF-TOKEN: $OWNER_CSRF" -H "If-Match: \"999\"" \
    -H "Content-Type: application/json" -d '{"purchasedAt":"2026-09-19T12:30:00Z","description":"Corrección con versión vieja"}'
if [ "$LAST_CODE" = "409" ] && check_leaks "$(cat "$LAST_BODY")"; then
    record_result "Purchase" "Stale If-Match conflict on correction" "409" "$LAST_CODE" "PASS" "Stale version on correction rejected with 409 Conflict"
else
    record_result "Purchase" "Stale If-Match conflict on correction" "409" "$LAST_CODE" "FAIL" "Unexpected response"
fi

# 2.7 Stale If-Match version conflict on void
call_api DELETE "$OWNER_JAR" "/api/customers/$P_CID/purchases/$PURCHASE_ID" \
    -H "X-Tenant-Id: $TENANT_ID" -H "X-CSRF-TOKEN: $OWNER_CSRF" -H "If-Match: \"999\""
if [ "$LAST_CODE" = "409" ] && check_leaks "$(cat "$LAST_BODY")"; then
    record_result "Purchase" "Stale If-Match conflict on void" "409" "$LAST_CODE" "PASS" "Stale version on void rejected with 409 Conflict"
else
    record_result "Purchase" "Stale If-Match conflict on void" "409" "$LAST_CODE" "FAIL" "Unexpected response"
fi

# 2.8 Valid void operation (DELETE /api/customers/{cid}/purchases/{pid})
call_api DELETE "$OWNER_JAR" "/api/customers/$P_CID/purchases/$PURCHASE_ID" \
    -H "X-Tenant-Id: $TENANT_ID" -H "X-CSRF-TOKEN: $OWNER_CSRF" -H "If-Match: \"$PURCHASE_VERSION\""
if [ "$LAST_CODE" = "204" ]; then
    record_result "Purchase" "Valid purchase void" "204" "$LAST_CODE" "PASS" "Purchase successfully voided (204 No Content)"
else
    record_result "Purchase" "Valid purchase void" "204" "$LAST_CODE" "FAIL" "Unexpected response ($LAST_CODE)"
fi

# 2.9 Voiding an already voided purchase (with old version -> 409 conflict)
call_api DELETE "$OWNER_JAR" "/api/customers/$P_CID/purchases/$PURCHASE_ID" \
    -H "X-Tenant-Id: $TENANT_ID" -H "X-CSRF-TOKEN: $OWNER_CSRF" -H "If-Match: \"$PURCHASE_VERSION\""
if [ "$LAST_CODE" = "409" ] && check_leaks "$(cat "$LAST_BODY")"; then
    record_result "Purchase" "Voiding already voided purchase" "409" "$LAST_CODE" "PASS" "Re-voiding with prior version rejected with 409 Conflict"
else
    record_result "Purchase" "Voiding already voided purchase" "409" "$LAST_CODE" "FAIL" "Unexpected response ($LAST_CODE)"
fi

# 2.10 Verify rejected purchase attempts do not corrupt state
VALID_PURCHASE_COUNT="$(curl -s -b "$OWNER_JAR" -H "X-Tenant-Id: $TENANT_ID" "$BFF_URL/api/customers/$P_CID/purchases?status=VALID" | jq '.purchases | length')"
if [ "$VALID_PURCHASE_COUNT" = "1" ]; then
    record_result "Purchase" "Purchase state integrity intact" "1" "$VALID_PURCHASE_COUNT" "PASS" "Exactly 1 valid purchase persisted (timezone offset test)"
else
    record_result "Purchase" "Purchase state integrity intact" "1" "$VALID_PURCHASE_COUNT" "FAIL" "Purchase count mismatch ($VALID_PURCHASE_COUNT)"
fi

# ==============================================================================
# 3. Consent & Do-Not-Contact Negative Paths
# ==============================================================================
echo ""
echo "--- 3. Consent & Do-Not-Contact Negative Paths ---"

POLICY_RES="$(curl -s -b "$OWNER_JAR" -H "X-Tenant-Id: $TENANT_ID" "$BFF_URL/api/customers/$P_CID/contact-policy")"
POLICY_VERSION="$(echo "$POLICY_RES" | jq -r '.version')"
CONTACT_ID="$(echo "$POLICY_RES" | jq -r '.consents[0].contactId')"

# 3.1 Stale consent version conflict (competing update)
call_api PUT "$OWNER_JAR" "/api/customers/$P_CID/contacts/$CONTACT_ID/consents/WHATSAPP" \
    -H "X-Tenant-Id: $TENANT_ID" -H "X-CSRF-TOKEN: $OWNER_CSRF" -H "If-Match: \"999\"" -H "Content-Type: application/json" \
    -d '{"status":"GRANTED","source":"CUSTOMER_VERBAL"}'
if [ "$LAST_CODE" = "409" ] && check_leaks "$(cat "$LAST_BODY")"; then
    record_result "Consent" "Stale version on consent update" "409" "$LAST_CODE" "PASS" "Stale ETag returns 409 Conflict"
else
    record_result "Consent" "Stale version on consent update" "409" "$LAST_CODE" "FAIL" "Unexpected response"
fi

# 3.2 Competing / invalid transition rejected without mutating state
BEFORE_STATUS="$(curl -s -b "$OWNER_JAR" -H "X-Tenant-Id: $TENANT_ID" "$BFF_URL/api/customers/$P_CID/contact-policy" | jq -r '.consents[0].status')"
call_api PUT "$OWNER_JAR" "/api/customers/$P_CID/contacts/$CONTACT_ID/consents/WHATSAPP" \
    -H "X-Tenant-Id: $TENANT_ID" -H "X-CSRF-TOKEN: $OWNER_CSRF" -H "If-Match: \"$POLICY_VERSION\"" -H "Content-Type: application/json" \
    -d '{"status":"INVALID_ENUM_STATE","source":"CUSTOMER_VERBAL"}'
AFTER_STATUS="$(curl -s -b "$OWNER_JAR" -H "X-Tenant-Id: $TENANT_ID" "$BFF_URL/api/customers/$P_CID/contact-policy" | jq -r '.consents[0].status')"
if [ "$LAST_CODE" = "400" ] && [ "$BEFORE_STATUS" = "$AFTER_STATUS" ]; then
    record_result "Consent" "Invalid status rejected without mutation" "400" "$LAST_CODE" "PASS" "Rejected with 400 and status remained $BEFORE_STATUS"
else
    record_result "Consent" "Invalid status rejected without mutation" "400" "$LAST_CODE" "FAIL" "Unexpected response ($LAST_CODE) or state mutated"
fi

# ==============================================================================
# 4. Follow-Up Negative & Stale-Action Coverage
# ==============================================================================
echo ""
echo "--- 4. Follow-Up Negative & Stale-Action Coverage ---"

# Fetch current follow-up policy for customer
call_api GET "$OWNER_JAR" "/api/customers/$P_CID/follow-up-policy" -H "X-Tenant-Id: $TENANT_ID"
FU_ETAG="$(grep -i "^etag:" "$LAST_HEADERS" | tr -d '\r\n' | awk '{print $2}')"
if [ -z "$FU_ETAG" ]; then FU_ETAG="\"0\""; fi

# 4.1 Snooze with boundary past date (e.g., 2020-01-01)
call_api PUT "$OWNER_JAR" "/api/customers/$P_CID/follow-up-snooze" \
    -H "X-Tenant-Id: $TENANT_ID" -H "X-CSRF-TOKEN: $OWNER_CSRF" -H "If-Match: $FU_ETAG" -H "Content-Type: application/json" \
    -d '{"until":"2020-01-01"}'
if [ "$LAST_CODE" = "400" ] && check_leaks "$(cat "$LAST_BODY")"; then
    record_result "FollowUp" "Past snooze date rejected" "400" "$LAST_CODE" "PASS" "Snooze date in the past rejected with 400 Bad Request"
else
    record_result "FollowUp" "Past snooze date rejected" "400" "$LAST_CODE" "FAIL" "Unexpected response ($LAST_CODE)"
fi

# 4.2 Attempt follow-up completion on an ineligible customer (OPTED_OUT)
# First set consent to OPTED_OUT so customer becomes INELIGIBLE
call_api PUT "$OWNER_JAR" "/api/customers/$P_CID/contacts/$CONTACT_ID/consents/WHATSAPP" \
    -H "X-Tenant-Id: $TENANT_ID" -H "X-CSRF-TOKEN: $OWNER_CSRF" -H "If-Match: \"$POLICY_VERSION\"" -H "Content-Type: application/json" \
    -d '{"status":"OPTED_OUT","source":"CUSTOMER_VERBAL"}'

# Verify eligibility is INELIGIBLE
call_api GET "$OWNER_JAR" "/api/customers/$P_CID/follow-up-eligibility" -H "X-Tenant-Id: $TENANT_ID"
ELIGIBLE_STATUS="$(cat "$LAST_BODY" | jq -r '.status')"

# Now try to record manual follow-up on this ineligible customer
call_api POST "$OWNER_JAR" "/api/customers/$P_CID/manual-follow-ups" \
    -H "X-Tenant-Id: $TENANT_ID" -H "X-CSRF-TOKEN: $OWNER_CSRF" -H "If-Match: $FU_ETAG" \
    -H "Idempotency-Key: idemp-optout-$RANDOM_DIGITS" -H "Content-Type: application/json" \
    -d '{"notes":"Intento en cliente no elegible"}'
if [ "$LAST_CODE" = "409" ] && check_leaks "$(cat "$LAST_BODY")"; then
    record_result "FollowUp" "Follow-up on ineligible customer (opted-out)" "409" "$LAST_CODE" "PASS" "Manual follow-up on ineligible customer rejected with 409 Conflict"
else
    record_result "FollowUp" "Follow-up on ineligible customer (opted-out)" "409" "$LAST_CODE" "FAIL" "Expected 409, got $LAST_CODE"
fi

# Restore consent to GRANTED for subsequent tests
CURRENT_POLICY_VER="$(curl -s -b "$OWNER_JAR" -H "X-Tenant-Id: $TENANT_ID" "$BFF_URL/api/customers/$P_CID/contact-policy" | jq -r '.version')"
call_api PUT "$OWNER_JAR" "/api/customers/$P_CID/contacts/$CONTACT_ID/consents/WHATSAPP" \
    -H "X-Tenant-Id: $TENANT_ID" -H "X-CSRF-TOKEN: $OWNER_CSRF" -H "If-Match: \"$CURRENT_POLICY_VER\"" -H "Content-Type: application/json" \
    -d '{"status":"GRANTED","source":"CUSTOMER_VERBAL"}'

# 4.3 Idempotency key replay on manual follow-up with identical payload
call_api GET "$OWNER_JAR" "/api/customers/$P_CID/follow-up-policy" -H "X-Tenant-Id: $TENANT_ID"
FU_ETAG="$(grep -i "^etag:" "$LAST_HEADERS" | tr -d '\r\n' | awk '{print $2}')"
if [ -z "$FU_ETAG" ]; then FU_ETAG="\"0\""; fi

FU_IDEMP="idemp-fu-$RANDOM_DIGITS"
call_api POST "$OWNER_JAR" "/api/customers/$P_CID/manual-follow-ups" \
    -H "X-Tenant-Id: $TENANT_ID" -H "X-CSRF-TOKEN: $OWNER_CSRF" -H "If-Match: $FU_ETAG" -H "Idempotency-Key: $FU_IDEMP" \
    -H "Content-Type: application/json" -d '{"notes":"Primer intento manual"}'
FIRST_FU_CODE="$LAST_CODE"

call_api POST "$OWNER_JAR" "/api/customers/$P_CID/manual-follow-ups" \
    -H "X-Tenant-Id: $TENANT_ID" -H "X-CSRF-TOKEN: $OWNER_CSRF" -H "If-Match: $FU_ETAG" -H "Idempotency-Key: $FU_IDEMP" \
    -H "Content-Type: application/json" -d '{"notes":"Primer intento manual"}'
REPLAY_FU_CODE="$LAST_CODE"

if [ "$FIRST_FU_CODE" = "201" ] && [ "$REPLAY_FU_CODE" = "200" ]; then
    record_result "FollowUp" "Idempotent retry returns existing completion" "200" "$REPLAY_FU_CODE" "PASS" "First request returned 201, replay returned 200 OK without duplicate"
else
    record_result "FollowUp" "Idempotent retry returns existing completion" "200" "$REPLAY_FU_CODE" "FAIL" "First=$FIRST_FU_CODE, Replay=$REPLAY_FU_CODE"
fi

# 4.4 Stale If-Match version conflict on manual follow-up
call_api POST "$OWNER_JAR" "/api/customers/$P_CID/manual-follow-ups" \
    -H "X-Tenant-Id: $TENANT_ID" -H "X-CSRF-TOKEN: $OWNER_CSRF" -H "If-Match: \"999\"" \
    -H "Idempotency-Key: idemp-stale-$RANDOM_DIGITS" -H "Content-Type: application/json" \
    -d '{"notes":"Intento con versión vieja"}'
if [ "$LAST_CODE" = "409" ] && check_leaks "$(cat "$LAST_BODY")"; then
    record_result "FollowUp" "Stale If-Match on manual follow-up" "409" "$LAST_CODE" "PASS" "Stale policy version rejected with 409 Conflict"
else
    record_result "FollowUp" "Stale If-Match on manual follow-up" "409" "$LAST_CODE" "FAIL" "Unexpected response ($LAST_CODE)"
fi

# ==============================================================================
# 5. Compound-Invalid Scenario (API Level)
# ==============================================================================
echo ""
echo "--- 5. Compound-Invalid Scenario (API Level) ---"

# Compound: Malformed date + invalid payload + missing required If-Match header
call_api PUT "$OWNER_JAR" "/api/customers/$P_CID/follow-up-snooze" \
    -H "X-Tenant-Id: $TENANT_ID" -H "X-CSRF-TOKEN: $OWNER_CSRF" -H "Content-Type: application/json" \
    -d '{"until":"not-a-valid-date","extraField":12345}'
if [ "$LAST_CODE" = "400" ] && check_leaks "$(cat "$LAST_BODY")"; then
    record_result "Compound" "Malformed date + missing If-Match" "400" "$LAST_CODE" "PASS" "Compound invalid request rejected cleanly with 400"
else
    record_result "Compound" "Malformed date + missing If-Match" "400" "$LAST_CODE" "FAIL" "Unexpected response"
fi

# ==============================================================================
# 6. Protocol & Query Gaps
# ==============================================================================
echo ""
echo "--- 6. Protocol & Query Gaps ---"

# 6.1 Missing Content-Type on JSON POST
call_api POST "$OWNER_JAR" "/api/customers" \
    -H "X-Tenant-Id: $TENANT_ID" -H "X-CSRF-TOKEN: $OWNER_CSRF" \
    -d "{\"displayName\":\"Missing Content Type\",\"phones\":[{\"number\":\"+56955554444\",\"region\":\"CL\",\"primary\":true}]}"
if [ "$LAST_CODE" = "415" ] || [ "$LAST_CODE" = "400" ]; then
    record_result "Protocol" "Missing Content-Type on mutation" "415/400" "$LAST_CODE" "PASS" "Rejected with HTTP $LAST_CODE"
else
    record_result "Protocol" "Missing Content-Type on mutation" "415/400" "$LAST_CODE" "FAIL" "Unexpected response"
fi

# 6.2 Incorrect Content-Type (application/xml)
call_api POST "$OWNER_JAR" "/api/customers" \
    -H "X-Tenant-Id: $TENANT_ID" -H "X-CSRF-TOKEN: $OWNER_CSRF" -H "Content-Type: application/xml" \
    -d "<customer><displayName>XML Test</displayName></customer>"
if [ "$LAST_CODE" = "415" ] || [ "$LAST_CODE" = "400" ]; then
    record_result "Protocol" "Incorrect Content-Type (application/xml)" "415/400" "$LAST_CODE" "PASS" "Rejected with HTTP $LAST_CODE"
else
    record_result "Protocol" "Incorrect Content-Type (application/xml)" "415/400" "$LAST_CODE" "FAIL" "Unexpected response"
fi

# 6.3 Unusual Accept header (Accept: application/xml)
call_api GET "$OWNER_JAR" "/api/customers?limit=1" \
    -H "X-Tenant-Id: $TENANT_ID" -H "Accept: application/xml"
if [ "$LAST_CODE" = "406" ] || [ "$LAST_CODE" = "200" ]; then
    record_result "Protocol" "Unusual Accept header (application/xml)" "406/200" "$LAST_CODE" "PASS" "Handled cleanly without 500: HTTP $LAST_CODE"
else
    record_result "Protocol" "Unusual Accept header (application/xml)" "406/200" "$LAST_CODE" "FAIL" "Unexpected response"
fi

# 6.4 Conflicting repeated query parameters (?status=DUE&status=OVERDUE)
call_api GET "$OWNER_JAR" "/api/follow-up-queue?status=DUE&status=OVERDUE" \
    -H "X-Tenant-Id: $TENANT_ID"
if [ "$LAST_CODE" = "400" ] || [ "$LAST_CODE" = "200" ]; then
    record_result "Protocol" "Parameter pollution (?status=DUE&status=OVERDUE)" "400/200" "$LAST_CODE" "PASS" "Handled safely without 500: HTTP $LAST_CODE"
else
    record_result "Protocol" "Parameter pollution (?status=DUE&status=OVERDUE)" "400/200" "$LAST_CODE" "FAIL" "Unexpected response"
fi

# 6.5 Extremely long search query (1000 characters)
LONG_QUERY="$(python3 -c "print('Q' * 1000)")"
call_api GET "$OWNER_JAR" "/api/customers?search=$LONG_QUERY" \
    -H "X-Tenant-Id: $TENANT_ID"
if [ "$LAST_CODE" = "200" ] || [ "$LAST_CODE" = "400" ]; then
    record_result "Protocol" "Extremely long search query (1000 chars)" "200/400" "$LAST_CODE" "PASS" "Handled cleanly without 500: HTTP $LAST_CODE"
else
    record_result "Protocol" "Extremely long search query (1000 chars)" "200/400" "$LAST_CODE" "FAIL" "Unexpected response"
fi

echo ""
echo "======================================================================"
echo "FOLLOW-UP TEST SUMMARY: $PASSED_TESTS / $TOTAL_TESTS PASSED (Failures: $FAILED_TESTS)"
echo "======================================================================"

if [ "$FAILED_TESTS" -gt 0 ]; then
    exit 1
fi
