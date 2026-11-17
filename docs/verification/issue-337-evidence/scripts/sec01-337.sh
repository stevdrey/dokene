#!/usr/bin/env bash
# ABOUTME: P3-MQA-SEC-01: the role matrix over direct authenticated HTTP (no UI) on the submit, draft, approve, cancel,
# ABOUTME: send, resolution, template and integration routes, including OPERATOR self-approval. Global switch stays off.
set -uo pipefail
. "$(dirname "$0")/qa337.sh"
C=SEC-01
assert_safe_stack false || exit 1
for u in testuser testadmin testoperator testviewer testnone; do qa_login "$u" || exit 1; done
SUBMIT() { # <user> -> submit a manual GENERAL_FOLLOW_UP message on RA; sets CODE/BODY
  req "$1" "$A" GET "/api/customers/$RA/follow-up-policy" -; local pv; pv=$(etag)
  req "$1" "$A" POST "/api/customers/$RA/messages" "{\"contactId\":\"$RA_C\",\"action\":\"GENERAL_CHECK_IN\",\"templateIntent\":\"GENERAL_FOLLOW_UP\",\"body\":\"QA337 roles $1\",\"locale\":\"es-419\",\"origin\":\"MANUAL\"}" -H "If-Match: $pv" -H "Idempotency-Key: $(uuidgen)"
}
APPROVAL() { local r; req testuser "$A" GET "/api/messages/$1" -; r=$(jb .template.revision); EM=$(etag); AB="{\"decision\":\"APPROVE\",\"templateRevision\":\"$r\"}"; }
CUR() { req testuser "$A" GET "/api/messages/$1" -; EM=$(etag); }

# --- submit and AI draft
req testviewer "$A" GET "/api/customers/$RA/follow-up-policy" -; PV=$(etag)
denied $C testviewer "$A" POST "/api/customers/$RA/messages" "{\"contactId\":\"$RA_C\",\"action\":\"GENERAL_CHECK_IN\",\"templateIntent\":\"GENERAL_FOLLOW_UP\",\"body\":\"QA337 viewer\",\"locale\":\"es-419\",\"origin\":\"MANUAL\"}" MESSAGE_DRAFT "VIEWER submit" -H "If-Match: $PV" -H "Idempotency-Key: $(uuidgen)"
a0=$(ai_n); denied $C testviewer "$A" POST "/api/customers/$RA/draft" '{}' MESSAGE_DRAFT "VIEWER AI draft" -H "If-Match: $PV"
rec $C "VIEWER AI draft refused before any AI invocation" "$( { [ "$(ai_n)" = "$a0" ]; } && echo PASS || echo FAIL)" "ai_outcome_rows=+$(( $(ai_n) - a0 ))"
denied $C testnone "$A" GET /api/messages - TENANT_READ "non-member list messages (no tenant context)"

# --- OPERATOR submits; VIEWER cannot approve or cancel it; OPERATOR approves its own message (no second approver)
SUBMIT testoperator; P1=$(jb .messageId)
rec $C "OPERATOR submit" "$( { [ "$CODE" = 201 ]; } && echo PASS || echo FAIL)" "http=$CODE status=$(jb .status) msg=${P1:0:8}"
APPROVAL "$P1"
denied $C testviewer "$A" POST "/api/messages/$P1/approvals" "$AB" MESSAGE_APPROVE "VIEWER approve" -H "If-Match: $EM" -H "Idempotency-Key: $(uuidgen)"
denied $C testviewer "$A" POST "/api/messages/$P1/cancellations" '{"note":"QA337 viewer"}' MESSAGE_DRAFT "VIEWER cancel (any-of guard, #265)" -H "If-Match: $EM" -H "Idempotency-Key: $(uuidgen)"
req testoperator "$A" POST "/api/messages/$P1/approvals" "$AB" -H "If-Match: $EM" -H "Idempotency-Key: $(uuidgen)"
rec $C "OPERATOR self-approval of its own message is allowed (matrix: OPERATOR has MESSAGE_APPROVE)" "$( { [ "$(jb .status)" = APPROVED ]; } && echo PASS || echo FAIL)" "http=$CODE status=$(jb .status)"

# --- send: VIEWER denied; every sending role is authorized and stops at the global gate (switch off), no attempt
CUR "$P1"; denied $C testviewer "$A" POST "/api/messages/$P1/sends" - MESSAGE_SEND "VIEWER send" -H "If-Match: $EM" -H "Idempotency-Key: $(uuidgen)"
for u in testuser testadmin testoperator; do
  CUR "$P1"; passes_auth $C $u "$A" POST "/api/messages/$P1/sends" - 409 OUTBOUND_DISABLED_GLOBALLY "$u send" -H "If-Match: $EM" -H "Idempotency-Key: $(uuidgen)"
done
# --- T12 resolution: INTEGRATION_MANAGE only; OWNER/ADMIN reach the state check (APPROVED cannot be resolved)
for u in testoperator testviewer; do CUR "$P1"; denied $C $u "$A" POST "/api/messages/$P1/resolutions" '{"outcome":"FAILED","note":"QA337"}' INTEGRATION_MANAGE "$u resolve"  -H "If-Match: $EM" -H "Idempotency-Key: $(uuidgen)"; done
for u in testuser testadmin; do CUR "$P1"; passes_auth $C $u "$A" POST "/api/messages/$P1/resolutions" '{"outcome":"FAILED","note":"QA337"}' 409 INVALID_TRANSITION "$u resolve APPROVED" -H "If-Match: $EM" -H "Idempotency-Key: $(uuidgen)"; done
CUR "$P1"; req testoperator "$A" POST "/api/messages/$P1/cancellations" '{"note":"QA337 operator"}' -H "If-Match: $EM" -H "Idempotency-Key: $(uuidgen)"
rec $C "OPERATOR cancels the approved message" "$( { [ "$(jb .status)" = CANCELLED ]; } && echo PASS || echo FAIL)" "http=$CODE status=$(jb .status)"

