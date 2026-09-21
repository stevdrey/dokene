#!/usr/bin/env bash
# ==============================================================================
# verify-issue-61-cross-features.sh
#
# Exhaustive Cross-Feature Multi-Step Verification Script for Issue #61
# ==============================================================================

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEMP_DIR="$(mktemp -d /tmp/dokene-qa-61.XXXXXX)"

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
echo -e "Family\tScenario\tExpected\tObserved\tStatus\tDetails" > "$RESULTS_FILE"

record_result() {
    local fam="$1"
    local name="$2"
    local exp="$3"
    local obs="$4"
    local status="$5"
    local details="$6"

    TOTAL_TESTS=$((TOTAL_TESTS + 1))
    if [ "$status" = "PASS" ]; then
        PASSED_TESTS=$((PASSED_TESTS + 1))
        echo "  [PASS] $fam :: $name"
    else
        FAILED_TESTS=$((FAILED_TESTS + 1))
        echo "  [FAIL] $fam :: $name (Expected $exp, got $obs. Details: $details)"
    fi
    echo -e "$fam\t$name\t$exp\t$obs\t$status\t$details" >> "$RESULTS_FILE"
}

login() {
    local username="$1"
    local cookie_jar="$2"
    local header_log="$TEMP_DIR/login-headers.txt"

    curl -s -c "$cookie_jar" -D "$header_log" "$BFF_URL/oauth2/authorization/dokene" >/dev/null
    local auth_url
    auth_url=$(grep -i "^location:" "$header_log" | tr -d "\r\n" | awk "{print \$2}")

    local login_page
    login_page=$(curl -s -c "$cookie_jar" -b "$cookie_jar" "$auth_url")
    local form_action
    form_action=$(echo "$login_page" | grep -o "action=\"[^\"]*\"" | head -n 1 | cut -d"\"" -f2 | sed "s/&amp;/\&/g")

    curl -s -c "$cookie_jar" -b "$cookie_jar" -D "$header_log" -X POST "$form_action" \
        --data-urlencode "username=$username" \
        --data-urlencode "password=$TEST_PASSWORD" >/dev/null

    local callback_url
    callback_url=$(grep -i "^location:" "$header_log" | tr -d "\r\n" | awk "{print \$2}")
    curl -s -c "$cookie_jar" -b "$cookie_jar" "$callback_url" >/dev/null
}

echo "=== INITIALIZING AUTHENTICATED SESSIONS FOR QA #61 ==="
OPERATOR_COOKIES="$TEMP_DIR/operator-cookies.txt"
login "testoperator" "$OPERATOR_COOKIES"
SESSION_JSON=$(curl -s -b "$OPERATOR_COOKIES" "$BFF_URL/api/session")
CSRF_TOKEN=$(echo "$SESSION_JSON" | jq -r ".csrfToken")

TENANTS=$(curl -s -b "$OPERATOR_COOKIES" "$BFF_URL/api/tenants")
TENANT_A=$(echo "$TENANTS" | jq -r ".[] | select(.displayName==\"QA Café Norte\") | .tenantId")
echo "Active Workspace A: QA Café Norte ($TENANT_A)"

TODAY=$(date +"%Y-%m-%d")
TOMORROW=$(date -d "+1 day" +"%Y-%m-%d" 2>/dev/null || date -v+1d +"%Y-%m-%d")
YESTERDAY=$(date -d "-1 day" +"%Y-%m-%d" 2>/dev/null || date -v-1d +"%Y-%m-%d")
PAST_TS=$(date -u -d "-2 hours" +"%Y-%m-%dT%H:%M:%SZ" 2>/dev/null || date -u -v-2H +"%Y-%m-%dT%H:%M:%SZ")

echo ""
echo "=== EXECUTING SCENARIO FAMILY 4: FOLLOW-UP ELIGIBILITY LIFECYCLE ==="

# 4.1 Create fresh candidate customer Rodrigo Castro Vera
RODRIGO_PHONE="9845$((RANDOM % 90000 + 10000))"
RODRIGO_RESP=$(curl -s -b "$OPERATOR_COOKIES" -X POST "$BFF_URL/api/customers" \
    -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT_A" -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
    -d "{\"displayName\": \"Rodrigo Castro Vera\", \"notes\": \"Cliente regular de cafe para filtro\", \"phones\": [{\"number\": \"$RODRIGO_PHONE\", \"region\": \"CL\", \"primary\": true}]}")
