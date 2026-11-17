#!/usr/bin/env bash
# ABOUTME: Suite #336 HIST-06 API part: keyset pages of a 26-message history, a change between pages, tenant scoping of
# ABOUTME: list and detail, and no raw webhook payload or secret value in any history response.
# shellcheck disable=SC2046  # $(... echo PASS || echo FAIL) always yields one word
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/qa336.sh"
assert_safe_stack false || exit 2
qa_login testuser || exit 3
C=HIST-06
page() { req testuser "$1" GET "/api/messages?customerId=$H6&limit=20${2:+&cursor=$2}" -; }
page "$A"; P1=$(jb '[.items[].messageId]|join(" ")'); CUR=$(jb .nextCursor); A1=$BODY
rec $C "page 1: 20 rows, newest first, a next cursor" "$([ "$(jb '.items|length')" = 20 ] && [ "$CUR" != null ] && [ "$(jb '.items[0].messageId')" = "$M_H6_26" ] && echo PASS || echo FAIL)" "rows=$(jb '.items|length') first_is_newest=$([ "$(jb '.items[0].messageId')" = "$M_H6_26" ] && echo yes || echo no)"
# Between pages: the open message is cancelled (status change) and a new message is submitted (newer than the cursor).
cancel "$M_H6_26"; mkmsg M_H6_27 testuser "$H6" "$H6_C"
page "$A" "$CUR"; P2=$(jb '[.items[].messageId]|join(" ")'); A2=$BODY
ALL=$(printf '%s %s' "$P1" "$P2" | tr ' ' '\n' | LC_ALL=C sort)
DB=$(psqlq "select id from dokene.outbound_messages where customer_id='$H6' and id<>'$M_H6_27'" | LC_ALL=C sort)
rec $C "page 2 after a change between pages: 6 rows, no duplicate, union equals the 26 earlier messages" \
  "$([ "$(jb '.items|length')" = 6 ] && [ "$(jb .nextCursor)" = null ] && [ -z "$(printf '%s\n' "$ALL" | uniq -d)" ] && [ "$ALL" = "$DB" ] && echo PASS || echo FAIL)" \
  "rows=$(jb '.items|length') dups=$(printf '%s\n' "$ALL" | uniq -d | wc -l) union=$(printf '%s\n' "$ALL" | wc -l) db=$(printf '%s\n' "$DB" | wc -l)"
page "$A"
rec $C "a fresh first page shows the new message on top and the cancelled one updated" "$([ "$(jb '.items[0].messageId')" = "$M_H6_27" ] && [ "$(jb ".items[]|select(.messageId==\"$M_H6_26\").status")" = CANCELLED ] && echo PASS || echo FAIL)" "top=${M_H6_27:0:8}? $(jb '.items[0].messageId[0:8]') m26=$(jb ".items[]|select(.messageId==\"$M_H6_26\").status")"
# Tenant scope: tenant B (testuser is ADMIN there) sees nothing of tenant A.
page "$B"; HB=$CODE; RB=$(jb '.items|length // 0')
req testuser "$B" GET "/api/messages/$M_H2" -; DB2=$CODE; DBODY=$BODY
req testuser "$B" GET "/api/messages?limit=100" -
LB=$(jb '[.items[].messageId]|join(" ")')
leak=0; for m in $P1 $P2 $M_H2 $M_H3 $M_H4; do case " $LB " in *" $m "*) leak=1 ;; esac; done
rec $C "tenant B: A's customer filter returns no row, A's message detail is 404 without A data, B's list holds no A message" \
  "$({ [ "$HB" = 200 ] && [ "$RB" = 0 ] || [ "$HB" = 404 ]; } && [ "$DB2" = 404 ] && ! printf '%s' "$DBODY" | grep -q 'QA336' && [ $leak = 0 ] && echo PASS || echo FAIL)" \
  "filter=$HB/$RB detail=$DB2 b_rows=$(jb '.items|length') leak=$leak"
# No raw webhook payload or secret in history responses (list pages, detail and events of a callback-updated message).
req testuser "$A" GET "/api/messages/$M_H3" -; D3=$BODY; req testuser "$A" GET "/api/messages/$M_H3/events" -; E3=$BODY
hits=$(printf '%s\n%s\n%s\n%s\n' "$A1" "$A2" "$D3" "$E3" | grep -oE 'whatsapp_business_account|phone_number_id|recipient_id|statuses|X-Hub-Signature|CANARY|sha256=|"errors"' | sort -u | tr '\n' ' ')
rec $C "history responses carry no raw callback members, signature or canary secret" "$([ -z "$hits" ] && echo PASS || echo FAIL)" "hits=${hits:-none}"
qa_finish
