#!/usr/bin/env bash
# ABOUTME: P3-MQA-SEC-04: attempts to change an approved message's body or recipient through arbitrary HTTP fields,
# ABOUTME: forged If-Match values, and Idempotency-Key reuse across tenants and messages. Expect strict refusals only.
set -uo pipefail
. "$(dirname "$0")/qa337.sh"
C=SEC-04
assert_safe_stack false || exit 1
qa_login testuser || exit 1
SUB() { # <customer> <contact> <text> [key] -> CODE/BODY of a submit in A
  req testuser "$A" GET "/api/customers/$1/follow-up-policy" -
  req testuser "$A" POST "/api/customers/$1/messages" "{\"contactId\":\"$2\",\"action\":\"GENERAL_CHECK_IN\",\"templateIntent\":\"GENERAL_FOLLOW_UP\",\"body\":\"$3\",\"locale\":\"es-419\",\"origin\":\"MANUAL\"}" -H "If-Match: $(etag)" -H "Idempotency-Key: ${4:-$(uuidgen)}"
}
APPR() { # <message> [key] -> approve the current revision
  req testuser "$A" GET "/api/messages/$1" -; local r; r=$(jb .template.revision)
  req testuser "$A" POST "/api/messages/$1/approvals" "{\"decision\":\"APPROVE\",\"templateRevision\":\"$r\"}" -H "If-Match: $(etag)" -H "Idempotency-Key: ${2:-$(uuidgen)}"
}
SUB "$RA" "$RA_C" "QA337 aprobado original"; PT=$(jb .messageId); setid PT "$PT"
APPR "$PT"; rec $C "fixture: approved message" "$( { [ "$(jb .status)" = APPROVED ]; } && echo PASS || echo FAIL)" "msg=${PT:0:8}"
req testuser "$A" GET "/api/messages/$PT" -; ET=$(etag); RECIP0=$(psqlq "select recipient_phone from dokene.outbound_messages where id='$PT'")
# tamper <label> <want-http> <METHOD> <path> <json|-> [curl args]: refused, and the approved row (status, version, body
# hash), the recipient, the counters and the stub are unchanged.
tamper() {
  local label=$1 want=$2 m=$3 p=$4 d=$5; shift 5; local r0 s0 st0
  r0=$(mrowv "$PT"); s0=$(snap1); st0=$(stub_all)
  req testuser "$A" "$m" "$p" "$d" "$@"
  rec $C "$label: $want, approved body/recipient/version unchanged, no side effect" \
    "$( { [ "$CODE" = "$want" ] && [ "$(mrowv "$PT")" = "$r0" ] && [ "$(psqlq "select recipient_phone from dokene.outbound_messages where id='$PT'")" = "$RECIP0" ] && [ "$(snap1)" = "$s0" ] && [ "$(stub_all)" = "$st0" ]; } && echo PASS || echo FAIL)" \
    "http=$CODE code=$(code) row_same=$([ "$(mrowv "$PT")" = "$r0" ] && echo y || echo n)"
}
K() { printf 'Idempotency-Key: %s' "$(uuidgen)"; }
# --- arbitrary fields that try to change body or recipient
tamper "send with a body replacing text and recipient" 400 POST "/api/messages/$PT/sends" '{"body":"QA337 cambiado","recipientPhone":"+56983379999"}' -H "If-Match: $ET" -H "$(K)"
tamper "send with an empty JSON object" 400 POST "/api/messages/$PT/sends" '{}' -H "If-Match: $ET" -H "$(K)"
tamper "re-approval carrying a new body" 400 POST "/api/messages/$PT/approvals" '{"decision":"APPROVE","templateRevision":"x","body":"QA337 cambiado"}' -H "If-Match: $ET" -H "$(K)"
tamper "re-approval carrying a recipient" 400 POST "/api/messages/$PT/approvals" '{"decision":"APPROVE","templateRevision":"x","contactId":"'"$IA_C"'"}' -H "If-Match: $ET" -H "$(K)"
tamper "cancellation carrying a body" 400 POST "/api/messages/$PT/cancellations" '{"note":"x","body":"QA337 cambiado"}' -H "If-Match: $ET" -H "$(K)"
for m in PUT PATCH DELETE; do tamper "$m on the message resource (no edit route)" 405 $m "/api/messages/$PT" '{"body":"QA337 cambiado"}' -H "If-Match: $ET"; done
tamper "re-approval of an approved message (valid fields)" 409 POST "/api/messages/$PT/approvals" "{\"decision\":\"APPROVE\",\"templateRevision\":\"$(req testuser "$A" GET "/api/messages/$PT" -; jb .template.revision)\"}" -H "If-Match: $ET" -H "$(K)"
# --- forged preconditions on send (each is refused before the global gate or by it; never an attempt)
tamper "send with a non-numeric If-Match" 400 POST "/api/messages/$PT/sends" - -H 'If-Match: "abc"' -H "$(K)"
tamper "send with a weak If-Match" 400 POST "/api/messages/$PT/sends" - -H "If-Match: W/$ET" -H "$(K)"
tamper "send with If-Match *" 400 POST "/api/messages/$PT/sends" - -H 'If-Match: *' -H "$(K)"
tamper "send with two If-Match values" 400 POST "/api/messages/$PT/sends" - -H "If-Match: $ET, \"0\"" -H "$(K)"
tamper "send without If-Match" 400 POST "/api/messages/$PT/sends" - -H "$(K)"
tamper "send with a stale If-Match" 409 POST "/api/messages/$PT/sends" - -H 'If-Match: "0"' -H "$(K)"
tamper "send without Idempotency-Key" 400 POST "/api/messages/$PT/sends" - -H "If-Match: $ET"
# --- submit with an extra recipient member
req testuser "$A" GET "/api/customers/$XA/follow-up-policy" -; PX=$(etag)
tamper "submit with an extra recipientPhone member" 400 POST "/api/customers/$XA/messages" "{\"contactId\":\"$XA_C\",\"recipientPhone\":\"+56983379999\",\"action\":\"GENERAL_CHECK_IN\",\"templateIntent\":\"GENERAL_FOLLOW_UP\",\"body\":\"QA337 x\",\"locale\":\"es-419\",\"origin\":\"MANUAL\"}" -H "If-Match: $PX" -H "$(K)"
tamper "submit with a tenantId member" 400 POST "/api/customers/$XA/messages" "{\"tenantId\":\"$B\",\"contactId\":\"$XA_C\",\"action\":\"GENERAL_CHECK_IN\",\"templateIntent\":\"GENERAL_FOLLOW_UP\",\"body\":\"QA337 x\",\"locale\":\"es-419\",\"origin\":\"MANUAL\"}" -H "If-Match: $PX" -H "$(K)"