RODRIGO_ID=$(echo "$RODRIGO_RESP" | jq -r ".id")
RODRIGO_PHONE_ID=$(echo "$RODRIGO_RESP" | jq -r ".phones[0].id")

if [ -n "$RODRIGO_ID" ] && [ "$RODRIGO_ID" != "null" ]; then
    record_result "Fam4-Eligibility" "Create candidate customer" "201/Valid ID" "$RODRIGO_ID" "PASS" "Resolved Rodrigo ID"
else
    record_result "Fam4-Eligibility" "Create candidate customer" "Valid ID" "null" "FAIL" "Could not resolve Rodrigo"
fi

# 4.2 Grant WhatsApp consent with If-Match
CONSENT_GET=$(curl -s -i -b "$OPERATOR_COOKIES" -H "X-Tenant-Id: $TENANT_A" "$BFF_URL/api/customers/$RODRIGO_ID/contact-policy")
CONSENT_ETAG=$(echo "$CONSENT_GET" | grep -i "^etag:" | tr -d "\r\n" | awk "{print \$2}")

CONSENT_RESP=$(curl -s -b "$OPERATOR_COOKIES" -w "\n%{http_code}" -X PUT "$BFF_URL/api/customers/$RODRIGO_ID/contacts/$RODRIGO_PHONE_ID/consents/WHATSAPP" \
    -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT_A" -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
    -H "If-Match: $CONSENT_ETAG" \
    -d '{"status": "GRANTED", "source": "CUSTOMER_VERBAL"}')
CONSENT_HTTP=$(echo "$CONSENT_RESP" | tail -n 1)
record_result "Fam4-Eligibility" "Grant WhatsApp consent" "200" "$CONSENT_HTTP" "$([ "$CONSENT_HTTP" = "200" ] && echo "PASS" || echo "FAIL")" "Consent granted"

# 4.3 Initial eligibility check before purchase (must be INELIGIBLE with NO_PURCHASE_HISTORY)
ELIG_1=$(curl -s -b "$OPERATOR_COOKIES" -H "X-Tenant-Id: $TENANT_A" "$BFF_URL/api/customers/$RODRIGO_ID/follow-up-eligibility")
STATUS_1=$(echo "$ELIG_1" | jq -r ".status")
REASON_1=$(echo "$ELIG_1" | jq -r ".reasons[0]")
record_result "Fam4-Eligibility" "Initial eligibility without purchase is INELIGIBLE (NO_PURCHASE_HISTORY)" "INELIGIBLE" "$STATUS_1" "$([ "$STATUS_1" = "INELIGIBLE" ] && [ "$REASON_1" = "NO_PURCHASE_HISTORY" ] && echo "PASS" || echo "FAIL")" "Status: $STATUS_1, Reason: $REASON_1"

# 4.4 Record purchase with valid past timestamp
PURCHASE_RESP=$(curl -s -b "$OPERATOR_COOKIES" -w "\n%{http_code}" -X POST "$BFF_URL/api/customers/$RODRIGO_ID/purchases" \
    -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT_A" -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
    -H "Idempotency-Key: rodrigo-purchase-final-$RANDOM" \
    -d "{\"purchasedAt\": \"$PAST_TS\", \"description\": \"1kg Tostado Italiano\"}")
PURCHASE_HTTP=$(echo "$PURCHASE_RESP" | tail -n 1)
record_result "Fam4-Eligibility" "Record baseline purchase" "201" "$PURCHASE_HTTP" "$([ "$PURCHASE_HTTP" = "201" ] && echo "PASS" || echo "FAIL")" "Purchase recorded"

# 4.5 Eligibility becomes NOT_YET_DUE based on default cadence
ELIG_2=$(curl -s -b "$OPERATOR_COOKIES" -H "X-Tenant-Id: $TENANT_A" "$BFF_URL/api/customers/$RODRIGO_ID/follow-up-eligibility")
STATUS_2=$(echo "$ELIG_2" | jq -r ".status")
record_result "Fam4-Eligibility" "Post-purchase eligibility transitions to NOT_YET_DUE" "NOT_YET_DUE" "$STATUS_2" "$([ "$STATUS_2" = "NOT_YET_DUE" ] && echo "PASS" || echo "FAIL")" "Status: $STATUS_2"

