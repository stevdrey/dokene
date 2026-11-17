#!/usr/bin/env bash
# ABOUTME: P3-MQA-SEC-03: unsafe Phase 3 /api operations without a CSRF token, with another session's or a forged token,
# ABOUTME: with an ended session (app logout, IdP back-channel logout), after membership revocation and cross-origin.
set -uo pipefail
. "$(dirname "$0")/qa337.sh"
C=SEC-03
assert_safe_stack false || exit 1
for u in testuser testadmin testoperator testoperator2; do qa_login "$u" || exit 1; done
# Fixtures: a pending message on RA (approve/cancel targets); XA has no open message (submit and AI draft target).
req testuser "$A" GET "/api/customers/$RA/follow-up-policy" -
req testuser "$A" POST "/api/customers/$RA/messages" "{\"contactId\":\"$RA_C\",\"action\":\"GENERAL_CHECK_IN\",\"templateIntent\":\"GENERAL_FOLLOW_UP\",\"body\":\"QA337 csrf\",\"locale\":\"es-419\",\"origin\":\"MANUAL\"}" -H "If-Match: $(etag)" -H "Idempotency-Key: $(uuidgen)"
PC=$(jb .messageId); setid PC "$PC"; [ "$CODE" = 201 ] || { echo "fixture submit $CODE $(code)" >&2; exit 1; }
# fresh_args <op>: the method, path, body and valid preconditions of one unsafe operation, read just before the call,
# so only the CSRF/session/membership check can refuse it. Sets OM OP OD and OH (array).
fresh_args() {
  OH=(-H "Idempotency-Key: $(uuidgen)")
  case $1 in
    submit) req testuser "$A" GET "/api/customers/$XA/follow-up-policy" -; OM=POST OP="/api/customers/$XA/messages"
            OD="{\"contactId\":\"$XA_C\",\"action\":\"GENERAL_CHECK_IN\",\"templateIntent\":\"GENERAL_FOLLOW_UP\",\"body\":\"QA337 csrf submit\",\"locale\":\"es-419\",\"origin\":\"MANUAL\"}" ;;
    draft) req testuser "$A" GET "/api/customers/$XA/follow-up-policy" -; OM=POST OP="/api/customers/$XA/draft" OD='{}' ;;
    approve) req testuser "$A" GET "/api/messages/$PC" -; OM=POST OP="/api/messages/$PC/approvals" OD="{\"decision\":\"APPROVE\",\"templateRevision\":\"$(jb .template.revision)\"}" ;;
    cancel) req testuser "$A" GET "/api/messages/$PC" -; OM=POST OP="/api/messages/$PC/cancellations" OD='{"note":"QA337 csrf"}' ;;
    send) req testuser "$A" GET "/api/messages/$PC" -; OM=POST OP="/api/messages/$PC/sends" OD=- ;;
    consent) req testuser "$A" GET "/api/customers/$RA/contact-policy" -; OM=PUT OP="/api/customers/$RA/contacts/$RA_C/consents/WHATSAPP" OD='{"status":"REVOKED","source":"CUSTOMER_VERBAL"}' ;;
    template) req testuser "$A" GET /api/templates -; OM=PUT OP=/api/templates/GENERAL_FOLLOW_UP
              OD=$(jb '.items[]|select(.templateIntent=="GENERAL_FOLLOW_UP")|{providerTemplateName:"qa337_csrf",language,parameterNames,enabled}' | jq -c .)
              OH+=(-H "If-Match: \"$(jb '.items[]|select(.templateIntent=="GENERAL_FOLLOW_UP")|.version')\""); return ;;
    outbound) req testuser "$A" GET /api/integrations/whatsapp -; OM=PUT OP=/api/integrations/whatsapp/outbound OD='{"enabled":true}' ;;
  esac
  OH+=(-H "If-Match: $(etag)")
}
OPS="submit draft approve cancel send consent template outbound"
world() { printf '%s|%s|ai=%s|pc=%s' "$(snap1)" "$(stub_all)" "$(ai_n)" "$(mrowv "$PC")"; }
# refuse <mode-label> <want-http> <user> <op> [curl args]: CSRF_MODE=none so req adds no token; the caller adds its own.
refuse() {
  local label=$1 want=$2 u=$3 op=$4; shift 4; local w0
  fresh_args "$op"; w0=$(world)
  CSRF_MODE=none req "$u" "$A" "$OM" "$OP" "$OD" "${OH[@]}" "$@"
  rec $C "$label: $op refused $want, no state, AI or provider change" "$( { [ "$CODE" = "$want" ] && [ "$(world)" = "$w0" ]; } && echo PASS || echo FAIL)" "http=$CODE same=$([ "$(world)" = "$w0" ] && echo y || echo n)"
}
OTHER=$(csrf testadmin)
for op in $OPS; do
  refuse "no CSRF token" 403 testuser $op
  refuse "another session's CSRF token" 403 testuser $op -H "X-CSRF-TOKEN: $OTHER"
  refuse "forged CSRF token" 403 testuser $op -H "X-CSRF-TOKEN: qa337-$(uuidgen)"
  refuse "cross-origin request without a token" 403 testuser $op -H 'Origin: http://qa337-attacker.test' -H 'Sec-Fetch-Site: cross-site'