# --- ADMIN: submit, approve someone else's message, cancel
SUBMIT testuser; P2=$(jb .messageId); APPROVAL "$P2"
req testadmin "$A" POST "/api/messages/$P2/approvals" "$AB" -H "If-Match: $EM" -H "Idempotency-Key: $(uuidgen)"
rec $C "ADMIN approves the OWNER's message" "$( { [ "$(jb .status)" = APPROVED ]; } && echo PASS || echo FAIL)" "http=$CODE status=$(jb .status) msg=${P2:0:8}"
CUR "$P2"; req testadmin "$A" POST "/api/messages/$P2/cancellations" '{"note":"QA337 admin"}' -H "If-Match: $EM" -H "Idempotency-Key: $(uuidgen)"
rec $C "ADMIN cancels" "$( { [ "$(jb .status)" = CANCELLED ]; } && echo PASS || echo FAIL)" "http=$CODE status=$(jb .status)"
SUBMIT testadmin; P3=$(jb .messageId); rec $C "ADMIN submit" "$( { [ "$CODE" = 201 ]; } && echo PASS || echo FAIL)" "http=$CODE msg=${P3:0:8}"
CUR "$P3"; req testuser "$A" POST "/api/messages/$P3/cancellations" '{"note":"QA337 owner"}' -H "If-Match: $EM" -H "Idempotency-Key: $(uuidgen)"
rec $C "OWNER cancels the ADMIN's message" "$( { [ "$(jb .status)" = CANCELLED ]; } && echo PASS || echo FAIL)" "http=$CODE status=$(jb .status)"

# --- templates: VIEWER cannot write; writers are authorized and stop at the stale precondition (no stub call)
req testuser "$A" GET /api/templates -
TB=$(jb '.items[]|select(.templateIntent=="GENERAL_FOLLOW_UP")|{providerTemplateName,language,parameterNames,enabled}' | jq -c .)
TV=$(jb '.items[]|select(.templateIntent=="GENERAL_FOLLOW_UP")|.version')
denied $C testviewer "$A" PUT /api/templates/GENERAL_FOLLOW_UP "$TB" TEMPLATE_WRITE "VIEWER template write" -H "If-Match: \"$TV\""
denied $C testviewer "$A" POST /api/templates/GENERAL_FOLLOW_UP/approval-refresh - TEMPLATE_WRITE "VIEWER template refresh" -H "If-Match: \"$TV\""
for u in testuser testadmin testoperator; do
  passes_auth $C $u "$A" PUT /api/templates/GENERAL_FOLLOW_UP "$TB" "${STALE_HTTP:-409}" "${STALE_CODE:-STALE_VERSION}" "$u template write (stale If-Match)" -H 'If-Match: "999999"'
  passes_auth $C $u "$A" POST /api/templates/GENERAL_FOLLOW_UP/approval-refresh - "${STALE_HTTP:-409}" "${STALE_CODE:-STALE_VERSION}" "$u template refresh (stale If-Match)" -H 'If-Match: "999999"'
done

# --- integration: OPERATOR/VIEWER denied on every manage route; OWNER/ADMIN authorized, stopped by the stale precondition
req testuser "$A" GET /api/integrations/whatsapp -; IE=$(etag)
IB_CFG=$(jb '{phoneNumberId,businessAccountId,displayPhoneNumber,enabled,secretRef}' | jq -c .)
for u in testoperator testviewer; do
  denied $C $u "$A" PUT /api/integrations/whatsapp "$IB_CFG" INTEGRATION_MANAGE "$u integration update" -H "If-Match: $IE"
  denied $C $u "$A" PUT /api/integrations/whatsapp/outbound '{"enabled":true}' INTEGRATION_MANAGE "$u tenant outbound switch" -H "If-Match: $IE"
  denied $C $u "$A" POST /api/integrations/whatsapp/ownership-verifications - INTEGRATION_MANAGE "$u ownership proof" -H "If-Match: $IE"
  denied $C $u "$A" POST /api/integrations/whatsapp/credential-rotations - INTEGRATION_MANAGE "$u credential rotation" -H "If-Match: $IE"
done
for u in testuser testadmin; do
  passes_auth $C $u "$A" PUT /api/integrations/whatsapp/outbound '{"enabled":true}' "${STALE_HTTP:-409}" "${STALE_CODE:-STALE_VERSION}" "$u tenant outbound switch (stale If-Match)" -H 'If-Match: "999999"'
  passes_auth $C $u "$A" POST /api/integrations/whatsapp/ownership-verifications - "${STALE_HTTP:-409}" "${STALE_CODE:-STALE_VERSION}" "$u ownership proof (stale If-Match)" -H 'If-Match: "999999"'
done
req testuser "$A" GET /api/integrations/whatsapp -
rec $C "integration unchanged after the probes" "$( { [ "$(etag)" = "$IE" ] && [ "$(jb .outboundEnabled)" = false ]; } && echo PASS || echo FAIL)" "etag_same=$([ "$(etag)" = "$IE" ] && echo y || echo n) outbound=$(jb .outboundEnabled)"
setid P1 "$P1"; setid P2 "$P2"; setid P3 "$P3"