# 4.6 Set explicit next date = today via follow-up-policy
POLICY_GET=$(curl -s -i -b "$OPERATOR_COOKIES" -H "X-Tenant-Id: $TENANT_A" "$BFF_URL/api/customers/$RODRIGO_ID/follow-up-policy")
POLICY_ETAG=$(echo "$POLICY_GET" | grep -i "^etag:" | tr -d "\r\n" | awk "{print \$2}")

POLICY_SET=$(curl -s -b "$OPERATOR_COOKIES" -w "\n%{http_code}" -X PUT "$BFF_URL/api/customers/$RODRIGO_ID/follow-up-policy" \
    -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT_A" -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
    -H "If-Match: $POLICY_ETAG" \
    -d "{\"cadenceDays\": 14, \"explicitNextDate\": \"$TODAY\"}")
POLICY_SET_HTTP=$(echo "$POLICY_SET" | tail -n 1)
record_result "Fam4-Eligibility" "Configure customer explicit next date = today (API override, UI BLOCKED)" "200" "$POLICY_SET_HTTP" "$([ "$POLICY_SET_HTTP" = "200" ] && echo "PASS" || echo "FAIL")" "Customer policy set to today"

# 4.7 Verify candidate appears in due queue with DUE / DUE_TODAY
QUEUE_PAGE=$(curl -s -b "$OPERATOR_COOKIES" -H "X-Tenant-Id: $TENANT_A" "$BFF_URL/api/follow-up-queue?status=DUE")
IN_QUEUE=$(echo "$QUEUE_PAGE" | jq -r ".items[] | select(.customerId==\"$RODRIGO_ID\") | .reasons[0]")
record_result "Fam4-Eligibility" "Due candidate appears in follow-up queue" "DUE_TODAY" "$IN_QUEUE" "$([ "$IN_QUEUE" = "DUE_TODAY" ] && echo "PASS" || echo "FAIL")" "Rodrigo is in queue with DUE_TODAY"

echo ""
echo "=== EXECUTING SCENARIO FAMILY 5: FOLLOW-UP DISPOSITION LIFECYCLE ==="

# 5.1 Snooze candidate Rodrigo to tomorrow
SNOOZE_GET=$(curl -s -i -b "$OPERATOR_COOKIES" -H "X-Tenant-Id: $TENANT_A" "$BFF_URL/api/customers/$RODRIGO_ID/follow-up-policy")
SNOOZE_ETAG=$(echo "$SNOOZE_GET" | grep -i "^etag:" | tr -d "\r\n" | awk "{print \$2}")

SNOOZE_RESP=$(curl -s -b "$OPERATOR_COOKIES" -w "\n%{http_code}" -X PUT "$BFF_URL/api/customers/$RODRIGO_ID/follow-up-snooze" \
    -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT_A" -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
    -H "If-Match: $SNOOZE_ETAG" \
    -d "{\"until\": \"$TOMORROW\"}")
SNOOZE_HTTP=$(echo "$SNOOZE_RESP" | tail -n 1)
record_result "Fam5-Disposition" "Snooze candidate to tomorrow" "200" "$SNOOZE_HTTP" "$([ "$SNOOZE_HTTP" = "200" ] && echo "PASS" || echo "FAIL")" "Candidate snoozed"

# 5.2 Verify candidate dropped from today's due queue
QUEUE_AFTER_SNOOZE=$(curl -s -b "$OPERATOR_COOKIES" -H "X-Tenant-Id: $TENANT_A" "$BFF_URL/api/follow-up-queue?status=DUE")
IN_QUEUE_AFTER_SNOOZE=$(echo "$QUEUE_AFTER_SNOOZE" | jq -r ".items[] | select(.customerId==\"$RODRIGO_ID\") | .customerId")
record_result "Fam5-Disposition" "Candidate dropped from today's due queue after snooze" "DROPPED" "$([ -z "$IN_QUEUE_AFTER_SNOOZE" ] && echo "DROPPED" || echo "STILL_IN_QUEUE")" "$([ -z "$IN_QUEUE_AFTER_SNOOZE" ] && echo "PASS" || echo "FAIL")" "Dropped from queue"

