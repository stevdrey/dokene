#!/usr/bin/env bash
# ABOUTME: Suite #149, CSRF cases P3-CSRF-01, 02, 04 and 05 over real HTTP, direct and through the nginx proxy.
# ABOUTME: P3-CSRF-03 (a page on another origin in a real browser) is in sec149-browser.mjs.
cd "$(git rev-parse --show-toplevel)"; . docs/verification/issue-149-evidence/scripts/env149.sh
H=HTTP; stub_reset; stub_set send accept

echo "== fixtures"
read -r CA PA MP <<< "$(ready_pending $A_OPERATOR "$A" "CSRF pending $RUN")"
read -r CA2 PA2 MA <<< "$(ready_approved $A_OPERATOR "$A" "CSRF approved $RUN")"
read -r CA3 PA3 <<< "$(mk_customer $A_OPERATOR "$A" "CSRF submit $RUN")"; make_due $A_OPERATOR "$A" "$CA3" "$PA3"
IE=$(integ_etag $A_OWNER "$A"); TV=$(tmpl_version $A_OWNER "$A")
# every unsafe Phase 3 route with a body and headers that would otherwise succeed or at least pass validation
ROUTES=(
  "POST /api/customers/$CA3/messages {\"contactId\":\"$PA3\",\"action\":\"GENERAL_CHECK_IN\",\"templateIntent\":\"GENERAL_FOLLOW_UP\",\"body\":\"Hola\",\"locale\":\"es-419\",\"origin\":\"MANUAL\"} $(fpv $A_OWNER "$A" "$CA3")"
  "POST /api/messages/$MP/approvals {\"decision\":\"APPROVE\"} $(msg_etag $A_OWNER "$A" "$MP")"
  "POST /api/messages/$MP/cancellations {} $(msg_etag $A_OWNER "$A" "$MP")"
  "POST /api/messages/$MA/sends - $(msg_etag $A_OWNER "$A" "$MA")"
  "POST /api/messages/$MA/resolutions {\"outcome\":\"FAILED\"} $(msg_etag $A_OWNER "$A" "$MA")"
  "PUT /api/templates/SEASONAL_EVENT {\"providerTemplateName\":\"qa149_csrf\",\"language\":\"es\",\"parameterNames\":[\"customer_name\"],\"enabled\":true} *"
  "POST /api/templates/GENERAL_FOLLOW_UP/approval-refresh - \"$TV\""
  "PUT /api/integrations/whatsapp {\"phoneNumberId\":\"$PN_A\",\"businessAccountId\":\"$WABA_A\",\"displayPhoneNumber\":\"+50600000000\",\"enabled\":true,\"secretRef\":\"qa149-token-a\"} $IE"
  "POST /api/integrations/whatsapp/credential-rotations - $IE"
  "POST /api/integrations/whatsapp/ownership-verifications - $IE"
  "PUT /api/integrations/whatsapp/outbound {\"enabled\":false} $IE"
)
run_routes() { # <case id> <label>
  local r m p d v; for r in "${ROUTES[@]}"; do read -r m p d v <<< "$r"; S=$(snap)
    [ "$v" = "*" ] && v='If-None-Match: *' || v="If-Match: $v"
    req $A_OWNER "$A" "$m" "$p" "$d" -H "Idempotency-Key: qa149-$RUN-$RANDOM$RANDOM" -H "$v"
    chk "$1" $H "$2: $m $p -> 403 and nothing written" "403 ok" "$CODE $(unchanged "$S" any)"
  done
}
echo "== P3-CSRF-01 valid session, no X-CSRF-TOKEN"
CSRF_MODE=none run_routes P3-CSRF-01 "no token"
echo "== P3-CSRF-02 token of another session, then a random token"
CSRF_MODE=$(csrf $A_OPERATOR) run_routes P3-CSRF-02 "operator's token on the owner session"
CSRF_MODE=$(uuidgen) run_routes P3-CSRF-02 "random token"
chk P3-CSRF-02 $H "control: the right token on the owner session cancels MP (201)" 201 "$(cancel $A_OWNER "$A" "$MP" >/dev/null; echo $CODE)"
chk P3-CSRF-01 $H "message states unchanged by the CSRF attempts: MA still APPROVED, no attempt" "APPROVED 0" "$(msg_status "$MA" | cut -d/ -f1) $(attempts_n "$MA")"
chk P3-CSRF-01 $H "tenant switch still on, integration still verified" "true true" "$(getj $A_OWNER "$A" /api/integrations/whatsapp | jq -r '(.outboundEnabled|tostring)+" "+(.webhookVerified|tostring)')"

echo "== P3-CSRF-04 the webhook is exempt on its signature alone; /api is not"
body=$(hook_body "$PN_A" "wamid.SYNTHETIC-csrf04-$RUN" delivered); h0=$(hooks_n "$A")
hook_post "$body" "$CANARY_APP_SECRET"; chk P3-CSRF-04 $H "signed webhook without any CSRF token: 200 and one inbox row" "200 1" "$CODE $(( $(hooks_n "$A") - h0 ))"
S=$(snap); CSRF_MODE=none send $A_OWNER "$A" "$MA"; chk P3-CSRF-04 $H "/api send without token still 403, nothing written" "403 ok" "$CODE $(unchanged "$S" any)"

echo "== P3-CSRF-05 webhook path handling, direct and through the proxy"
for base in "$BFF" "$PROXY"; do via=$([ "$base" = "$PROXY" ] && echo proxy || echo direct)
  raw -X POST "$base/webhooks/whatsapp" -H 'Content-Type: application/json' -d '{}'
  chk P3-CSRF-05 $H "$via: unsigned webhook: 403, no Set-Cookie, no redirect" "403 0 " "$CODE $(echo "$HDRS" | grep -ci '^set-cookie') $(hdr location)"
  raw -X POST "$base/webhooks/whatsapp" -H 'Content-Type: application/json' -b "$QA_TMP/jar-$A_OWNER.txt" -d '{}'
  chk P3-CSRF-05 $H "$via: unsigned webhook with the owner's session cookie: 403, no Set-Cookie" "403 0" "$CODE $(echo "$HDRS" | grep -ci '^set-cookie')"
  raw -X POST "$base/webhooks/other" -H 'Content-Type: application/json' -d '{}'
  chk P3-CSRF-05 $H "$via: /webhooks/other is not a handler (403 or 404), no cookie" "yes 0" "$([ "$CODE" = 403 ] || [ "$CODE" = 404 ] && echo yes || echo "$CODE") $(echo "$HDRS" | grep -ci '^set-cookie')"
  raw --path-as-is -b "$QA_TMP/jar-$A_OWNER.txt" "$base/webhooks/whatsapp/../../api/session"
  chk P3-CSRF-05 $H "$via: /webhooks/whatsapp/../../api/session never answers with the session JSON" 0 "$(echo "$BODY" | grep -c csrfToken)"
  raw --path-as-is "$base/webhooks/whatsapp/../../api/session"
  chk P3-CSRF-05 $H "$via: the same path without a session is not a login redirect to Keycloak" 0 "$(hdr location | grep -c realms)"
  raw "$base/webhooks/whatsapp?hub.mode=subscribe&hub.verify_token=wrong&hub.challenge=123"
  chk P3-CSRF-05 $H "$via: webhook GET with a wrong verify token: 403, no Set-Cookie" "403 0" "$CODE $(echo "$HDRS" | grep -ci '^set-cookie')"
done
summary
