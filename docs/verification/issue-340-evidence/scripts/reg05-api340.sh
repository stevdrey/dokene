#!/usr/bin/env bash
# ABOUTME: Suite #340 REG-05, API half: rechecks closed defects #66, #68, #70, #72, #265, #266 and #293 on the candidate.
# ABOUTME: #293 briefly revokes one SELECT grant from the runtime role on this disposable QA database and restores it.
set -uo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)/scripts/lib/curl-protected.sh" || { echo "scripts/lib/curl-protected.sh not found" >&2; exit 1; }
. "$(dirname "$0")/qa340.sh"
C=REG-05
for u in testuser testviewer testadmin testoperator2; do qa_login "$u" || { echo "login $u failed (#66 fixture users)" >&2; exit 1; }; done
S0=$(stub_sends)

# #66: second synthetic identities and memberships are created through Keycloak's admin API and the real invitation flow
req testadmin "$A" GET /api/session -; chk $C "#66 extra identity testadmin signs in and holds ADMIN in workspace A" "200 ADMIN" "$CODE $(curl -s -b "$QA_TMP/jar-testadmin.txt" "$BFF/api/tenants" | jq -r --arg a "$A" '.[]|select(.tenantId==$a).role')"
chk $C "#66 extra identity testoperator2 holds OPERATOR in workspace B only" "1 OPERATOR" "$(curl -s -b "$QA_TMP/jar-testoperator2.txt" "$BFF/api/tenants" | jq -r '[length, .[0].role]|join(" ")')"