# 5.3 Provision candidate Valentina Lagos Ríos for Dismissal lifecycle
VALENTINA_PHONE="9856$((RANDOM % 90000 + 10000))"
VALENTINA_RESP=$(curl -s -b "$OPERATOR_COOKIES" -X POST "$BFF_URL/api/customers" \
    -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT_A" -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
    -d "{\"displayName\": \"Valentina Lagos Ríos\", \"notes\": \"Cliente candidata para desestimacion\", \"phones\": [{\"number\": \"$VALENTINA_PHONE\", \"region\": \"CL\", \"primary\": true}]}")
VALENTINA_ID=$(echo "$VALENTINA_RESP" | jq -r ".id")
VALENTINA_PHONE_ID=$(echo "$VALENTINA_RESP" | jq -r ".phones[0].id")

VAL_CONSENT_GET=$(curl -s -i -b "$OPERATOR_COOKIES" -H "X-Tenant-Id: $TENANT_A" "$BFF_URL/api/customers/$VALENTINA_ID/contact-policy")
VAL_CONSENT_ETAG=$(echo "$VAL_CONSENT_GET" | grep -i "^etag:" | tr -d "\r\n" | awk "{print \$2}")
curl -s -b "$OPERATOR_COOKIES" -X PUT "$BFF_URL/api/customers/$VALENTINA_ID/contacts/$VALENTINA_PHONE_ID/consents/WHATSAPP" \
    -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT_A" -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
    -H "If-Match: $VAL_CONSENT_ETAG" \
    -d '{"status": "GRANTED", "source": "CUSTOMER_VERBAL"}' >/dev/null

curl -s -b "$OPERATOR_COOKIES" -X POST "$BFF_URL/api/customers/$VALENTINA_ID/purchases" \
    -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT_A" -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
    -H "Idempotency-Key: val-purchase-$RANDOM" \
    -d "{\"purchasedAt\": \"$PAST_TS\", \"description\": \"Cafe en grano 500g\"}" >/dev/null

VAL_POL_GET=$(curl -s -i -b "$OPERATOR_COOKIES" -H "X-Tenant-Id: $TENANT_A" "$BFF_URL/api/customers/$VALENTINA_ID/follow-up-policy")
VAL_POL_ETAG=$(echo "$VAL_POL_GET" | grep -i "^etag:" | tr -d "\r\n" | awk "{print \$2}")
curl -s -b "$OPERATOR_COOKIES" -X PUT "$BFF_URL/api/customers/$VALENTINA_ID/follow-up-policy" \
    -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT_A" -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
    -H "If-Match: $VAL_POL_ETAG" \
    -d "{\"cadenceDays\": 14, \"explicitNextDate\": \"$TODAY\"}" >/dev/null

# 5.4 Dismissal disposition on Valentina (currently DUE)
VAL_POL_GET2=$(curl -s -i -b "$OPERATOR_COOKIES" -H "X-Tenant-Id: $TENANT_A" "$BFF_URL/api/customers/$VALENTINA_ID/follow-up-policy")
VAL_POL_ETAG2=$(echo "$VAL_POL_GET2" | grep -i "^etag:" | tr -d "\r\n" | awk "{print \$2}")

DISMISS_KEY="valentina-dismissal-$RANDOM"
DISMISS_RESP=$(curl -s -b "$OPERATOR_COOKIES" -w "\n%{http_code}" -X POST "$BFF_URL/api/customers/$VALENTINA_ID/follow-up-dismissals" \
    -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT_A" -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
    -H "If-Match: $VAL_POL_ETAG2" -H "Idempotency-Key: $DISMISS_KEY" \
    -d '{"notes": "Cliente indico tener cafe suficiente para las proximas 2 semanas"}')
DISMISS_HTTP=$(echo "$DISMISS_RESP" | tail -n 1)
record_result "Fam5-Disposition" "Dismissal disposition submitted" "201" "$DISMISS_HTTP" "$([ "$DISMISS_HTTP" = "201" ] && echo "PASS" || echo "FAIL")" "Dismissal saved"

