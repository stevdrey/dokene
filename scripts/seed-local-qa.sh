#!/usr/bin/env bash
# ==============================================================================
# seed-local-qa.sh
#
# Deterministic Dev-Seed Fixture for Multi-Tenant Local QA (Issue #66)
#
# Provisions / verifies synthetic identities across distinct RBAC roles:
# - testuser      -> OWNER    (workspace provisioning & administration)
# - testoperator  -> OPERATOR (customer & follow-up operations)
# - testviewer    -> VIEWER   (read-only access)
#
# Usage:
#   ./scripts/seed-local-qa.sh          # Seed workspaces & memberships
#   ./scripts/seed-local-qa.sh --verify # Seed and verify RBAC enforcement
# ==============================================================================

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEMP_DIR="$(mktemp -d /tmp/dokene-seed-qa.XXXXXX)"

cleanup() {
    rm -rf "$TEMP_DIR"
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
TEST_PASSWORD="${DOKENE_TEST_USER_PASSWORD:-testpassword}"

VERIFY_MODE=false
if [[ "${1:-}" == "--verify" ]]; then
    VERIFY_MODE=true
fi

echo "======================================================================"
echo "Dokene Local QA Synthetic Multi-Tenant RBAC Seeder (Issue #66)"
echo "Target BFF:      $BFF_URL"
echo "Target Keycloak: $KEYCLOAK_URL"
echo "Verify Mode:     $VERIFY_MODE"
echo "======================================================================"

# ------------------------------------------------------------------------------
# 1. Verify Prerequisites
# ------------------------------------------------------------------------------
command -v curl >/dev/null 2>&1 || { echo "Error: curl is required." >&2; exit 1; }
command -v jq >/dev/null 2>&1 || { echo "Error: jq is required." >&2; exit 1; }

echo -n "Checking Keycloak OIDC discovery... "
DISCOVERY_STATUS="$(curl -s -o /dev/null -w "%{http_code}" "$KEYCLOAK_URL/realms/dokene/.well-known/openid-configuration" || true)"
if [ "$DISCOVERY_STATUS" != "200" ]; then
    echo "FAILED (HTTP $DISCOVERY_STATUS)"
    echo "Error: Keycloak is unreachable at $KEYCLOAK_URL. Run 'docker compose up -d'." >&2
    exit 1
fi
echo "OK"

echo -n "Checking Dokene Backend / BFF... "
BFF_STATUS="$(curl -s -o /dev/null -w "%{http_code}" "$BFF_URL/api/session" || true)"
if [ "$BFF_STATUS" != "401" ]; then
    echo "FAILED (HTTP $BFF_STATUS)"
    echo "Error: Expected backend BFF at $BFF_URL to return 401 Unauthorized." >&2
    echo "Please launch backend: (cd backend && ./gradlew bootRun)" >&2
    exit 1
fi
echo "OK"

# ------------------------------------------------------------------------------
# 2. Login Helper Function
# ------------------------------------------------------------------------------
login_user() {
    local username="$1"
    local password="$2"
    local cookie_jar="$TEMP_DIR/$username-cookies.txt"
    local header_log="$TEMP_DIR/$username-headers.txt"
    local body_log="$TEMP_DIR/$username-body.txt"

    # Step A: Initiate authorization flow
    local auth_status
    auth_status="$(curl -s -c "$cookie_jar" -D "$header_log" -o "$body_log" -w "%{http_code}" "$BFF_URL/oauth2/authorization/dokene")"
    if [ "$auth_status" != "302" ]; then
        echo "Error: Initial redirect failed for $username (HTTP $auth_status)" >&2
        return 1
    fi

    local keycloak_auth_url
    keycloak_auth_url="$(grep -i "^location:" "$header_log" | tr -d '\r\n' | awk '{print $2}')"

    # Step B: Fetch Keycloak login form
    curl -s -c "$cookie_jar" -b "$cookie_jar" -D "$header_log" -o "$body_log" "$keycloak_auth_url"
    local form_action
    form_action="$(grep -o 'action="[^"]*"' "$body_log" | head -n 1 | cut -d'"' -f2 | sed 's/&amp;/\&/g')"
    if [ -z "$form_action" ]; then
        echo "Error: Could not parse Keycloak login form action for $username" >&2
        return 1
    fi

    # Step C: Submit credentials
    curl -s -c "$cookie_jar" -b "$cookie_jar" -D "$header_log" -o "$body_log" \
        --data-urlencode "username=$username" \
        --data-urlencode "password=$password" \
        "$form_action"

    local callback_url
    callback_url="$(grep -i "^location:" "$header_log" | tr -d '\r\n' | awk '{print $2}')"
    if [[ "$callback_url" != *"/login/oauth2/code/dokene"* ]]; then
        echo "Error: Keycloak authentication failed for $username" >&2
        return 1
    fi

    # Step D: Complete callback exchange
    curl -s -c "$cookie_jar" -b "$cookie_jar" -D "$header_log" -o "$body_log" "$callback_url"

    echo "$cookie_jar"
}

get_session_info() {
    local cookie_jar="$1"
    curl -s -b "$cookie_jar" "$BFF_URL/api/session"
}

# ------------------------------------------------------------------------------
# 3. Authenticate Synthetic Identities & Resolve Internal IDs
# ------------------------------------------------------------------------------
echo ""
echo "Authenticating synthetic test identities..."

echo -n "• Logging in 'testuser' (Owner)... "
OWNER_COOKIE_JAR="$(login_user "testuser" "$TEST_PASSWORD")"
OWNER_SESSION="$(get_session_info "$OWNER_COOKIE_JAR")"
OWNER_IDENTITY_ID="$(echo "$OWNER_SESSION" | jq -r '.identityId')"
OWNER_CSRF="$(echo "$OWNER_SESSION" | jq -r '.csrfToken')"
echo "OK (Identity ID: $OWNER_IDENTITY_ID)"

echo -n "• Logging in 'testoperator' (Operator)... "
OPERATOR_COOKIE_JAR="$(login_user "testoperator" "$TEST_PASSWORD")"
OPERATOR_SESSION="$(get_session_info "$OPERATOR_COOKIE_JAR")"
OPERATOR_IDENTITY_ID="$(echo "$OPERATOR_SESSION" | jq -r '.identityId')"
OPERATOR_CSRF="$(echo "$OPERATOR_SESSION" | jq -r '.csrfToken')"
echo "OK (Identity ID: $OPERATOR_IDENTITY_ID)"

echo -n "• Logging in 'testviewer' (Viewer)... "
VIEWER_COOKIE_JAR="$(login_user "testviewer" "$TEST_PASSWORD")"
VIEWER_SESSION="$(get_session_info "$VIEWER_COOKIE_JAR")"
VIEWER_IDENTITY_ID="$(echo "$VIEWER_SESSION" | jq -r '.identityId')"
VIEWER_CSRF="$(echo "$VIEWER_SESSION" | jq -r '.csrfToken')"
echo "OK (Identity ID: $VIEWER_IDENTITY_ID)"

# ------------------------------------------------------------------------------
# 4. Provision or Find Canonical QA Workspace
# ------------------------------------------------------------------------------
WORKSPACE_NAME="QA Café Norte"
IDEMPOTENCY_KEY="qa-seed-cafe-norte"
echo ""
echo "Establishing canonical workspace: '$WORKSPACE_NAME'..."

PROVISION_HEADER_LOG="$TEMP_DIR/provision-headers.txt"
PROVISION_BODY_LOG="$TEMP_DIR/provision-body.txt"
PROVISION_HTTP_CODE="$(curl -s -b "$OWNER_COOKIE_JAR" -D "$PROVISION_HEADER_LOG" -o "$PROVISION_BODY_LOG" -w "%{http_code}" \
    -X POST "$BFF_URL/api/tenants" \
    -H "Content-Type: application/json" \
    -H "X-CSRF-TOKEN: $OWNER_CSRF" \
    -H "Idempotency-Key: $IDEMPOTENCY_KEY" \
    -d "{\"displayName\": \"$WORKSPACE_NAME\", \"idempotencyKey\": \"$IDEMPOTENCY_KEY\"}")"

if [ "$PROVISION_HTTP_CODE" = "403" ]; then
    echo "FAILED (HTTP 403)"
    echo "Error: Workspace provisioning is disabled by default in Dokene." >&2
    echo "Please set DOKENE_PROVISIONING_ENABLED=true (or dokene.provisioning.enabled=true) in your backend environment." >&2
    exit 1
elif [ "$PROVISION_HTTP_CODE" != "200" ] && [ "$PROVISION_HTTP_CODE" != "201" ]; then
    echo "FAILED (HTTP $PROVISION_HTTP_CODE)"
    echo "Error: Failed to provision workspace: $(cat "$PROVISION_BODY_LOG")" >&2
    exit 1
fi

TENANT_ID="$(jq -r '.tenantId' "$PROVISION_BODY_LOG")"
if [ "$PROVISION_HTTP_CODE" = "201" ]; then
    echo "• Successfully provisioned new workspace with ID: $TENANT_ID"
else
    echo "• Resolved canonical workspace with ID: $TENANT_ID"
fi

# ------------------------------------------------------------------------------
# 5. Assign Memberships to Synthetic Identities
# ------------------------------------------------------------------------------
echo ""
echo "Establishing workspace memberships in '$WORKSPACE_NAME'..."

MEMBERSHIPS_JSON="$(curl -s -b "$OWNER_COOKIE_JAR" -H "X-Tenant-Id: $TENANT_ID" "$BFF_URL/api/memberships")"

# Assign testoperator as OPERATOR
if echo "$MEMBERSHIPS_JSON" | jq -e ".[] | select(.identityId == \"$OPERATOR_IDENTITY_ID\")" >/dev/null 2>&1; then
    CURRENT_OP_STATUS="$(echo "$MEMBERSHIPS_JSON" | jq -r ".[] | select(.identityId == \"$OPERATOR_IDENTITY_ID\") | .status")"
    if [ "$CURRENT_OP_STATUS" = "REVOKED" ]; then
        echo "Error: Membership for 'testoperator' in workspace '$WORKSPACE_NAME' is in REVOKED status." >&2
        echo "Revoked memberships cannot be reactivated in Dokene. Please reset your local database (e.g. 'docker compose down -v && docker compose up -d') to run QA seeding afresh." >&2
        exit 1
    fi
    CURRENT_OP_ROLE="$(echo "$MEMBERSHIPS_JSON" | jq -r ".[] | select(.identityId == \"$OPERATOR_IDENTITY_ID\") | .role")"
    if [ "$CURRENT_OP_ROLE" != "OPERATOR" ]; then
        echo -n "• Updating 'testoperator' role to OPERATOR... "
        curl -s -b "$OWNER_COOKIE_JAR" -X PUT "$BFF_URL/api/memberships/$OPERATOR_IDENTITY_ID/role" \
            -H "X-Tenant-Id: $TENANT_ID" \
            -H "X-CSRF-TOKEN: $OWNER_CSRF" \
            -H "Content-Type: application/json" \
            -d '{"role":"OPERATOR"}'
        echo "UPDATED"
    else
        echo "• 'testoperator' is already an OPERATOR"
    fi
else
    echo -n "• Adding 'testoperator' with role OPERATOR... "
    curl -s -b "$OWNER_COOKIE_JAR" -X POST "$BFF_URL/api/memberships" \
        -H "X-Tenant-Id: $TENANT_ID" \
        -H "X-CSRF-TOKEN: $OWNER_CSRF" \
        -H "Content-Type: application/json" \
        -d "{\"identityId\":\"$OPERATOR_IDENTITY_ID\",\"role\":\"OPERATOR\"}" >/dev/null
    echo "CREATED"
fi

# Assign testviewer as VIEWER
if echo "$MEMBERSHIPS_JSON" | jq -e ".[] | select(.identityId == \"$VIEWER_IDENTITY_ID\")" >/dev/null 2>&1; then
    CURRENT_VI_STATUS="$(echo "$MEMBERSHIPS_JSON" | jq -r ".[] | select(.identityId == \"$VIEWER_IDENTITY_ID\") | .status")"
    if [ "$CURRENT_VI_STATUS" = "REVOKED" ]; then
        echo "Error: Membership for 'testviewer' in workspace '$WORKSPACE_NAME' is in REVOKED status." >&2
        echo "Revoked memberships cannot be reactivated in Dokene. Please reset your local database (e.g. 'docker compose down -v && docker compose up -d') to run QA seeding afresh." >&2
        exit 1
    fi
    CURRENT_VI_ROLE="$(echo "$MEMBERSHIPS_JSON" | jq -r ".[] | select(.identityId == \"$VIEWER_IDENTITY_ID\") | .role")"
    if [ "$CURRENT_VI_ROLE" != "VIEWER" ]; then
        echo -n "• Updating 'testviewer' role to VIEWER... "
        curl -s -b "$OWNER_COOKIE_JAR" -X PUT "$BFF_URL/api/memberships/$VIEWER_IDENTITY_ID/role" \
            -H "X-Tenant-Id: $TENANT_ID" \
            -H "X-CSRF-TOKEN: $OWNER_CSRF" \
            -H "Content-Type: application/json" \
            -d '{"role":"VIEWER"}'
        echo "UPDATED"
    else
        echo "• 'testviewer' is already a VIEWER"
    fi
else
    echo -n "• Adding 'testviewer' with role VIEWER... "
    curl -s -b "$OWNER_COOKIE_JAR" -X POST "$BFF_URL/api/memberships" \
        -H "X-Tenant-Id: $TENANT_ID" \
        -H "X-CSRF-TOKEN: $OWNER_CSRF" \
        -H "Content-Type: application/json" \
        -d "{\"identityId\":\"$VIEWER_IDENTITY_ID\",\"role\":\"VIEWER\"}" >/dev/null
    echo "CREATED"
fi

# ------------------------------------------------------------------------------
# 6. Output Synthetic Identities Summary Table
# ------------------------------------------------------------------------------
echo ""
echo "======================================================================"
echo "SYNTHETIC IDENTITIES & MEMBERSHIPS READY FOR LOCAL QA"
echo "Workspace:  $WORKSPACE_NAME ($TENANT_ID)"
echo "----------------------------------------------------------------------"
printf "%-14s | %-24s | %-10s | %-12s\n" "Username" "Email" "Role" "Password"
echo "---------------+--------------------------+------------+--------------"
printf "%-14s | %-24s | %-10s | %-12s\n" "testuser" "testuser@dokene.local" "OWNER" "$TEST_PASSWORD"
printf "%-14s | %-24s | %-10s | %-12s\n" "testoperator" "testoperator@dokene.local" "OPERATOR" "$TEST_PASSWORD"
printf "%-14s | %-24s | %-10s | %-12s\n" "testviewer" "testviewer@dokene.local" "VIEWER" "$TEST_PASSWORD"
echo "======================================================================"
echo ""
echo "Manual QA Instructions:"
echo "1. Open the web browser at http://localhost:5173 (or http://localhost:8080)"
echo "2. Log in as 'testoperator' to verify operator controls (create customers, record follow-ups)"
echo "3. Log in as 'testviewer' to verify read-only view (mutation controls hidden, actions forbidden)"
echo ""

# ------------------------------------------------------------------------------
# 7. Automated RBAC Verification (if --verify requested)
# ------------------------------------------------------------------------------
if [ "$VERIFY_MODE" = true ]; then
    echo "======================================================================"
    echo "AUTOMATED RBAC CONSTRAINT VERIFICATION"
    echo "======================================================================"

    RANDOM_SUFFIX="$((RANDOM % 90000000 + 10000000))"
    RANDOM_PHONE="+569${RANDOM_SUFFIX}"

    # Verify OPERATOR constraints
    echo -n "• [OPERATOR] Listing memberships (MEMBERSHIP_READ)... "
    OP_MEMBERSHIPS_STATUS="$(curl -s -b "$OPERATOR_COOKIE_JAR" -H "X-Tenant-Id: $TENANT_ID" -o /dev/null -w "%{http_code}" "$BFF_URL/api/memberships")"
    if [ "$OP_MEMBERSHIPS_STATUS" = "200" ]; then echo "PASS (200 OK)"; else echo "FAIL ($OP_MEMBERSHIPS_STATUS)"; exit 1; fi

    echo -n "• [OPERATOR] Creating customer (CUSTOMER_WRITE)... "
    OP_CUST_STATUS="$(curl -s -b "$OPERATOR_COOKIE_JAR" -X POST "$BFF_URL/api/customers" \
        -H "X-Tenant-Id: $TENANT_ID" -H "X-CSRF-TOKEN: $OPERATOR_CSRF" \
        -H "Content-Type: application/json" \
        -d "{\"displayName\":\"Cliente creado por Operator\",\"phones\":[{\"number\":\"$RANDOM_PHONE\",\"region\":\"CL\",\"primary\":true}]}" \
        -o /dev/null -w "%{http_code}")"
    if [ "$OP_CUST_STATUS" = "201" ]; then echo "PASS (201 Created)"; else echo "FAIL ($OP_CUST_STATUS)"; exit 1; fi

    echo -n "• [OPERATOR] Inviting new member (Should be FORBIDDEN)... "
    OP_INVITE_STATUS="$(curl -s -b "$OPERATOR_COOKIE_JAR" -X POST "$BFF_URL/api/memberships" \
        -H "X-Tenant-Id: $TENANT_ID" -H "X-CSRF-TOKEN: $OPERATOR_CSRF" \
        -H "Content-Type: application/json" \
        -d '{"identityId":"00000000-0000-0000-0000-000000000099","role":"VIEWER"}' \
        -o /dev/null -w "%{http_code}")"
    if [ "$OP_INVITE_STATUS" = "403" ]; then echo "PASS (403 Forbidden fail-closed)"; else echo "FAIL ($OP_INVITE_STATUS)"; exit 1; fi

    # Verify VIEWER constraints
    echo -n "• [VIEWER] Listing customers (CUSTOMER_READ)... "
    VI_CUST_STATUS="$(curl -s -b "$VIEWER_COOKIE_JAR" -H "X-Tenant-Id: $TENANT_ID" -o /dev/null -w "%{http_code}" "$BFF_URL/api/customers")"
    if [ "$VI_CUST_STATUS" = "200" ]; then echo "PASS (200 OK)"; else echo "FAIL ($VI_CUST_STATUS)"; exit 1; fi

    echo -n "• [VIEWER] Creating customer (Should be FORBIDDEN)... "
    VI_CUST_CREATE_STATUS="$(curl -s -b "$VIEWER_COOKIE_JAR" -X POST "$BFF_URL/api/customers" \
        -H "X-Tenant-Id: $TENANT_ID" -H "X-CSRF-TOKEN: $VIEWER_CSRF" \
        -H "Content-Type: application/json" \
        -d '{"displayName":"Cliente intento por Viewer","phones":[{"number":"+56999887766","region":"CL","primary":true}]}' \
        -o /dev/null -w "%{http_code}")"
    if [ "$VI_CUST_CREATE_STATUS" = "403" ]; then echo "PASS (403 Forbidden fail-closed)"; else echo "FAIL ($VI_CUST_CREATE_STATUS)"; exit 1; fi

    echo -n "• [VIEWER] Inviting member (Should be FORBIDDEN)... "
    VI_INVITE_STATUS="$(curl -s -b "$VIEWER_COOKIE_JAR" -X POST "$BFF_URL/api/memberships" \
        -H "X-Tenant-Id: $TENANT_ID" -H "X-CSRF-TOKEN: $VIEWER_CSRF" \
        -H "Content-Type: application/json" \
        -d '{"identityId":"00000000-0000-0000-0000-000000000099","role":"VIEWER"}' \
        -o /dev/null -w "%{http_code}")"
    if [ "$VI_INVITE_STATUS" = "403" ]; then echo "PASS (403 Forbidden fail-closed)"; else echo "FAIL ($VI_INVITE_STATUS)"; exit 1; fi

    echo "----------------------------------------------------------------------"
    echo "RESULT: ALL MULTI-TENANT RBAC CONSTRAINTS VERIFIED SUCCESSFULLY!"
    echo "======================================================================"
fi
