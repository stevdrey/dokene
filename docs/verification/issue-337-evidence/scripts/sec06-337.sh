#!/usr/bin/env bash
# ABOUTME: P3-MQA-SEC-06: the webhook GET challenge and signed POST exemption cover only /webhooks/whatsapp; sibling
# ABOUTME: paths set no cookie (#266); one denied any-of request writes one denial row (#265). No cookie jar for webhooks.
set -uo pipefail
. "$(dirname "$0")/qa337.sh"
C=SEC-06
qa_login testviewer || exit 1
# raw <METHOD> <path> [curl args]: no cookie jar; sets RCODE, RCOOKIE (y/n) and RBODY.
raw() {
  local m=$1 p=$2; shift 2; local out
  out=$(curl -s -i -X "$m" -H 'Expect:' "$@" "$BFF$p" 2>/dev/null)
  RCODE=$(printf '%s\n' "$out" | head -1 | awk '{print $2}')
  RCOOKIE=n; printf '%s\n' "$out" | sed '/^\r\{0,1\}$/q' | grep -qi '^set-cookie:' && RCOOKIE=y
  RBODY=$(printf '%s\n' "$out" | sed '1,/^\r\{0,1\}$/d')
}
CH=qa337-challenge-$RANDOM
VT=$(docker compose -f "$WT/compose.yaml" exec -T backend sh -c 'printf %s "$DOKENE_INTEGRATION_SECRETS_LOCAL_ENTRIES_1_VALUE"')
q() { printf 'hub.mode=subscribe&hub.verify_token=%s&hub.challenge=%s' "$1" "$CH"; }
raw GET "/webhooks/whatsapp?$(q "$VT")"
rec $C "GET challenge with the verify token: 200 echoing the challenge, no cookie" "$( { [ "$RCODE" = 200 ] && [ "$RBODY" = "$CH" ] && [ $RCOOKIE = n ]; } && echo PASS || echo FAIL)" "http=$RCODE echo_ok=$([ "$RBODY" = "$CH" ] && echo y || echo n) cookie=$RCOOKIE"
raw GET "/webhooks/whatsapp?$(q wrong-token)"
rec $C "GET challenge with a wrong token: 403, no echo, no cookie" "$( { [ "$RCODE" = 403 ] && ! printf '%s' "$RBODY" | grep -q "$CH" && [ $RCOOKIE = n ]; } && echo PASS || echo FAIL)" "http=$RCODE cookie=$RCOOKIE"
# A signed callback for an unknown phone number id: accepted (200), stores nothing, no cookie.
i0=$(inbox_rows)
wh_body "$OUTD/sec06.json" 100000000000099 "wamid.SYNTHETIC-qa337-s06:delivered"; SIG=$(wh_sig "$OUTD/sec06.json")
wh_post "$OUTD/sec06.json" "$SIG"
rec $C "signed POST to /webhooks/whatsapp: 200 without session or CSRF, no cookie" "$( { [ "$WCODE" = 200 ] && no_cookie && [ "$(inbox_rows)" = "$i0" ]; } && echo PASS || echo FAIL)" "http=$WCODE inbox_delta=$(( $(inbox_rows) - i0 ))"
# Sibling paths: the exemption must not extend to them, and none may mint a session.
for p in /webhooks /webhooks/ /webhooks/other /webhooks/whatsapp/ /webhooks/whatsapp/status /webhooks/WhatsApp /webhooks/whatsapp2 "/webhooks/whatsapp;x=1" /webhooks/whatsapp/..%2Fother /webhooks/%2e%2e/api/session; do
  raw GET "$p?$(q "$VT")"; g=$RCODE; gc=$RCOOKIE; gb=$([ "$RBODY" = "$CH" ] && echo y || echo n)
  raw POST "$p" -H 'Content-Type: application/json' -H "X-Hub-Signature-256: $SIG" --data-binary "@$OUTD/sec06.json"; pc=$RCODE; pcc=$RCOOKIE
  rec $C "sibling $p: GET and signed POST refused, no challenge echo, no cookie, nothing stored" \
    "$( { [ "${g:0:1}" = 4 ] && [ "${pc:0:1}" = 4 ] && [ $gc = n ] && [ $pcc = n ] && [ $gb = n ] && [ "$(inbox_rows)" = "$i0" ]; } && echo PASS || echo FAIL)" "get=$g post=$pc cookie=$gc/$pcc echo=$gb"
done
# The exemption is path-bound, not method-bound: other methods on the callback path set no cookie either.
for m in PUT DELETE PATCH; do raw $m /webhooks/whatsapp -H 'Content-Type: application/json' --data-binary "@$OUTD/sec06.json"
  rec $C "$m /webhooks/whatsapp: refused, no cookie" "$( { [ "${RCODE:0:1}" = 4 ] && [ $RCOOKIE = n ]; } && echo PASS || echo FAIL)" "http=$RCODE cookie=$RCOOKIE"; done
# An /api route is never covered by the webhook exemption: signed but session-less POST is refused.
raw POST "/api/messages/$MA/approvals" -H 'Content-Type: application/json' -H "X-Hub-Signature-256: $SIG" --data-binary "@$OUTD/sec06.json"
rec $C "signed but session-less POST to an /api route: refused, nothing written" "$( { [ "${RCODE:0:1}" = 4 ] && [ "$(psqlq "select status from dokene.outbound_messages where id='$MA'")" = PENDING_APPROVAL ]; } && echo PASS || echo FAIL)" "http=$RCODE cookie=$RCOOKIE"
# #265: one denied any-of request = one AUTHORIZATION_DENIED row, for a pending and for an approved message.
for m in "$MA:pending" "$PT:approved"; do id=${m%%:*}
  req testuser "$A" GET "/api/messages/$id" -; E=$(etag)
  denied $C testviewer "$A" POST "/api/messages/$id/cancellations" '{"note":"QA337 viewer"}' MESSAGE_DRAFT "#265 VIEWER cancel ${m#*:} message" -H "If-Match: $E" -H "Idempotency-Key: $(uuidgen)"
done
# Recorded FAIL lines make the exit status nonzero.
qa_finish