# 5.5 Replay dismissal with same idempotency key
REPLAY_DISMISS=$(curl -s -b "$OPERATOR_COOKIES" -w "\n%{http_code}" -X POST "$BFF_URL/api/customers/$VALENTINA_ID/follow-up-dismissals" \
    -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT_A" -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
    -H "If-Match: $VAL_POL_ETAG2" -H "Idempotency-Key: $DISMISS_KEY" \
    -d '{"notes": "Cliente indico tener cafe suficiente para las proximas 2 semanas"}')
REPLAY_DISMISS_HTTP=$(echo "$REPLAY_DISMISS" | tail -n 1)
record_result "Fam5-Disposition" "Dismissal idempotency replay" "200" "$REPLAY_DISMISS_HTTP" "$([ "$REPLAY_DISMISS_HTTP" = "200" ] && echo "PASS" || echo "FAIL")" "Idempotent response"

# 5.6 Record manual follow-up disposition on Rodrigo
POL_GET5=$(curl -s -i -b "$OPERATOR_COOKIES" -H "X-Tenant-Id: $TENANT_A" "$BFF_URL/api/customers/$RODRIGO_ID/follow-up-policy")
ETAG5=$(echo "$POL_GET5" | grep -i "^etag:" | tr -d "\r\n" | awk "{print \$2}")

MANUAL_KEY="rodrigo-manual-$RANDOM"
MANUAL_RESP=$(curl -s -b "$OPERATOR_COOKIES" -w "\n%{http_code}" -X POST "$BFF_URL/api/customers/$RODRIGO_ID/manual-follow-ups" \
    -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT_A" -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
    -H "If-Match: $ETAG5" -H "Idempotency-Key: $MANUAL_KEY" \
    -d '{"notes": "Contacto telefonico exitoso. Cliente acordo pedido para fin de mes."}')
MANUAL_HTTP=$(echo "$MANUAL_RESP" | tail -n 1)
record_result "Fam5-Disposition" "Manual follow-up disposition submitted" "201" "$MANUAL_HTTP" "$([ "$MANUAL_HTTP" = "201" ] && echo "PASS" || echo "FAIL")" "Manual follow-up recorded"

# 5.7 Replay manual follow-up with same idempotency key
REPLAY_MANUAL=$(curl -s -b "$OPERATOR_COOKIES" -w "\n%{http_code}" -X POST "$BFF_URL/api/customers/$RODRIGO_ID/manual-follow-ups" \
    -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT_A" -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
    -H "If-Match: $ETAG5" -H "Idempotency-Key: $MANUAL_KEY" \
    -d '{"notes": "Contacto telefonico exitoso. Cliente acordo pedido para fin de mes."}')
REPLAY_MANUAL_HTTP=$(echo "$REPLAY_MANUAL" | tail -n 1)
record_result "Fam5-Disposition" "Manual follow-up idempotency replay" "200" "$REPLAY_MANUAL_HTTP" "$([ "$REPLAY_MANUAL_HTTP" = "200" ] && echo "PASS" || echo "FAIL")" "Idempotent response"

# 5.8 Concurrency / Stale state rejection: Revoke consent then attempt disposition
POL_GET6=$(curl -s -i -b "$OPERATOR_COOKIES" -H "X-Tenant-Id: $TENANT_A" "$BFF_URL/api/customers/$RODRIGO_ID/follow-up-policy")
ETAG6=$(echo "$POL_GET6" | grep -i "^etag:" | tr -d "\r\n" | awk "{print \$2}")

# Revoke consent
CONSENT_GET6=$(curl -s -i -b "$OPERATOR_COOKIES" -H "X-Tenant-Id: $TENANT_A" "$BFF_URL/api/customers/$RODRIGO_ID/contact-policy")
CONSENT_ETAG6=$(echo "$CONSENT_GET6" | grep -i "^etag:" | tr -d "\r\n" | awk "{print \$2}")

curl -s -b "$OPERATOR_COOKIES" -X PUT "$BFF_URL/api/customers/$RODRIGO_ID/contacts/$RODRIGO_PHONE_ID/consents/WHATSAPP" \
    -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT_A" -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
    -H "If-Match: $CONSENT_ETAG6" \
    -d '{"status": "REVOKED", "source": "CUSTOMER_VERBAL"}' >/dev/null