done
# CORS: a foreign origin gets no CORS grant on the preflight or the response.
out=$(curl -s -i -X OPTIONS -H 'Origin: http://qa337-attacker.test' -H 'Access-Control-Request-Method: POST' -H 'Access-Control-Request-Headers: content-type,x-csrf-token,x-tenant-id' "$BFF/api/messages/$PC/approvals")
rec $C "CORS preflight from a foreign origin gets no Access-Control-Allow-Origin" "$( { ! printf '%s' "$out" | grep -qi '^access-control-allow-origin'; } && echo PASS || echo FAIL)" "http=$(printf '%s' "$out" | head -1 | awk '{print $2}')"
# Positive control: the same operation with the session's own token goes through (approve, then it is cancelled below).
fresh_args approve; req testuser "$A" "$OM" "$OP" "$OD" "${OH[@]}"
rec $C "control: the session's own token is accepted (approve)" "$( { [ "$(jb .status)" = APPROVED ]; } && echo PASS || echo FAIL)" "http=$CODE"

# --- ended session: app logout. The old cookie and its token are replayed afterwards.
cp "$QA_TMP/jar-testoperator.txt" "$QA_TMP/jar-opold.txt"; TOK=$(csrf testoperator)
lo=$(curl -s -o /dev/null -w '%{http_code}' -X POST -b "$QA_TMP/jar-testoperator.txt" -c "$QA_TMP/jar-testoperator.txt" -H @<(printf 'X-CSRF-TOKEN: %s\n' "$TOK") "$BFF/logout")
s=$(curl -s -o /dev/null -w '%{http_code}' -b "$QA_TMP/jar-opold.txt" "$BFF/api/session")
rec $C "app logout ends the session (old cookie: /api/session not authenticated)" "$( { [ "$(curl -s -b "$QA_TMP/jar-opold.txt" "$BFF/api/session" | jq -r '.authenticated // false')" != true ]; } && echo PASS || echo FAIL)" "logout=$lo session_http=$s"
for op in approve cancel send; do refuse "after app logout, old cookie and token" 401 opold $op -H "X-CSRF-TOKEN: $TOK"; done
# --- ended session: IdP back-channel logout (Keycloak admin API) of a live session.
qa_login testadmin; cp "$QA_TMP/jar-testadmin.txt" "$QA_TMP/jar-adold.txt"; TOK=$(csrf testadmin)
bash "$QA_HOME/scripts/kc-logout.sh" testadmin >/dev/null; sleep 2
for op in approve cancel send; do refuse "after IdP back-channel logout, old cookie and token" 401 adold $op -H "X-CSRF-TOKEN: $TOK"; done

# --- revoked membership: testoperator2 keeps a live session in B while the ADMIN revokes its membership.
OP2=$(identity testoperator2)
req testoperator2 "$B" GET "/api/messages/$MB" -; EB=$(etag); RB=$(jb .template.revision)
rec $C "control: active OPERATOR reads B message before revocation" "$( { [ "$CODE" = 200 ]; } && echo PASS || echo FAIL)" "http=$CODE"
req testuser "$B" DELETE "/api/memberships/$OP2" -
rec $C "ADMIN revokes the OPERATOR membership in B" "$( { [ "$CODE" = 204 ]; } && echo PASS || echo FAIL)" "http=$CODE"
b0=$(mrowv "$MB"); w0=$(snap1); d0=$(deny_n)
req testoperator2 "$B" POST "/api/messages/$MB/approvals" "{\"decision\":\"APPROVE\",\"templateRevision\":\"$RB\"}" -H "If-Match: $EB" -H "Idempotency-Key: $(uuidgen)"; c1=$CODE
req testoperator2 "$B" POST "/api/messages/$MB/cancellations" '{"note":"QA337 revoked"}' -H "If-Match: $EB" -H "Idempotency-Key: $(uuidgen)"; c2=$CODE
req testoperator2 "$B" GET "/api/messages/$MB" -; c3=$CODE; leak=$(printf '%s' "$BODY" | grep -c "QA337" || true)
rec $C "revoked member's live session: approve/cancel/read in B refused, nothing written" "$( { [ "$c1" = 403 ] && [ "$c2" = 403 ] && [ "$c3" = 403 ] && [ "$leak" = 0 ] && [ "$(mrowv "$MB")" = "$b0" ] && [ "$(snap1)" = "$w0" ]; } && echo PASS || echo FAIL)" "approve=$c1 cancel=$c2 read=$c3 leak=$leak denials=+$(( $(deny_n) - d0 ))"
req testuser "$B" POST "/api/memberships/$OP2/reactivation" -; r1=$CODE; req testoperator2 - POST "/api/tenants/$B/invitation/acceptance" -
rec $C "cleanup: membership reactivated and accepted" "$( { [ "$CODE" = 200 ]; } && echo PASS || echo FAIL)" "reactivate=$r1 accept=$CODE"
# Cleanup: cancel PC (approved above).
req testuser "$A" GET "/api/messages/$PC" -; req testuser "$A" POST "/api/messages/$PC/cancellations" '{"note":"QA337 cleanup"}' -H "If-Match: $(etag)" -H "Idempotency-Key: $(uuidgen)"
echo "cleanup PC: $CODE $(jb .status)"