# --- Idempotency-Key reuse
# 1. B's submit key reused in A: A gets its own new message, never B's stored response.
KB="qa337-b-submit-$IB"
SUB "$XA" "$XA_C" "QA337 clave de B" "$KB"; NX=$(jb .messageId)
rec $C "tenant B's submit key reused in A: a new tenant A message, not B's replay" "$( { [ "$CODE" = 201 ] && [ "$NX" != "$MB" ] && [ "$(psqlq "select tenant_id from dokene.outbound_messages where id='$NX'")" = "$A" ] && ! printf '%s' "$BODY" | grep -q "$MB"; } && echo PASS || echo FAIL)" "http=$CODE new=${NX:0:8} is_mb=$([ "$NX" = "$MB" ] && echo y || echo n)"
setid NX "$NX"
# 2. A's approval key reused in B on B's message: B's own result, never A's.
KA=qa337-a-approve-$(uuidgen)
APPR "$NX" "$KA"; rec $C "A approval with key KA" "$( { [ "$(jb .status)" = APPROVED ]; } && echo PASS || echo FAIL)" "msg=${NX:0:8}"
req testuser "$B" GET "/api/messages/$MB" -; RB=$(jb .template.revision)
req testuser "$B" POST "/api/messages/$MB/approvals" "{\"decision\":\"APPROVE\",\"templateRevision\":\"$RB\"}" -H "If-Match: $(etag)" -H "Idempotency-Key: $KA"
rec $C "A's approval key reused in B: B's own message is decided, A's response is not replayed" "$( { [ "$(jb .messageId)" = "$MB" ] && ! printf '%s' "$BODY" | grep -q "$NX"; } && echo PASS || echo FAIL)" "http=$CODE msg=$(jb .messageId | cut -c1-8) status=$(jb .status)"
# 3. Same key, same tenant, another message: refused as an idempotency conflict, the second message stays pending.
SUB "$IA" "$IA_C" "QA337 otra" 2>/dev/null; true   # IA still holds MA: expect MESSAGE_ALREADY_OPEN; MA is the target
req testuser "$A" GET "/api/messages/$MA" -; RMA=$(jb .template.revision); m0=$(mrowv "$MA")
req testuser "$A" POST "/api/messages/$MA/approvals" "{\"decision\":\"APPROVE\",\"templateRevision\":\"$RMA\"}" -H "If-Match: $(etag)" -H "Idempotency-Key: $KA"
rec $C "A's approval key reused on another A message: conflict, that message unchanged" "$( { [ "${CODE:0:1}" = 4 ] && [ "$(mrowv "$MA")" = "$m0" ]; } && echo PASS || echo FAIL)" "http=$CODE code=$(code) status=$(psqlq "select status from dokene.outbound_messages where id='$MA'")"
# 4. Byte-identical replay of the A approval: the same stored answer, no second approval row.
ap0=$(psqlq "select count(*) from dokene.outbound_message_approvals where message_id='$NX'")
req testuser "$A" GET "/api/messages/$NX" -
req testuser "$A" POST "/api/messages/$NX/approvals" "{\"decision\":\"APPROVE\",\"templateRevision\":\"$(jb .template.revision)\"}" -H "If-Match: \"$(( $(etag | tr -d '"') - 1 ))\"" -H "Idempotency-Key: $KA"
rec $C "control: replay of the A approval with its key returns the stored result, no new approval row" "$( { [ "$(jb .messageId)" = "$NX" ] && [ "$(psqlq "select count(*) from dokene.outbound_message_approvals where message_id='$NX'")" = "$ap0" ]; } && echo PASS || echo FAIL)" "http=$CODE code=$(code) approvals=$ap0"