# Attempt disposition on now-ineligible customer with prior ETag
STALE_MANUAL=$(curl -s -b "$OPERATOR_COOKIES" -w "\n%{http_code}" -X POST "$BFF_URL/api/customers/$RODRIGO_ID/manual-follow-ups" \
    -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT_A" -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
    -H "If-Match: $ETAG6" -H "Idempotency-Key: rodrigo-stale-$RANDOM" \
    -d '{"notes": "Intento de seguimiento tras revocacion"}')
STALE_HTTP=$(echo "$STALE_MANUAL" | tail -n 1)
record_result "Fam5-Disposition" "Disposition on ineligible/stale candidate rejected" "409" "$STALE_HTTP" "$([ "$STALE_HTTP" = "409" ] && echo "PASS" || echo "FAIL")" "409 Conflict fail-closed"

echo ""
echo "=== EXECUTING SCENARIO FAMILY 6: CROSS-TENANT FEATURE JOURNEY ==="

TENANT_B=$(echo "$TENANTS" | jq -r ".[] | select(.displayName==\"QA 61 Mercado Austral\") | .tenantId")
echo "Active Workspace B: QA 61 Mercado Austral ($TENANT_B)"

# 6.1 Create or lookup synthetic customer in Tenant A
CUST_A_PHONE="986789099"
CUST_A_LOOKUP=$(curl -s -b "$OPERATOR_COOKIES" -H "X-Tenant-Id: $TENANT_A" "$BFF_URL/api/customers?phone=$CUST_A_PHONE&region=CL")
CUST_A_ID=$(echo "$CUST_A_LOOKUP" | jq -r ".customers[0].id // empty")
if [ -z "$CUST_A_ID" ]; then
    CUST_A_RESP=$(curl -s -b "$OPERATOR_COOKIES" -X POST "$BFF_URL/api/customers" \
        -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT_A" -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
        -d "{\"displayName\": \"Ignacio Paredes\", \"notes\": \"Cliente en Tenant A\", \"phones\": [{\"number\": \"$CUST_A_PHONE\", \"region\": \"CL\", \"primary\": true}]}")
    CUST_A_ID=$(echo "$CUST_A_RESP" | jq -r ".id")
fi

# 6.2 Create or lookup customer with IDENTICAL phone in Tenant B
CUST_B_LOOKUP=$(curl -s -b "$OPERATOR_COOKIES" -H "X-Tenant-Id: $TENANT_B" "$BFF_URL/api/customers?phone=$CUST_A_PHONE&region=CL")
CUST_B_ID=$(echo "$CUST_B_LOOKUP" | jq -r ".customers[0].id // empty")
if [ -z "$CUST_B_ID" ]; then
    CUST_B_RESP=$(curl -s -b "$OPERATOR_COOKIES" -X POST "$BFF_URL/api/customers" \
        -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT_B" -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
        -d "{\"displayName\": \"Ignacio Paredes\", \"notes\": \"Cliente en Tenant B\", \"phones\": [{\"number\": \"$CUST_A_PHONE\", \"region\": \"CL\", \"primary\": true}]}")
    CUST_B_ID=$(echo "$CUST_B_RESP" | jq -r ".id")
fi
record_result "Fam6-CrossTenant" "Identical phone allowed in distinct workspaces" "Distinct IDs" "$([ "$CUST_A_ID" != "$CUST_B_ID" ] && echo "Distinct IDs" || echo "Same ID")" "$([ "$CUST_A_ID" != "$CUST_B_ID" ] && echo "PASS" || echo "FAIL")" "Tenant A ID: $CUST_A_ID, Tenant B ID: $CUST_B_ID"

# 6.3 Query Tenant B with Tenant A customer ID (cross-tenant leakage probe)
PROBE_CROSS=$(curl -s -b "$OPERATOR_COOKIES" -w "\n%{http_code}" -H "X-Tenant-Id: $TENANT_B" "$BFF_URL/api/customers/$CUST_A_ID")
PROBE_HTTP=$(echo "$PROBE_CROSS" | tail -n 1)
record_result "Fam6-CrossTenant" "Accessing foreign customer ID fails closed" "404" "$PROBE_HTTP" "$([ "$PROBE_HTTP" = "404" ] && echo "PASS" || echo "FAIL")" "404 Not Found without leakage"