# #68: a stale or forged OIDC callback never ends on Spring's default login page
for base in "$BFF" "http://localhost:5173"; do
  hdrs=$(curl -s -o "$QA_TMP/cb.html" -D - "$base/login/oauth2/code/dokene?code=stale_code_qa340&state=stale_state_qa340" | tr -d '\r')
  loc=$(printf '%s\n' "$hdrs" | awk 'tolower($1)=="location:"{print $2}'); st=$(printf '%s\n' "$hdrs" | head -1 | awk '{print $2}')
  final=$(curl -s -L -o "$QA_TMP/cbf.html" -w '%{url_effective}' "$base/login/oauth2/code/dokene?code=stale_code_qa340&state=stale_state_qa340")
  rec $C "#68 stale callback via ${base#http://}: ends on the app (5173), not /login?error or 'Please sign in'" \
    "$( { case $final in http://localhost:5173*) true;; *) false;; esac && ! printf '%s' "$loc" | grep -q '/login?error' && ! grep -qiE 'Please sign in|Login with OAuth' "$QA_TMP/cb.html" "$QA_TMP/cbf.html"; } && echo PASS || echo FAIL)" \
    "http=$st location=${loc:-none} final=${final%%\?*}?$(printf '%s' "${final#*\?}" | grep -oE 'error=[a-z_]+' || true)"
done

# #72: unparseable phone is a field error (API); the browser message is in browser-notes
req testuser "$A" POST /api/customers '{"displayName":"QA340 Teléfono malo","phones":[{"number":"12345","region":"CR","primary":true}]}'
chk $C "#72 unparseable CR phone -> 400 naming phones[0].number" "400 phones[0].number" "$CODE $(jb .field)"

# #265: one denied any-of request writes exactly one AUTHORIZATION_DENIED row (VIEWER cancel of a pending message)
req testuser "$A" GET /api/templates -
for it in $(printf '%s' "$BODY" | jq -r '.items[] | select(.mapped and .enabled and (.sendable | not)) | "\(.templateIntent):\(.version)"'); do
  req testuser "$A" POST "/api/templates/${it%%:*}/approval-refresh" - -H "If-Match: \"${it#*:}\""
  rec $C "setup: stale template ${it%%:*} approval re-checked (no send)" NOTE "http=$CODE sendable=$(jb .sendable)"
done
mkcust R265 "QA340 Denegación Única $(date +%s)" || exit 1
mkmsg M265 testuser "$R265" "$R265_C" || exit 1
req testviewer "$A" GET "/api/messages/$M265" -; MV=$(etag)
denied $C testviewer "$A" POST "/api/messages/$M265/cancellations" '{"note":"QA340"}' MESSAGE_DRAFT "#265 VIEWER cancels a pending message" -H "If-Match: $MV" -H "Idempotency-Key: $(uuidgen)"
req testuser "$A" GET "/api/messages/$M265" -
req testuser "$A" POST "/api/messages/$M265/cancellations" '{"note":"QA340 limpieza"}' -H "If-Match: $(etag)" -H "Idempotency-Key: $(uuidgen)"
rec $C "cleanup: pending #265 message cancelled by the OWNER" "$( { [ "$CODE" = 200 ] || [ "$CODE" = 201 ]; } && echo PASS || echo FAIL)" "http=$CODE status=$(jb .status)"

# #266: paths under /webhooks other than the callback set no session cookie and do not echo the challenge. The verify
# token is the stack's synthetic canary, read from the backend environment (as in #337) rather than written here.
VT=$(docker compose -f "$WT/compose.yaml" exec -T backend sh -c 'printf %s "$DOKENE_INTEGRATION_SECRETS_LOCAL_ENTRIES_1_VALUE"')
for p in /webhooks /webhooks/ /webhooks/other /webhooks/whatsapp/ /webhooks/whatsapp/status /webhooks/WhatsApp /webhooks/whatsapp2; do
  hdrs=$(curl -s -o "$QA_TMP/wh.txt" -D - "$BFF$p?hub.mode=subscribe&hub.verify_token=$VT&hub.challenge=qa340echo" | tr -d '\r')
  st=$(printf '%s\n' "$hdrs" | head -1 | awk '{print $2}')
  rec $C "#266 GET $p -> refused, no Set-Cookie, no challenge echo" \
    "$( { [ "$st" = 403 ] || [ "$st" = 404 ] || [ "$st" = 401 ]; } && ! printf '%s\n' "$hdrs" | grep -qi '^set-cookie:' && ! grep -q qa340echo "$QA_TMP/wh.txt" && echo PASS || echo FAIL)" \
    "http=$st cookie=$(printf '%s\n' "$hdrs" | grep -ci '^set-cookie:')"
done
hdrs=$(curl -s -o "$QA_TMP/wh.txt" -D - "$BFF/webhooks/whatsapp?hub.mode=subscribe&hub.verify_token=$VT&hub.challenge=qa340echo" | tr -d '\r')
rec $C "#266 control: the real callback answers the verification challenge without a cookie" \
  "$( { [ "$(printf '%s\n' "$hdrs" | head -1 | awk '{print $2}')" = 200 ] && grep -qx qa340echo "$QA_TMP/wh.txt" && ! printf '%s\n' "$hdrs" | grep -qi '^set-cookie:'; } && echo PASS || echo FAIL)" "http=$(printf '%s\n' "$hdrs" | head -1 | awk '{print $2}')"

# #293: an unexpected failure on a Phase 3 route is the closed 500 problem body (disposable DB only; grant restored)
probe293() { # <table> <path>
  psqlq "revoke select on dokene.$1 from dokene_runtime" >/dev/null
  req testuser "$A" GET "$2" -
  local ct; ct=$(hdr content-type)
  psqlq "grant select on dokene.$1 to dokene_runtime" >/dev/null
  rec $C "#293 GET $2 with SELECT on $1 revoked -> 500 application/problem+json {type,title,status,code=INTERNAL_ERROR}, no path/timestamp/exception" \
    "$( { [ "$CODE" = 500 ] && printf '%s' "$ct" | grep -q 'application/problem+json' && [ "$(jb '[keys[]]|sort|join(",")')" = code,status,title,type ] && [ "$(jb .code)" = INTERNAL_ERROR ] \
        && ! printf '%s' "$BODY" | grep -qiE 'timestamp|path|exception|permission denied|PSQL'; } && echo PASS || echo FAIL)" \
    "http=$CODE type=${ct%%;*} keys=$(jb '[keys[]]|sort|join(",")') code=$(jb .code)"
  req testuser "$A" GET "$2" -
  chk $C "#293 grant on $1 restored, route answers 200 again" "200 t" "$CODE $(psqlq "select has_table_privilege('dokene_runtime','dokene.$1','SELECT')")"
}
probe293 tenant_provider_templates /api/templates
probe293 tenant_messaging_integrations /api/integrations/whatsapp

chk $C "no provider send during REG-05" "$S0" "$(stub_sends)"
qa_finish
