#!/usr/bin/env bash
# ABOUTME: Suite #333 customer fixtures (P3-MQA-ENV-02): due, ineligible (no consent / opted out) and not-yet-due
# ABOUTME: synthetic customers in tenant A, one due customer in tenant B. Invented names, +5698 test-range numbers.
# shellcheck disable=SC2046  # $(... echo PASS || echo FAIL) always yields one word
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/qa333.sh"
. "$OUTD/ids"
for u in testoperator testoperator2; do qa_login $u; done
PAST=$(date -u -d '-40 days' +%Y-%m-%dT%H:%M:%SZ); RECENT=$(date -u -d '-2 days' +%Y-%m-%dT%H:%M:%SZ)
n=0
mk() { # user tenant name consent(GRANTED|REVOKED|none) purchasedAt nextDate(today|none)
  n=$((n+1)); local phone; phone=$(printf '+5698%07d' $((3330000 + n)))
  req "$1" "$2" POST /api/customers "$(jq -cn --arg n "$3" --arg p "$phone" '{displayName:$n,phones:[{number:$p,region:"CL",primary:true}]}')"
  local cid pid; cid=$(printf '%s' "$BODY" | jq -r .id); pid=$(printf '%s' "$BODY" | jq -r '.phones[0].id'); local c1=$CODE
  if [ "$4" != none ]; then
    req "$1" "$2" GET "/api/customers/$cid/contact-policy" -
    req "$1" "$2" PUT "/api/customers/$cid/contacts/$pid/consents/WHATSAPP" "{\"status\":\"$4\",\"source\":\"CUSTOMER_VERBAL\"}" -H "If-Match: $(etag)"
  fi; local c2=${CODE:-}
  req "$1" "$2" POST "/api/customers/$cid/purchases" "$(jq -cn --arg t "$5" '{purchasedAt:$t,description:"Compra sintetica QA333"}')" -H "Idempotency-Key: qa333-p-$cid"
  local c3=$CODE c4=-
  if [ "$6" = today ]; then
    local tz; req "$1" "$2" GET "/api/customers/$cid/follow-up-policy" -; tz=$(printf '%s' "$BODY" | jq -r '.timeZone // "UTC"')
    req "$1" "$2" PUT "/api/customers/$cid/follow-up-policy" "$(jq -cn --arg d "$(TZ=$tz date +%F)" '{cadenceDays:null,explicitNextDate:$d}')" -H "If-Match: $(etag)"; c4=$CODE
  fi
  req "$1" "$2" GET "/api/customers/$cid/follow-up-eligibility" -
  printf '  %-28s create=%s consent=%s purchase=%s policy=%s eligibility=%s %s\n' "$3" "$c1" "$c2" "$c3" "$c4" \
    "$(printf '%s' "$BODY" | jq -r '.status // .eligibility // "?"')" "$(printf '%s' "$BODY" | jq -c '.reasons // .ineligibilityReasons // empty' 2>/dev/null)"
}
echo "== tenant A"
mk testoperator "$A" "QA333 Ana Vencida" GRANTED "$PAST" today
mk testoperator "$A" "QA333 Beto Sin Consentimiento" none "$PAST" today
mk testoperator "$A" "QA333 Carla Revocada" REVOKED "$PAST" today
mk testoperator "$A" "QA333 Dario Reciente" GRANTED "$RECENT" none
echo "== tenant B"
mk testoperator2 "$B" "QA333 Elena Sur Vencida" GRANTED "$PAST" today