# 6.4 Query Tenant B customer list: verify only Tenant B data is returned
LIST_B=$(curl -s -b "$OPERATOR_COOKIES" -H "X-Tenant-Id: $TENANT_B" "$BFF_URL/api/customers")
COUNT_B=$(echo "$LIST_B" | jq -r ".customers | length")
IN_B=$(echo "$LIST_B" | jq -r ".customers[] | select(.id==\"$CUST_A_ID\") | .id")
record_result "Fam6-CrossTenant" "Tenant A customer never leaks into Tenant B list" "NOT_PRESENT" "$([ -z "$IN_B" ] && echo "NOT_PRESENT" || echo "LEAKED")" "$([ -z "$IN_B" ] && echo "PASS" || echo "FAIL")" "Tenant B customer count: $COUNT_B"

# 6.5 Query Tenant B follow-up queue: verify completely isolated
QUEUE_B=$(curl -s -b "$OPERATOR_COOKIES" -H "X-Tenant-Id: $TENANT_B" "$BFF_URL/api/follow-up-queue")
QUEUE_B_COUNT=$(echo "$QUEUE_B" | jq -r ".items | length")
record_result "Fam6-CrossTenant" "Tenant B queue isolated from Tenant A candidates" "0" "$QUEUE_B_COUNT" "$([ "$QUEUE_B_COUNT" -eq 0 ] && echo "PASS" || echo "FAIL")" "0 queue items in Tenant B"

echo ""
echo "=== EXECUTING SCENARIO FAMILY 8: LONG-RUNNING OPERATOR JOURNEY ==="

# 8.1 Create or lookup Mateo San Martin in Tenant A
MATEO_PHONE="987890199"
MATEO_LOOKUP=$(curl -s -b "$OPERATOR_COOKIES" -H "X-Tenant-Id: $TENANT_A" "$BFF_URL/api/customers?phone=$MATEO_PHONE&region=CL")
MATEO_ID=$(echo "$MATEO_LOOKUP" | jq -r ".customers[0].id // empty")
if [ -z "$MATEO_ID" ]; then
    MATEO_RESP=$(curl -s -b "$OPERATOR_COOKIES" -X POST "$BFF_URL/api/customers" \
        -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT_A" -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
        -d "{\"displayName\": \"Mateo San Martin\", \"notes\": \"Cliente para jornada continua con logout/login\", \"phones\": [{\"number\": \"$MATEO_PHONE\", \"region\": \"CL\", \"primary\": true}]}")
    MATEO_ID=$(echo "$MATEO_RESP" | jq -r ".id")
    MATEO_PHONE_ID=$(echo "$MATEO_RESP" | jq -r ".phones[0].id")
else
    MATEO_PHONE_ID=$(echo "$MATEO_LOOKUP" | jq -r ".customers[0].phones[0].id")
fi

# 8.2 Grant WhatsApp consent
MATEO_CONSENT_GET=$(curl -s -i -b "$OPERATOR_COOKIES" -H "X-Tenant-Id: $TENANT_A" "$BFF_URL/api/customers/$MATEO_ID/contact-policy")
MATEO_CONSENT_ETAG=$(echo "$MATEO_CONSENT_GET" | grep -i "^etag:" | tr -d "\r\n" | awk "{print \$2}")

curl -s -b "$OPERATOR_COOKIES" -X PUT "$BFF_URL/api/customers/$MATEO_ID/contacts/$MATEO_PHONE_ID/consents/WHATSAPP" \
    -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT_A" -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
    -H "If-Match: $MATEO_CONSENT_ETAG" \
    -d '{"status": "GRANTED", "source": "CUSTOMER_WRITTEN"}' >/dev/null

# 8.3 Record purchase
curl -s -b "$OPERATOR_COOKIES" -X POST "$BFF_URL/api/customers/$MATEO_ID/purchases" \
    -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT_A" -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
    -H "Idempotency-Key: mateo-purchase-final-$RANDOM" \
    -d "{\"purchasedAt\": \"$PAST_TS\", \"description\": \"2.5kg Blend Especial de la Casa\"}" >/dev/null

