#!/usr/bin/env bash
# ==============================================================================
# verify-issue-61-focused-followup.sh
#
# Exhaustive Verification for Maintainer Review on Issue #61:
# Covers Scenarios 1 through 7:
# 1. Complete consent / do-not-contact lifecycle
# 2. Contact identity change after consent history exists
# 3. Cross-session/tab stale consent state
# 4. Purchase lifecycle gaps (correction, void latest, recalculation, idempotency)
# 5. Follow-up eligibility invalidation after candidate becomes due (archival & new purchase)
# 6. Cross-workspace stale form/detail mutation
# 7. First-use / empty-state completeness (no purchases, no consent history)
# ==============================================================================

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEMP_DIR="$(mktemp -d /tmp/dokene-focused-qa-61.XXXXXX)"

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

RESULTS_FILE="$TEMP_DIR/focused-results.tsv"
echo -e "Scenario\tAssertion\tExpected\tObserved\tStatus\tDetails" > "$RESULTS_FILE"

record_result() {
    local scn="$1"
    local name="$2"
    local exp="$3"
    local obs="$4"
    local status="$5"
    local details="$6"

    TOTAL_TESTS=$((TOTAL_TESTS + 1))
    if [ "$status" = "PASS" ]; then
        PASSED_TESTS=$((PASSED_TESTS + 1))
        echo "  [PASS] $scn :: $name"
    else
        FAILED_TESTS=$((FAILED_TESTS + 1))
        echo "  [FAIL] $scn :: $name (Expected $exp, got $obs. Details: $details)"
    fi
    echo -e "$scn\t$name\t$exp\t$obs\t$status\t$details" >> "$RESULTS_FILE"
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

echo "=== INITIALIZING AUTHENTICATED SESSIONS FOR FOCUSED QA #61 ==="
OPERATOR_COOKIES="$TEMP_DIR/operator-cookies.txt"
login "testoperator" "$OPERATOR_COOKIES"
SESSION_JSON=$(curl -s -b "$OPERATOR_COOKIES" "$BFF_URL/api/session")
CSRF_TOKEN=$(echo "$SESSION_JSON" | jq -r ".csrfToken")

TENANTS=$(curl -s -b "$OPERATOR_COOKIES" "$BFF_URL/api/tenants")
TENANT_A=$(echo "$TENANTS" | jq -r ".[] | select(.displayName==\"QA Café Norte\") | .tenantId")
TENANT_B=$(echo "$TENANTS" | jq -r ".[] | select(.displayName==\"QA 61 Mercado Austral\") | .tenantId")
echo "Workspace A: QA Café Norte ($TENANT_A)"
echo "Workspace B: QA 61 Mercado Austral ($TENANT_B)"

TODAY=$(date +"%Y-%m-%d")
TOMORROW=$(date -d "+1 day" +"%Y-%m-%d" 2>/dev/null || date -v+1d +"%Y-%m-%d")
YESTERDAY=$(date -d "-1 day" +"%Y-%m-%d" 2>/dev/null || date -v-1d +"%Y-%m-%d")
PAST_TS=$(date -u -d "-2 hours" +"%Y-%m-%dT%H:%M:%SZ" 2>/dev/null || date -u -v-2H +"%Y-%m-%dT%H:%M:%SZ")
OLD_PAST_TS=$(date -u -d "-35 days" +"%Y-%m-%dT%H:%M:%SZ" 2>/dev/null || date -u -v-35d +"%Y-%m-%dT%H:%M:%SZ")

echo ""
echo "=== 1. COMPLETE CONSENT / DO-NOT-CONTACT LIFECYCLE ==="

# 1.1 Fresh customer creation: consent is UNKNOWN / NOT_GRANTED
LUCIA_PHONE="98$((RANDOM % 9000000 + 1000000))"
LUCIA_RESP=$(curl -s -b "$OPERATOR_COOKIES" -X POST "$BFF_URL/api/customers" \
    -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT_A" -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
    -d "{\"displayName\": \"Lucía Menéndez\", \"notes\": \"Ciclo completo consentimiento\", \"phones\": [{\"number\": \"$LUCIA_PHONE\", \"region\": \"CL\", \"primary\": true}]}")
LUCIA_ID=$(echo "$LUCIA_RESP" | jq -r ".id")
LUCIA_PHONE_ID=$(echo "$LUCIA_RESP" | jq -r ".phones[0].id")

# Baseline eligibility: without consent, status must be INELIGIBLE (NO_ELIGIBLE_CONTACT)
ELIG_1_1=$(curl -s -b "$OPERATOR_COOKIES" -H "X-Tenant-Id: $TENANT_A" "$BFF_URL/api/customers/$LUCIA_ID/follow-up-eligibility")
STATUS_1_1=$(echo "$ELIG_1_1" | jq -r ".status")
REASON_1_1=$(echo "$ELIG_1_1" | jq -r ".reasons[0]")
record_result "1.ConsentLifecycle" "1.1 Initial state without consent is INELIGIBLE (NO_ELIGIBLE_CONTACT)" "INELIGIBLE:NO_ELIGIBLE_CONTACT" "$STATUS_1_1:$REASON_1_1" "$([ "$STATUS_1_1" = "INELIGIBLE" ] && [ "$REASON_1_1" = "NO_ELIGIBLE_CONTACT" ] && echo "PASS" || echo "FAIL")" "Initial consent ungranted"

# 1.2 Transition: NOT_GRANTED -> GRANTED
POL_1_2=$(curl -s -i -b "$OPERATOR_COOKIES" -H "X-Tenant-Id: $TENANT_A" "$BFF_URL/api/customers/$LUCIA_ID/contact-policy")
ETAG_1_2=$(echo "$POL_1_2" | grep -i "^etag:" | tr -d "\r\n" | awk "{print \$2}")
curl -s -b "$OPERATOR_COOKIES" -X PUT "$BFF_URL/api/customers/$LUCIA_ID/contacts/$LUCIA_PHONE_ID/consents/WHATSAPP" \
    -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT_A" -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
    -H "If-Match: $ETAG_1_2" -d '{"status": "GRANTED", "source": "CUSTOMER_VERBAL"}' >/dev/null

# Add purchase so cadence can be evaluated
curl -s -b "$OPERATOR_COOKIES" -X POST "$BFF_URL/api/customers/$LUCIA_ID/purchases" \
    -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT_A" -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
    -H "Idempotency-Key: lucia-p1-$RANDOM" -d "{\"purchasedAt\": \"$PAST_TS\", \"description\": \"Café Especial\"}" >/dev/null

ELIG_1_2=$(curl -s -b "$OPERATOR_COOKIES" -H "X-Tenant-Id: $TENANT_A" "$BFF_URL/api/customers/$LUCIA_ID/follow-up-eligibility")
STATUS_1_2=$(echo "$ELIG_1_2" | jq -r ".status")
record_result "1.ConsentLifecycle" "1.2 Post-grant + purchase transitions to NOT_YET_DUE" "NOT_YET_DUE" "$STATUS_1_2" "$([ "$STATUS_1_2" = "NOT_YET_DUE" ] && echo "PASS" || echo "FAIL")" "Eligible on WhatsApp"

# 1.3 Transition: GRANTED -> REVOKED
POL_1_3=$(curl -s -i -b "$OPERATOR_COOKIES" -H "X-Tenant-Id: $TENANT_A" "$BFF_URL/api/customers/$LUCIA_ID/contact-policy")
ETAG_1_3=$(echo "$POL_1_3" | grep -i "^etag:" | tr -d "\r\n" | awk "{print \$2}")
curl -s -b "$OPERATOR_COOKIES" -X PUT "$BFF_URL/api/customers/$LUCIA_ID/contacts/$LUCIA_PHONE_ID/consents/WHATSAPP" \
    -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT_A" -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
    -H "If-Match: $ETAG_1_3" -d '{"status": "REVOKED", "source": "CUSTOMER_VERBAL"}' >/dev/null

ELIG_1_3=$(curl -s -b "$OPERATOR_COOKIES" -H "X-Tenant-Id: $TENANT_A" "$BFF_URL/api/customers/$LUCIA_ID/follow-up-eligibility")
STATUS_1_3=$(echo "$ELIG_1_3" | jq -r ".status")
REASON_1_3=$(echo "$ELIG_1_3" | jq -r ".reasons[0]")
record_result "1.ConsentLifecycle" "1.3 Post-revocation transitions to INELIGIBLE (NO_ELIGIBLE_CONTACT)" "INELIGIBLE:NO_ELIGIBLE_CONTACT" "$STATUS_1_3:$REASON_1_3" "$([ "$STATUS_1_3" = "INELIGIBLE" ] && [ "$REASON_1_3" = "NO_ELIGIBLE_CONTACT" ] && echo "PASS" || echo "FAIL")" "Revocation drops eligibility"

# 1.4 Transition: Enable DO_NOT_CONTACT
POL_1_4=$(curl -s -i -b "$OPERATOR_COOKIES" -H "X-Tenant-Id: $TENANT_A" "$BFF_URL/api/customers/$LUCIA_ID/contact-policy")
ETAG_1_4=$(echo "$POL_1_4" | grep -i "^etag:" | tr -d "\r\n" | awk "{print \$2}")
curl -s -b "$OPERATOR_COOKIES" -X PUT "$BFF_URL/api/customers/$LUCIA_ID/do-not-contact" \
    -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT_A" -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
    -H "If-Match: $ETAG_1_4" -d '{"enabled": true, "source": "CUSTOMER_VERBAL"}' >/dev/null

ELIG_1_4=$(curl -s -b "$OPERATOR_COOKIES" -H "X-Tenant-Id: $TENANT_A" "$BFF_URL/api/customers/$LUCIA_ID/follow-up-eligibility")
STATUS_1_4=$(echo "$ELIG_1_4" | jq -r ".status")
DNC_REASON_FOUND=$(echo "$ELIG_1_4" | jq -r '.reasons | index("DO_NOT_CONTACT")')
record_result "1.ConsentLifecycle" "1.4 DNC enabled adds DO_NOT_CONTACT hard reason" "HAS_DNC" "$([ "$DNC_REASON_FOUND" != "null" ] && echo "HAS_DNC" || echo "MISSING_DNC")" "$([ "$DNC_REASON_FOUND" != "null" ] && echo "PASS" || echo "FAIL")" "Status: $STATUS_1_4"

# 1.5 Transition: Clear DNC while consent remains REVOKED
POL_1_5=$(curl -s -i -b "$OPERATOR_COOKIES" -H "X-Tenant-Id: $TENANT_A" "$BFF_URL/api/customers/$LUCIA_ID/contact-policy")
ETAG_1_5=$(echo "$POL_1_5" | grep -i "^etag:" | tr -d "\r\n" | awk "{print \$2}")
curl -s -b "$OPERATOR_COOKIES" -X PUT "$BFF_URL/api/customers/$LUCIA_ID/do-not-contact" \
    -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT_A" -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
    -H "If-Match: $ETAG_1_5" -d '{"enabled": false, "source": "CUSTOMER_VERBAL"}' >/dev/null

ELIG_1_5=$(curl -s -b "$OPERATOR_COOKIES" -H "X-Tenant-Id: $TENANT_A" "$BFF_URL/api/customers/$LUCIA_ID/follow-up-eligibility")
STATUS_1_5=$(echo "$ELIG_1_5" | jq -r ".status")
REASON_1_5=$(echo "$ELIG_1_5" | jq -r ".reasons[0]")
record_result "1.ConsentLifecycle" "1.5 Clear DNC leaves candidate INELIGIBLE if consent is still REVOKED" "INELIGIBLE:NO_ELIGIBLE_CONTACT" "$STATUS_1_5:$REASON_1_5" "$([ "$STATUS_1_5" = "INELIGIBLE" ] && [ "$REASON_1_5" = "NO_ELIGIBLE_CONTACT" ] && echo "PASS" || echo "FAIL")" "Consent remains revoked"

# 1.6 Transition: Re-grant WhatsApp consent
POL_1_6=$(curl -s -i -b "$OPERATOR_COOKIES" -H "X-Tenant-Id: $TENANT_A" "$BFF_URL/api/customers/$LUCIA_ID/contact-policy")
ETAG_1_6=$(echo "$POL_1_6" | grep -i "^etag:" | tr -d "\r\n" | awk "{print \$2}")
curl -s -b "$OPERATOR_COOKIES" -X PUT "$BFF_URL/api/customers/$LUCIA_ID/contacts/$LUCIA_PHONE_ID/consents/WHATSAPP" \
    -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT_A" -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
    -H "If-Match: $ETAG_1_6" -d '{"status": "GRANTED", "source": "CUSTOMER_WRITTEN"}' >/dev/null

ELIG_1_6=$(curl -s -b "$OPERATOR_COOKIES" -H "X-Tenant-Id: $TENANT_A" "$BFF_URL/api/customers/$LUCIA_ID/follow-up-eligibility")
STATUS_1_6=$(echo "$ELIG_1_6" | jq -r ".status")
record_result "1.ConsentLifecycle" "1.6 Re-grant restores eligibility to NOT_YET_DUE" "NOT_YET_DUE" "$STATUS_1_6" "$([ "$STATUS_1_6" = "NOT_YET_DUE" ] && echo "PASS" || echo "FAIL")" "Re-grant restored eligibility"

echo ""
echo "=== 2. CONTACT IDENTITY CHANGE AFTER CONSENT HISTORY EXISTS ==="

# 2.1 Create Joaquín with Phone A, grant WhatsApp, record purchase
JOAQUIN_PHONE_A="98$((RANDOM % 9000000 + 1000000))"
JOAQUIN_RESP=$(curl -s -b "$OPERATOR_COOKIES" -X POST "$BFF_URL/api/customers" \
    -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT_A" -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
    -d "{\"displayName\": \"Joaquín Morales\", \"notes\": \"Prueba cambio de contacto\", \"phones\": [{\"number\": \"$JOAQUIN_PHONE_A\", \"region\": \"CL\", \"primary\": true}]}")
JOAQUIN_ID=$(echo "$JOAQUIN_RESP" | jq -r ".id")
JOAQUIN_PHONE_A_ID=$(echo "$JOAQUIN_RESP" | jq -r ".phones[0].id")
JOAQUIN_CUST_VER=$(echo "$JOAQUIN_RESP" | jq -r ".version")

# Grant consent to Phone A
POL_2_1=$(curl -s -i -b "$OPERATOR_COOKIES" -H "X-Tenant-Id: $TENANT_A" "$BFF_URL/api/customers/$JOAQUIN_ID/contact-policy")
ETAG_2_1=$(echo "$POL_2_1" | grep -i "^etag:" | tr -d "\r\n" | awk "{print \$2}")
curl -s -b "$OPERATOR_COOKIES" -X PUT "$BFF_URL/api/customers/$JOAQUIN_ID/contacts/$JOAQUIN_PHONE_A_ID/consents/WHATSAPP" \
    -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT_A" -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
    -H "If-Match: $ETAG_2_1" -d '{"status": "GRANTED", "source": "CUSTOMER_VERBAL"}' >/dev/null

curl -s -b "$OPERATOR_COOKIES" -X POST "$BFF_URL/api/customers/$JOAQUIN_ID/purchases" \
    -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT_A" -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
    -H "Idempotency-Key: joaquin-p-$RANDOM" -d "{\"purchasedAt\": \"$PAST_TS\", \"description\": \"Café Especial\"}" >/dev/null

# 2.2 Replace Phone A with Phone B via customer update
JOAQUIN_PHONE_B="98$((RANDOM % 9000000 + 1000000))"
UPDATE_RESP=$(curl -s -b "$OPERATOR_COOKIES" -X PUT "$BFF_URL/api/customers/$JOAQUIN_ID" \
    -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT_A" -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
    -H "If-Match: \"$JOAQUIN_CUST_VER\"" \
    -d "{\"displayName\": \"Joaquín Morales\", \"notes\": \"Teléfono actualizado\", \"phones\": [{\"number\": \"$JOAQUIN_PHONE_B\", \"region\": \"CL\", \"primary\": true}]}")
JOAQUIN_PHONE_B_ID=$(echo "$UPDATE_RESP" | jq -r ".phones[0].id")

# 2.3 Verify existing audit history is coherent and preserved
HIST_2_3=$(curl -s -b "$OPERATOR_COOKIES" -H "X-Tenant-Id: $TENANT_A" "$BFF_URL/api/customers/$JOAQUIN_ID/contact-policy/history")
HIST_COUNT_2_3=$(echo "$HIST_2_3" | jq -r ".events | length")
record_result "2.ContactIdentityChange" "2.1 Consent audit history remains coherent after contact update" "1+" "$HIST_COUNT_2_3" "$([ "$HIST_COUNT_2_3" -ge 1 ] && echo "PASS" || echo "FAIL")" "Audit events: $HIST_COUNT_2_3"

# 2.4 Verify current contact state is authoritative: Phone B has NO granted consent
POL_2_4=$(curl -s -b "$OPERATOR_COOKIES" -H "X-Tenant-Id: $TENANT_A" "$BFF_URL/api/customers/$JOAQUIN_ID/contact-policy")
PHONE_B_CONSENT=$(echo "$POL_2_4" | jq -r "(.consents[] | select(.contactId==\"$JOAQUIN_PHONE_B_ID\") | .status) // \"UNKNOWN\"")
record_result "2.ContactIdentityChange" "2.2 New contact has NO unverified granted consent" "UNKNOWN" "$PHONE_B_CONSENT" "$([ "$PHONE_B_CONSENT" = "UNKNOWN" ] && echo "PASS" || echo "FAIL")" "Authoritative contact status: $PHONE_B_CONSENT"

# 2.5 Verify follow-up eligibility does NOT use stale Phone A: immediately INELIGIBLE
ELIG_2_5=$(curl -s -b "$OPERATOR_COOKIES" -H "X-Tenant-Id: $TENANT_A" "$BFF_URL/api/customers/$JOAQUIN_ID/follow-up-eligibility")
STATUS_2_5=$(echo "$ELIG_2_5" | jq -r ".status")
REASON_2_5=$(echo "$ELIG_2_5" | jq -r ".reasons[0]")
record_result "2.ContactIdentityChange" "2.3 Follow-up eligibility fails closed (NO_ELIGIBLE_CONTACT) on modified phone" "INELIGIBLE:NO_ELIGIBLE_CONTACT" "$STATUS_2_5:$REASON_2_5" "$([ "$STATUS_2_5" = "INELIGIBLE" ] && [ "$REASON_2_5" = "NO_ELIGIBLE_CONTACT" ] && echo "PASS" || echo "FAIL")" "Does not use stale phone"

echo ""
echo "=== 3. CROSS-SESSION/TAB STALE CONSENT STATE ==="

# 3.1 Create candidate Esteban Silva with consent, purchase, explicit date = today
ESTEBAN_PHONE="98$((RANDOM % 9000000 + 1000000))"
ESTEBAN_RESP=$(curl -s -b "$OPERATOR_COOKIES" -X POST "$BFF_URL/api/customers" \
    -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT_A" -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
    -d "{\"displayName\": \"Esteban Silva\", \"notes\": \"Prueba concurrencia sesion\", \"phones\": [{\"number\": \"$ESTEBAN_PHONE\", \"region\": \"CL\", \"primary\": true}]}")
ESTEBAN_ID=$(echo "$ESTEBAN_RESP" | jq -r ".id")
ESTEBAN_PHONE_ID=$(echo "$ESTEBAN_RESP" | jq -r ".phones[0].id")

POL_3_1=$(curl -s -i -b "$OPERATOR_COOKIES" -H "X-Tenant-Id: $TENANT_A" "$BFF_URL/api/customers/$ESTEBAN_ID/contact-policy")
ETAG_3_1=$(echo "$POL_3_1" | grep -i "^etag:" | tr -d "\r\n" | awk "{print \$2}")
curl -s -b "$OPERATOR_COOKIES" -X PUT "$BFF_URL/api/customers/$ESTEBAN_ID/contacts/$ESTEBAN_PHONE_ID/consents/WHATSAPP" \
    -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT_A" -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
    -H "If-Match: $ETAG_3_1" -d '{"status": "GRANTED", "source": "CUSTOMER_VERBAL"}' >/dev/null

curl -s -b "$OPERATOR_COOKIES" -X POST "$BFF_URL/api/customers/$ESTEBAN_ID/purchases" \
    -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT_A" -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
    -H "Idempotency-Key: esteban-p-$RANDOM" -d "{\"purchasedAt\": \"$PAST_TS\", \"description\": \"Café Especial\"}" >/dev/null

EST_FP_GET=$(curl -s -i -b "$OPERATOR_COOKIES" -H "X-Tenant-Id: $TENANT_A" "$BFF_URL/api/customers/$ESTEBAN_ID/follow-up-policy")
EST_FP_ETAG=$(echo "$EST_FP_GET" | grep -i "^etag:" | tr -d "\r\n" | awk "{print \$2}")
curl -s -b "$OPERATOR_COOKIES" -X PUT "$BFF_URL/api/customers/$ESTEBAN_ID/follow-up-policy" \
    -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT_A" -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
    -H "If-Match: $EST_FP_ETAG" -d "{\"cadenceDays\": 14, \"explicitNextDate\": \"$TODAY\"}" >/dev/null

# Session A captures current Follow-Up policy ETag
EST_POL_A=$(curl -s -i -b "$OPERATOR_COOKIES" -H "X-Tenant-Id: $TENANT_A" "$BFF_URL/api/customers/$ESTEBAN_ID/follow-up-policy")
SESSION_A_ETAG=$(echo "$EST_POL_A" | grep -i "^etag:" | tr -d "\r\n" | awk "{print \$2}")

# Session B (second authenticated session as testuser) modifies consent: REVOKES WhatsApp
SESSION_B_COOKIES="$TEMP_DIR/session-b-cookies.txt"
login "testuser" "$SESSION_B_COOKIES"
SESSION_B_JSON=$(curl -s -b "$SESSION_B_COOKIES" "$BFF_URL/api/session")
CSRF_B=$(echo "$SESSION_B_JSON" | jq -r ".csrfToken")

EST_POL_B=$(curl -s -i -b "$SESSION_B_COOKIES" -H "X-Tenant-Id: $TENANT_A" "$BFF_URL/api/customers/$ESTEBAN_ID/contact-policy")
ETAG_B=$(echo "$EST_POL_B" | grep -i "^etag:" | tr -d "\r\n" | awk "{print \$2}")
curl -s -b "$SESSION_B_COOKIES" -X PUT "$BFF_URL/api/customers/$ESTEBAN_ID/contacts/$ESTEBAN_PHONE_ID/consents/WHATSAPP" \
    -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT_A" -H "X-CSRF-TOKEN: $CSRF_B" \
    -H "If-Match: $ETAG_B" -d '{"status": "REVOKED", "source": "CUSTOMER_VERBAL"}' >/dev/null

# Session A now attempts previously prepared manual follow-up disposition
STALE_DISP_RESP=$(curl -s -b "$OPERATOR_COOKIES" -w "\n%{http_code}" -X POST "$BFF_URL/api/customers/$ESTEBAN_ID/manual-follow-ups" \
    -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT_A" -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
    -H "If-Match: $SESSION_A_ETAG" -H "Idempotency-Key: stale-disp-$RANDOM" \
    -d '{"notes": "Intento de disposicion concurrente"}')
STALE_DISP_HTTP=$(echo "$STALE_DISP_RESP" | tail -n 1)
record_result "3.CrossSessionStaleConsent" "3.1 Stale follow-up action fails closed (409 Conflict) after concurrent revocation" "409" "$STALE_DISP_HTTP" "$([ "$STALE_DISP_HTTP" = "409" ] && echo "PASS" || echo "FAIL")" "Session A mutation rejected"

echo ""
echo "=== 4. PURCHASE LIFECYCLE GAPS ==="

# 4.1 Retry purchase submission using supported Idempotency-Key
CAROLINA_PHONE="98$((RANDOM % 9000000 + 1000000))"
CAROLINA_RESP=$(curl -s -b "$OPERATOR_COOKIES" -X POST "$BFF_URL/api/customers" \
    -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT_A" -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
    -d "{\"displayName\": \"Carolina Reyes\", \"notes\": \"Ciclo completo compras\", \"phones\": [{\"number\": \"$CAROLINA_PHONE\", \"region\": \"CL\", \"primary\": true}]}")
CAROLINA_ID=$(echo "$CAROLINA_RESP" | jq -r ".id")

IDEM_KEY="carolina-idem-$RANDOM"
P1_FIRST=$(curl -s -b "$OPERATOR_COOKIES" -w "\n%{http_code}" -X POST "$BFF_URL/api/customers/$CAROLINA_ID/purchases" \
    -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT_A" -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
    -H "Idempotency-Key: $IDEM_KEY" -d "{\"purchasedAt\": \"$OLD_PAST_TS\", \"description\": \"1kg Geisha Inicial\"}")
HTTP_P1_FIRST=$(echo "$P1_FIRST" | tail -n 1)
P1_FIRST_ID=$(echo "$P1_FIRST" | head -n -1 | jq -r ".id")

P1_RETRY=$(curl -s -b "$OPERATOR_COOKIES" -w "\n%{http_code}" -X POST "$BFF_URL/api/customers/$CAROLINA_ID/purchases" \
    -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT_A" -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
    -H "Idempotency-Key: $IDEM_KEY" -d "{\"purchasedAt\": \"$OLD_PAST_TS\", \"description\": \"1kg Geisha Inicial\"}")
HTTP_P1_RETRY=$(echo "$P1_RETRY" | tail -n 1)
P1_RETRY_ID=$(echo "$P1_RETRY" | head -n -1 | jq -r ".id")

record_result "4.PurchaseGaps" "4.1 Idempotent purchase retry returns 200 without duplicate creation" "201:200:SameID" "$HTTP_P1_FIRST:$HTTP_P1_RETRY:$([ "$P1_FIRST_ID" = "$P1_RETRY_ID" ] && echo "SameID" || echo "DifferentID")" "$([ "$HTTP_P1_FIRST" = "201" ] && [ "$HTTP_P1_RETRY" = "200" ] && [ "$P1_FIRST_ID" = "$P1_RETRY_ID" ] && echo "PASS" || echo "FAIL")" "P1 ID: $P1_FIRST_ID"

# 4.2 Correct an existing purchase
P1_GET=$(curl -s -i -b "$OPERATOR_COOKIES" -H "X-Tenant-Id: $TENANT_A" "$BFF_URL/api/customers/$CAROLINA_ID/purchases/$P1_FIRST_ID")
P1_ETAG=$(echo "$P1_GET" | grep -i "^etag:" | tr -d "\r\n" | awk "{print \$2}")

CORRECT_RESP=$(curl -s -b "$OPERATOR_COOKIES" -w "\n%{http_code}" -X PUT "$BFF_URL/api/customers/$CAROLINA_ID/purchases/$P1_FIRST_ID" \
    -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT_A" -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
    -H "If-Match: $P1_ETAG" -d "{\"purchasedAt\": \"$OLD_PAST_TS\", \"description\": \"1kg Geisha Inicial (Corregido)\"}")
CORRECT_HTTP=$(echo "$CORRECT_RESP" | tail -n 1)
CORRECT_DESC=$(echo "$CORRECT_RESP" | head -n -1 | jq -r ".description")
record_result "4.PurchaseGaps" "4.2 Correct existing purchase via PUT returns 200 with updated description" "200:Corregido" "$CORRECT_HTTP:$([ "$CORRECT_DESC" = "1kg Geisha Inicial (Corregido)" ] && echo "Corregido" || echo "Unchanged")" "$([ "$CORRECT_HTTP" = "200" ] && [ "$CORRECT_DESC" = "1kg Geisha Inicial (Corregido)" ] && echo "PASS" || echo "FAIL")" "Updated description: $CORRECT_DESC"

# 4.3 Add a newer second purchase (making it the latest purchase)
P2_RESP=$(curl -s -b "$OPERATOR_COOKIES" -X POST "$BFF_URL/api/customers/$CAROLINA_ID/purchases" \
    -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT_A" -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
    -H "Idempotency-Key: carolina-p2-$RANDOM" -d "{\"purchasedAt\": \"$PAST_TS\", \"description\": \"500g Bourbon Reciente\"}")
P2_ID=$(echo "$P2_RESP" | jq -r ".id")

# Verify last purchase points to P2
LAST_BEFORE=$(curl -s -b "$OPERATOR_COOKIES" -H "X-Tenant-Id: $TENANT_A" "$BFF_URL/api/customers/$CAROLINA_ID/purchases/last")
LAST_BEFORE_ID=$(echo "$LAST_BEFORE" | jq -r ".id")

# 4.4 Void the CURRENT LATEST purchase (P2)
P2_GET=$(curl -s -i -b "$OPERATOR_COOKIES" -H "X-Tenant-Id: $TENANT_A" "$BFF_URL/api/customers/$CAROLINA_ID/purchases/$P2_ID")
P2_ETAG=$(echo "$P2_GET" | grep -i "^etag:" | tr -d "\r\n" | awk "{print \$2}")
VOID_P2=$(curl -s -b "$OPERATOR_COOKIES" -w "%{http_code}" -X DELETE "$BFF_URL/api/customers/$CAROLINA_ID/purchases/$P2_ID" \
    -H "X-Tenant-Id: $TENANT_A" -H "X-CSRF-TOKEN: $CSRF_TOKEN" -H "If-Match: $P2_ETAG")

# 4.5 Verify last-purchase recalculation after voiding the latest purchase (reverts to P1)
LAST_AFTER=$(curl -s -b "$OPERATOR_COOKIES" -H "X-Tenant-Id: $TENANT_A" "$BFF_URL/api/customers/$CAROLINA_ID/purchases/last")
LAST_AFTER_ID=$(echo "$LAST_AFTER" | jq -r ".id")

record_result "4.PurchaseGaps" "4.3 Void latest purchase recalculates last-purchase to previous valid purchase" "204:P2->P1" "$VOID_P2:$([ "$LAST_BEFORE_ID" = "$P2_ID" ] && [ "$LAST_AFTER_ID" = "$P1_FIRST_ID" ] && echo "P2->P1" || echo "Mismatch")" "$([ "$VOID_P2" = "204" ] && [ "$LAST_BEFORE_ID" = "$P2_ID" ] && [ "$LAST_AFTER_ID" = "$P1_FIRST_ID" ] && echo "PASS" || echo "FAIL")" "Before: $LAST_BEFORE_ID, After: $LAST_AFTER_ID"

echo ""
echo "=== 5. FOLLOW-UP ELIGIBILITY INVALIDATION AFTER CANDIDATE BECOMES DUE ==="

# 5.1 Invalidation by Archival: Due candidate -> Archived -> disappears from queue
MATIAS_PHONE="98$((RANDOM % 9000000 + 1000000))"
MATIAS_RESP=$(curl -s -b "$OPERATOR_COOKIES" -X POST "$BFF_URL/api/customers" \
    -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT_A" -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
    -d "{\"displayName\": \"Matías Valenzuela\", \"notes\": \"Invalidacion por archivo\", \"phones\": [{\"number\": \"$MATIAS_PHONE\", \"region\": \"CL\", \"primary\": true}]}")
MATIAS_ID=$(echo "$MATIAS_RESP" | jq -r ".id")
MATIAS_PHONE_ID=$(echo "$MATIAS_RESP" | jq -r ".phones[0].id")
MATIAS_CUST_VER=$(echo "$MATIAS_RESP" | jq -r ".version")

# Consent + Purchase + Explicit due today
POL_5_1=$(curl -s -i -b "$OPERATOR_COOKIES" -H "X-Tenant-Id: $TENANT_A" "$BFF_URL/api/customers/$MATIAS_ID/contact-policy")
ETAG_5_1=$(echo "$POL_5_1" | grep -i "^etag:" | tr -d "\r\n" | awk "{print \$2}")
curl -s -b "$OPERATOR_COOKIES" -X PUT "$BFF_URL/api/customers/$MATIAS_ID/contacts/$MATIAS_PHONE_ID/consents/WHATSAPP" \
    -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT_A" -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
    -H "If-Match: $ETAG_5_1" -d '{"status": "GRANTED", "source": "CUSTOMER_VERBAL"}' >/dev/null

curl -s -b "$OPERATOR_COOKIES" -X POST "$BFF_URL/api/customers/$MATIAS_ID/purchases" \
    -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT_A" -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
    -H "Idempotency-Key: matias-p-$RANDOM" -d "{\"purchasedAt\": \"$PAST_TS\", \"description\": \"Café Especial\"}" >/dev/null

FP_5_1=$(curl -s -i -b "$OPERATOR_COOKIES" -H "X-Tenant-Id: $TENANT_A" "$BFF_URL/api/customers/$MATIAS_ID/follow-up-policy")
FP_ETAG_5_1=$(echo "$FP_5_1" | grep -i "^etag:" | tr -d "\r\n" | awk "{print \$2}")
curl -s -b "$OPERATOR_COOKIES" -X PUT "$BFF_URL/api/customers/$MATIAS_ID/follow-up-policy" \
    -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT_A" -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
    -H "If-Match: $FP_ETAG_5_1" -d "{\"cadenceDays\": 14, \"explicitNextDate\": \"$TODAY\"}" >/dev/null

# Verify Matias is in due queue
Q_MATIAS_BEFORE=$(curl -s -b "$OPERATOR_COOKIES" -H "X-Tenant-Id: $TENANT_A" "$BFF_URL/api/follow-up-queue?status=DUE")
IN_Q_BEFORE=$(echo "$Q_MATIAS_BEFORE" | jq -r ".items[] | select(.customerId==\"$MATIAS_ID\") | .customerId")

# Get fresh customer ETag and archive customer Matias as OWNER (requires CUSTOMER_DELETE)
MATIAS_GET=$(curl -s -i -b "$SESSION_B_COOKIES" -H "X-Tenant-Id: $TENANT_A" "$BFF_URL/api/customers/$MATIAS_ID")
MATIAS_ETAG=$(echo "$MATIAS_GET" | grep -i "^etag:" | tr -d "\r\n" | awk "{print \$2}")
ARCHIVE_HTTP=$(curl -s -b "$SESSION_B_COOKIES" -w "%{http_code}" -X DELETE "$BFF_URL/api/customers/$MATIAS_ID" \
    -H "X-Tenant-Id: $TENANT_A" -H "X-CSRF-TOKEN: $CSRF_B" -H "If-Match: $MATIAS_ETAG")

# Verify candidate drops from due queue and eligibility is INELIGIBLE (CUSTOMER_ARCHIVED)
Q_MATIAS_AFTER=$(curl -s -b "$OPERATOR_COOKIES" -H "X-Tenant-Id: $TENANT_A" "$BFF_URL/api/follow-up-queue?status=DUE")
IN_Q_AFTER=$(echo "$Q_MATIAS_AFTER" | jq -r ".items[] | select(.customerId==\"$MATIAS_ID\") | .customerId")
ELIG_MATIAS=$(curl -s -b "$OPERATOR_COOKIES" -H "X-Tenant-Id: $TENANT_A" "$BFF_URL/api/customers/$MATIAS_ID/follow-up-eligibility")
STATUS_MATIAS=$(echo "$ELIG_MATIAS" | jq -r ".status")
REASON_MATIAS=$(echo "$ELIG_MATIAS" | jq -r ".reasons[0]")

record_result "5.EligibilityInvalidation" "5.1 Archiving due customer immediately purges candidate from due queue" "DROPPED:CUSTOMER_ARCHIVED" "$([ -z "$IN_Q_AFTER" ] && echo "DROPPED" || echo "STILL_IN_Q"):$STATUS_MATIAS:$REASON_MATIAS" "$([ -n "$IN_Q_BEFORE" ] && [ -z "$IN_Q_AFTER" ] && [ "$STATUS_MATIAS" = "INELIGIBLE" ] && [ "$REASON_MATIAS" = "CUSTOMER_ARCHIVED" ] && echo "PASS" || echo "FAIL")" "Archived dropped from queue"

# 5.2 Invalidation by New Purchase: Due candidate -> New purchase registered -> recalculated to not yet due
SOFIA_PHONE="98$((RANDOM % 9000000 + 1000000))"
SOFIA_RESP=$(curl -s -b "$OPERATOR_COOKIES" -X POST "$BFF_URL/api/customers" \
    -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT_A" -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
    -d "{\"displayName\": \"Sofía Herrera\", \"notes\": \"Recalculo por nueva compra\", \"phones\": [{\"number\": \"$SOFIA_PHONE\", \"region\": \"CL\", \"primary\": true}]}")
SOFIA_ID=$(echo "$SOFIA_RESP" | jq -r ".id")
SOFIA_PHONE_ID=$(echo "$SOFIA_RESP" | jq -r ".phones[0].id")

POL_5_2=$(curl -s -i -b "$OPERATOR_COOKIES" -H "X-Tenant-Id: $TENANT_A" "$BFF_URL/api/customers/$SOFIA_ID/contact-policy")
ETAG_5_2=$(echo "$POL_5_2" | grep -i "^etag:" | tr -d "\r\n" | awk "{print \$2}")
curl -s -b "$OPERATOR_COOKIES" -X PUT "$BFF_URL/api/customers/$SOFIA_ID/contacts/$SOFIA_PHONE_ID/consents/WHATSAPP" \
    -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT_A" -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
    -H "If-Match: $ETAG_5_2" -d '{"status": "GRANTED", "source": "CUSTOMER_VERBAL"}' >/dev/null

# Old purchase from 35 days ago (default tenant cadence is 30 days, so customer becomes overdue/due)
curl -s -b "$OPERATOR_COOKIES" -X POST "$BFF_URL/api/customers/$SOFIA_ID/purchases" \
    -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT_A" -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
    -H "Idempotency-Key: sofia-p1-$RANDOM" -d "{\"purchasedAt\": \"$OLD_PAST_TS\", \"description\": \"Compra Antigua\"}" >/dev/null

# Verify Sofia is DUE in queue
ELIG_SOFIA_BEFORE=$(curl -s -b "$OPERATOR_COOKIES" -H "X-Tenant-Id: $TENANT_A" "$BFF_URL/api/customers/$SOFIA_ID/follow-up-eligibility")
STATUS_SOFIA_BEFORE=$(echo "$ELIG_SOFIA_BEFORE" | jq -r ".status")

# Register brand-new purchase (2 hours ago)
curl -s -b "$OPERATOR_COOKIES" -X POST "$BFF_URL/api/customers/$SOFIA_ID/purchases" \
    -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT_A" -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
    -H "Idempotency-Key: sofia-p2-$RANDOM" -d "{\"purchasedAt\": \"$PAST_TS\", \"description\": \"Nueva Compra Reciente\"}" >/dev/null

# Verify eligibility immediately recalculated to NOT_YET_DUE
ELIG_SOFIA_AFTER=$(curl -s -b "$OPERATOR_COOKIES" -H "X-Tenant-Id: $TENANT_A" "$BFF_URL/api/customers/$SOFIA_ID/follow-up-eligibility")
STATUS_SOFIA_AFTER=$(echo "$ELIG_SOFIA_AFTER" | jq -r ".status")
REASON_SOFIA_AFTER=$(echo "$ELIG_SOFIA_AFTER" | jq -r ".reasons[0]")
SOURCE_SOFIA_AFTER=$(echo "$ELIG_SOFIA_AFTER" | jq -r ".timingSource")

record_result "5.EligibilityInvalidation" "5.2 New purchase recalculates due candidate back to NOT_YET_DUE" "DUE->NOT_YET_DUE:LAST_PURCHASE" "$STATUS_SOFIA_BEFORE->$STATUS_SOFIA_AFTER:$SOURCE_SOFIA_AFTER" "$([ "$STATUS_SOFIA_BEFORE" = "OVERDUE" -o "$STATUS_SOFIA_BEFORE" = "DUE" ] && [ "$STATUS_SOFIA_AFTER" = "NOT_YET_DUE" ] && [ "$SOURCE_SOFIA_AFTER" = "LAST_PURCHASE" ] && echo "PASS" || echo "FAIL")" "Before: $STATUS_SOFIA_BEFORE, After: $STATUS_SOFIA_AFTER"

echo ""
echo "=== 6. CROSS-WORKSPACE STALE FORM/DETAIL MUTATION ==="

# 6.1 Customer Gabriel Ramos created in Workspace A (Tenant A)
GABRIEL_PHONE="98$((RANDOM % 9000000 + 1000000))"
GABRIEL_RESP=$(curl -s -b "$OPERATOR_COOKIES" -X POST "$BFF_URL/api/customers" \
    -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT_A" -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
    -d "{\"displayName\": \"Gabriel Ramos\", \"notes\": \"Cliente en Workspace A\", \"phones\": [{\"number\": \"$GABRIEL_PHONE\", \"region\": \"CL\", \"primary\": true}]}")
GABRIEL_ID=$(echo "$GABRIEL_RESP" | jq -r ".id")
GABRIEL_VER=$(echo "$GABRIEL_RESP" | jq -r ".version")

# 6.2 Attempt to submit mutation against Gabriel ID scoped to Workspace B (Tenant B)
STALE_MUTATION=$(curl -s -b "$OPERATOR_COOKIES" -w "\n%{http_code}" -X PUT "$BFF_URL/api/customers/$GABRIEL_ID" \
    -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT_B" -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
    -H "If-Match: \"$GABRIEL_VER\"" \
    -d "{\"displayName\": \"Gabriel Ramos Hack\", \"notes\": \"Intento de contaminacion cruzada\", \"phones\": [{\"number\": \"$GABRIEL_PHONE\", \"region\": \"CL\", \"primary\": true}]}")
STALE_HTTP=$(echo "$STALE_MUTATION" | tail -n 1)

# 6.3 Verify Workspace A remains untouched
GAB_CHECK_A=$(curl -s -b "$OPERATOR_COOKIES" -H "X-Tenant-Id: $TENANT_A" "$BFF_URL/api/customers/$GABRIEL_ID")
GAB_NAME_A=$(echo "$GAB_CHECK_A" | jq -r ".displayName")

# 6.4 Verify Workspace B has no customer with this phone
GAB_CHECK_B=$(curl -s -b "$OPERATOR_COOKIES" -H "X-Tenant-Id: $TENANT_B" "$BFF_URL/api/customers?phone=$GABRIEL_PHONE&region=CL")
GAB_COUNT_B=$(echo "$GAB_CHECK_B" | jq -r ".customers | length")

record_result "6.CrossWorkspaceStaleMutation" "6.1 Stale cross-workspace mutation fails closed (404) without contamination" "404:Gabriel Ramos:0" "$STALE_HTTP:$GAB_NAME_A:$GAB_COUNT_B" "$([ "$STALE_HTTP" = "404" ] && [ "$GAB_NAME_A" = "Gabriel Ramos" ] && [ "$GAB_COUNT_B" -eq 0 ] && echo "PASS" || echo "FAIL")" "Workspace boundary enforced"

echo ""
echo "=== 7. FIRST-USE / EMPTY-STATE COMPLETENESS ==="

# 7.1 Fresh customer with NO purchase history and NO consent history
TOMAS_PHONE="98$((RANDOM % 9000000 + 1000000))"
TOMAS_RESP=$(curl -s -b "$OPERATOR_COOKIES" -X POST "$BFF_URL/api/customers" \
    -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT_A" -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
    -d "{\"displayName\": \"Tomás Alarcón\", \"notes\": \"Cliente recien registrado para estados vacios\", \"phones\": [{\"number\": \"$TOMAS_PHONE\", \"region\": \"CL\", \"primary\": true}]}")
TOMAS_ID=$(echo "$TOMAS_RESP" | jq -r ".id")

# Purchase history is empty (0 purchases, 204 on /last)
TOMAS_PURCHASES=$(curl -s -b "$OPERATOR_COOKIES" -H "X-Tenant-Id: $TENANT_A" "$BFF_URL/api/customers/$TOMAS_ID/purchases")
TOMAS_P_COUNT=$(echo "$TOMAS_PURCHASES" | jq -r ".purchases | length")
TOMAS_LAST_HTTP=$(curl -s -o /dev/null -w "%{http_code}" -b "$OPERATOR_COOKIES" -H "X-Tenant-Id: $TENANT_A" "$BFF_URL/api/customers/$TOMAS_ID/purchases/last")

record_result "7.EmptyStateCompleteness" "7.1 First-use customer has 0 purchases and 204 No Content on last purchase" "0:204" "$TOMAS_P_COUNT:$TOMAS_LAST_HTTP" "$([ "$TOMAS_P_COUNT" -eq 0 ] && [ "$TOMAS_LAST_HTTP" = "204" ] && echo "PASS" || echo "FAIL")" "Zero purchases"

# Consent history is empty (0 events)
TOMAS_CONSENT_HIST=$(curl -s -b "$OPERATOR_COOKIES" -H "X-Tenant-Id: $TENANT_A" "$BFF_URL/api/customers/$TOMAS_ID/contact-policy/history")
TOMAS_EVT_COUNT=$(echo "$TOMAS_CONSENT_HIST" | jq -r ".events | length")

record_result "7.EmptyStateCompleteness" "7.2 First-use customer has 0 consent audit events" "0" "$TOMAS_EVT_COUNT" "$([ "$TOMAS_EVT_COUNT" -eq 0 ] && echo "PASS" || echo "FAIL")" "Zero audit events"

# Evaluation on unconsented, unpurchased customer reports deterministic fail-closed reasons
TOMAS_ELIG=$(curl -s -b "$OPERATOR_COOKIES" -H "X-Tenant-Id: $TENANT_A" "$BFF_URL/api/customers/$TOMAS_ID/follow-up-eligibility")
TOMAS_STATUS=$(echo "$TOMAS_ELIG" | jq -r ".status")
TOMAS_REASONS=$(echo "$TOMAS_ELIG" | jq -r '.reasons | sort | join(",")')

record_result "7.EmptyStateCompleteness" "7.3 Unconsented first-use customer reports deterministic fail-closed INELIGIBLE" "INELIGIBLE:NO_ELIGIBLE_CONTACT" "$TOMAS_STATUS:$TOMAS_REASONS" "$([ "$TOMAS_STATUS" = "INELIGIBLE" ] && [ "$TOMAS_REASONS" = "NO_ELIGIBLE_CONTACT" ] && echo "PASS" || echo "FAIL")" "Status: $TOMAS_STATUS, Reasons: $TOMAS_REASONS"

echo ""
echo "======================================================================"
echo "FOCUSED QA #61 SUMMARY: $PASSED_TESTS / $TOTAL_TESTS PASSED ($FAILED_TESTS FAILED)"
echo "======================================================================"

if [ "$FAILED_TESTS" -eq 0 ]; then
    exit 0
else
    exit 1
fi

