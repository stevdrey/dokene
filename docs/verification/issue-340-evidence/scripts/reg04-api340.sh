#!/usr/bin/env bash
# ABOUTME: Suite #340 REG-04, API half: cursor pagination of the due queue (status filters, page sizes), the customer
# ABOUTME: list and a long purchase history over the volume fixture, plus workspace isolation of every page. Read-only.
set -uo pipefail
. "$(dirname "$0")/qa340.sh"
C=REG-04
qa_login testuser || exit 1; qa_login testoperator2 || exit 1
VOL=$(psqlq "select count(*) from dokene.customers where tenant_id='$A' and display_name like 'QA340 Volumen %' and status='ACTIVE'")
rec $C "fixture: volume customers in workspace A" NOTE "volume=$VOL long_history=$(psqlq "select count(*) from dokene.purchases where customer_id='$LONG_C'")"
# walk <path-with-?> <items-jq> <page-size>: all pages; prints "<pages> <items> <dups> <first-page-size>"
walk() {
  local path=$1 items=$2 size=$3 t=${4:-$A} u=${5:-testuser} cur="" pages=0 first="" f=$QA_TMP/walk.$$; : > "$f"
  rm -f "$QA_TMP/walk.last"   # a failed walk must not leave the previous walk's ids for the checks that follow
  while :; do
    req "$u" "$t" GET "${path}limit=$size${cur:+&cursor=$cur}" -
    [ "$CODE" = 200 ] || { echo "ERR $CODE"; rm -f "$f"; return; }
    pages=$((pages + 1)); jb "$items" >> "$f"; [ -n "$first" ] || first=$(jb "[$items]|length")
    cur=$(jb '.nextCursor // empty'); [ -n "$cur" ] && [ $pages -lt 60 ] || break
  done
  echo "$pages $(wc -l < "$f") $(sort "$f" | uniq -d | wc -l) $first"; cp "$f" "$QA_TMP/walk.last"; rm -f "$f"
}
DB_DUE=$(psqlq "select count(*) from dokene.customers where tenant_id='$A' and display_name like 'QA340 Volumen %' and status='ACTIVE'")
for size in 100 37; do
  r=$(walk "/api/follow-up-queue?" '.items[].customerId' $size); read -r pg n dup first <<< "$r"
  vol=$(grep -cFf <(psqlq "select id from dokene.customers where tenant_id='$A' and display_name like 'QA340 Volumen %' and status='ACTIVE'") "$QA_TMP/walk.last")
  rec $C "queue page size $size: every volume customer exactly once over $pg pages, no duplicate, first page full" \
    "$( { [ "$vol" = "$DB_DUE" ] && [ "$dup" = 0 ] && [ "$first" = "$size" ] && [ "$pg" -ge 3 ]; } && echo PASS || echo FAIL)" "pages=$pg items=$n volume_seen=$vol/$DB_DUE dups=$dup first=$first"
done
# order: by due date ascending (most overdue first), also across page boundaries
r=$(walk "/api/follow-up-queue?" '.items[].dueDate' 37); read -r pg n dup first <<< "$r"
chk $C "queue order: due date never decreases across $pg pages of 37" sorted "$(sort -c "$QA_TMP/walk.last" 2>/dev/null && echo sorted || echo unsorted)"
ORD=$(curl -s -b "$QA_TMP/jar-testuser.txt" -H "X-Tenant-Id: $A" "$BFF/api/follow-up-queue?limit=100" | jq -r '.items[0]|keys|join(",")')
rec $C "queue item fields" NOTE "$ORD"
for st in OVERDUE DUE; do
  r=$(walk "/api/follow-up-queue?status=$st&" '.items[]|"\(.customerId) \(.status)"' 100); read -r pg n dup first <<< "$r"
  rec $C "status=$st filter: only $st items, no duplicate across $pg pages" \
    "$( { [ "$dup" = 0 ] && [ "$(awk '{print $2}' "$QA_TMP/walk.last" | sort -u | tr -d '\n')" = "$st" ]; } && echo PASS || echo FAIL)" "pages=$pg items=$n"
done
req testuser "$A" GET "/api/follow-up-queue?limit=101" -; chk $C "queue limit above 100 -> 400" 400 "$CODE"
req testuser "$A" GET "/api/follow-up-queue?limit=50&cursor=not-a-cursor" -; chk $C "forged cursor -> 400" 400 "$CODE"
req testuser "$A" GET "/api/follow-up-queue?limit=50" -; CUR=$(jb .nextCursor)
req testuser "$B" GET "/api/follow-up-queue?limit=50&cursor=$CUR" -
rec $C "workspace A cursor replayed in workspace B never returns A customers" \
  "$( { [ "$CODE" = 400 ] || { [ "$CODE" = 200 ] && [ "$(printf '%s' "$BODY" | jq -r '.items[]?.customerId' | grep -cFf <(psqlq "select id from dokene.customers where tenant_id='$A'") || true)" = 0 ]; }; } && echo PASS || echo FAIL)" "http=$CODE items=$(jb '.items|length // 0')"
r=$(walk "/api/follow-up-queue?" '.items[].customerId' 100 "$B" testoperator2); read -r pg n dup first <<< "$r"
chk $C "workspace B queue (all pages) holds no workspace A customer" 0 "$(grep -cFf <(psqlq "select id from dokene.customers where tenant_id='$A'") "$QA_TMP/walk.last" || true)" "b_items=$n"
# customers list
r=$(walk "/api/customers?name=QA340%20Volumen&" '.customers[].id' 50); read -r pg n dup first <<< "$r"
chk $C "customer search 'QA340 Volumen' pages of 50: all $DB_DUE once" "$DB_DUE 0" "$n $dup" "pages=$pg"
r=$(walk "/api/customers?status=ARCHIVED&" '.customers[].id' 100); read -r pg n dup first <<< "$r"
chk $C "archived list over all pages matches the database, no duplicate" "$(psqlq "select count(*) from dokene.customers where tenant_id='$A' and status='ARCHIVED'") 0" "$n $dup" "pages=$pg"
# long purchase history
r=$(walk "/api/customers/$LONG_C/purchases?" '.purchases[].id' 50); read -r pg n dup first <<< "$r"
chk $C "purchase history pages of 50: every purchase once" "$(psqlq "select count(*) from dokene.purchases where customer_id='$LONG_C'") 0" "$n $dup" "pages=$pg"
req testuser "$A" GET "/api/customers/$LONG_C/purchases?limit=100" -
chk $C "purchase history newest first" "$(jb '[.purchases[].purchasedAt]|. == (sort|reverse)')" true
qa_finish
