#!/usr/bin/env bash
# ABOUTME: Suite #340 REG-03, API half for one AI mode (unavailable|disabled): recommendation and draft degrade to 200
# ABOUTME: AI_UNAVAILABLE with the right reason and write nothing; manual operations and message history still work.
# Also re-checks session expiry (#70): a mutation with an ended session's cookie and CSRF token gets 401, not 403.
set -uo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)/scripts/lib/curl-protected.sh" || { echo "scripts/lib/curl-protected.sh not found" >&2; exit 1; }
. "$(dirname "$0")/qa340.sh"
MODE=${1:?usage: reg03-api340.sh unavailable|disabled}
C=REG-03
case $MODE in unavailable) WANT_REASON=UNAVAILABLE WANT_RETRY=true ;; disabled) WANT_REASON=NOT_AVAILABLE WANT_RETRY=false ;; *) exit 2 ;; esac
qa_login testuser || exit 1
rec $C "[$MODE] backend AI provider (unavailable mode = openai adapter on a closed loopback port, see report)" NOTE "$(docker compose -f "$WT/compose.yaml" exec -T backend sh -c 'echo provider=${DOKENE_AI_PROVIDER:-<empty>}')"
mkcust X "QA340 Modo IA $MODE $(date +%s)" || exit 1
S0=$(snap1)
req testuser "$A" GET "/api/customers/$X/follow-up-policy" -; FV=$(etag)
for op in recommendation draft; do
  req testuser "$A" POST "/api/customers/$X/$op" '{}'
  rec $C "[$MODE] $op -> 200 AI_UNAVAILABLE reason=$WANT_REASON retryable=$WANT_RETRY, evaluation kept, no raw provider error" \
    "$( { [ "$CODE" = 200 ] && [ "$(jb .status)" = AI_UNAVAILABLE ] && [ "$(jb .unavailableReason)" = "$WANT_REASON" ] && [ "$(jb .retryable)" = "$WANT_RETRY" ] \
        && [ "$(jb .evaluation.status)" = DUE ] && ! printf '%s' "$BODY" | grep -qiE 'exception|stack|connection refused|127\.0\.0\.1|openai'; } && echo PASS || echo FAIL)" \
    "http=$CODE status=$(jb .status) reason=$(jb .unavailableReason) retryable=$(jb .retryable) eval=$(jb .evaluation.status)"
done
req testuser "$A" GET "/api/customers/$X/follow-up-policy" -
chk $C "[$MODE] failed AI calls changed no follow-up policy" "$FV" "$(etag)"
chk $C "[$MODE] failed AI calls wrote no message, attempt, customer or other audit row besides AI outcomes" \
  "$(printf '%s' "$S0" | cut -d/ -f1-4)" "$(snap1 | cut -d/ -f1-4)"
req testuser "$A" PUT "/api/customers/$X/follow-up-snooze" "$(jq -cn --arg d "$(date -u -d '+2 days' +%F)" '{until:$d}')" -H "If-Match: $FV"
chk $C "[$MODE] manual snooze still works" 200 "$CODE"
req testuser "$A" POST "/api/customers/$X/manual-follow-ups" '{"notes":"manual QA340"}' -H "If-Match: $(etag)" -H "Idempotency-Key: qa340-$MODE-$X"
chk $C "[$MODE] manual completion still works (early touch on a snoozed customer)" 201 "$CODE"
req testuser "$A" GET "/api/messages?customerId=$QA333_ANA&limit=20" -
rec $C "[$MODE] message history of a customer with messages still readable" "$( { [ "$CODE" = 200 ] && [ "$(jb '.items|length')" -ge 1 ]; } && echo PASS || echo FAIL)" "http=$CODE items=$(jb '.items|length')"
req testuser "$A" GET /api/follow-up-queue -; chk $C "[$MODE] due queue still served" 200 "$CODE"
# Session expiry (#70): log out through the app, then reuse the old cookie and its CSRF token on mutations.
qa_login testoperator || exit 1
TOK=$(csrf testoperator); cp "$QA_TMP/jar-testoperator.txt" "$QA_TMP/jar-old.txt"
req testoperator - POST /logout -
for m in "PUT /api/customers/$X/follow-up-snooze" "POST /api/customers/$X/manual-follow-ups" "POST /api/customers/$X/recommendation"; do
  code=$(curl -s -o /dev/null -w '%{http_code}' -X "${m%% *}" -b "$QA_TMP/jar-old.txt" -H "X-Tenant-Id: $A" -H 'Content-Type: application/json' \
    -H @<(printf 'X-CSRF-TOKEN: %s\n' "$TOK") -H 'If-Match: "1"' -H "Idempotency-Key: qa340-exp-$RANDOM" --data '{}' "$BFF${m#* }")
  chk $C "[$MODE] ended session: ${m%% *} …/${m##*/} -> 401, not 403 (#70)" 401 "$code"
done
chk $C "[$MODE] no provider send" "$STUB0_340" "$(stub_sends)"
qa_finish