# 8.4 Set follow-up policy to due today
MATEO_POL_GET=$(curl -s -i -b "$OPERATOR_COOKIES" -H "X-Tenant-Id: $TENANT_A" "$BFF_URL/api/customers/$MATEO_ID/follow-up-policy")
MATEO_ETAG=$(echo "$MATEO_POL_GET" | grep -i "^etag:" | tr -d "\r\n" | awk "{print \$2}")
curl -s -b "$OPERATOR_COOKIES" -X PUT "$BFF_URL/api/customers/$MATEO_ID/follow-up-policy" \
    -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT_A" -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
    -H "If-Match: $MATEO_ETAG" -d "{\"cadenceDays\": 14, \"explicitNextDate\": \"$TODAY\"}" >/dev/null

# 8.5 Record manual follow-up disposition
MATEO_POL_GET2=$(curl -s -i -b "$OPERATOR_COOKIES" -H "X-Tenant-Id: $TENANT_A" "$BFF_URL/api/customers/$MATEO_ID/follow-up-policy")
MATEO_ETAG2=$(echo "$MATEO_POL_GET2" | grep -i "^etag:" | tr -d "\r\n" | awk "{print \$2}")
curl -s -b "$OPERATOR_COOKIES" -X POST "$BFF_URL/api/customers/$MATEO_ID/manual-follow-ups" \
    -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT_A" -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
    -H "If-Match: $MATEO_ETAG2" -H "Idempotency-Key: mateo-disposition-$RANDOM" \
    -d '{"notes": "Seguimiento manual realizado. Cliente recompro en tienda fisica."}' >/dev/null

# 8.6 Logout operator session
curl -s -b "$OPERATOR_COOKIES" -X POST "$BFF_URL/logout" -H "X-CSRF-TOKEN: $CSRF_TOKEN" >/dev/null
rm -f "$OPERATOR_COOKIES"

# Verify session is dead
LOGOUT_CHECK=$(curl -s -o /dev/null -w "%{http_code}" "$BFF_URL/api/session")
record_result "Fam8-LongRunning" "Session terminated after logout" "401" "$LOGOUT_CHECK" "$([ "$LOGOUT_CHECK" = "401" ] && echo "PASS" || echo "FAIL")" "Logout successful"

# 8.7 New login session as testoperator
NEW_COOKIES="$TEMP_DIR/new-cookies.txt"
login "testoperator" "$NEW_COOKIES"

# 8.8 Verify persisted authoritative state for Mateo San Martin
MATEO_FETCH=$(curl -s -b "$NEW_COOKIES" -H "X-Tenant-Id: $TENANT_A" "$BFF_URL/api/customers/$MATEO_ID")
MATEO_NAME=$(echo "$MATEO_FETCH" | jq -r ".displayName")
record_result "Fam8-LongRunning" "Customer entity persisted post-relogin" "Mateo San Martin" "$MATEO_NAME" "$([ "$MATEO_NAME" = "Mateo San Martin" ] && echo "PASS" || echo "FAIL")" "Customer intact"

MATEO_PURCHASES=$(curl -s -b "$NEW_COOKIES" -H "X-Tenant-Id: $TENANT_A" "$BFF_URL/api/customers/$MATEO_ID/purchases")
MATEO_P_COUNT=$(echo "$MATEO_PURCHASES" | jq -r ".purchases | length")
record_result "Fam8-LongRunning" "Purchases history persisted post-relogin" "1+" "$MATEO_P_COUNT" "$([ "$MATEO_P_COUNT" -ge 1 ] && echo "PASS" || echo "FAIL")" "$MATEO_P_COUNT purchases intact"

MATEO_CONSENT=$(curl -s -b "$NEW_COOKIES" -H "X-Tenant-Id: $TENANT_A" "$BFF_URL/api/customers/$MATEO_ID/contact-policy")
MATEO_CONSENT_STATUS=$(echo "$MATEO_CONSENT" | jq -r ".consents[] | select(.channel==\"WHATSAPP\") | .status")
record_result "Fam8-LongRunning" "WhatsApp consent persisted post-relogin" "GRANTED" "$MATEO_CONSENT_STATUS" "$([ "$MATEO_CONSENT_STATUS" = "GRANTED" ] && echo "PASS" || echo "FAIL")" "Consent GRANTED intact"

echo ""
echo "======================================================================"
echo "QA #61 VERIFICATION SUMMARY: $PASSED_TESTS / $TOTAL_TESTS PASSED ($FAILED_TESTS FAILED)"
echo "======================================================================"
