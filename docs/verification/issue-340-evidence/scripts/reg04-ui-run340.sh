#!/usr/bin/env bash
# ABOUTME: Suite #340 REG-04 browser leg driver: derives the expected queue, workspace B, volume and purchase-id set from
# ABOUTME: the API/database, runs reg04-ui340.mjs in Chromium and Firefox, saves each log and fails if either run fails.
# Needs the reg04-fixtures340.sh fixture (LONG_C in $OUTD/ids), PW_HOME (playwright-core 1.64.1) and Node 24 on PATH.
set -uo pipefail
. "$(dirname "$0")/qa340.sh"
qa_login testuser || exit 1
[ -n "${LONG_C:-}" ] || { echo "LONG_C missing: run reg04-fixtures340.sh first" >&2; exit 1; }
# walk_ids <tenant>: every customer id of the due queue, all pages
walk_ids() {
  local cur="" pages=0
  while :; do
    req testuser "$1" GET "/api/follow-up-queue?limit=100${cur:+&cursor=$cur}" -
    [ "$CODE" = 200 ] || { echo "queue read failed: $CODE" >&2; return 1; }
    jb '.items[].customerId'; pages=$((pages + 1))
    cur=$(jb '.nextCursor // empty'); [ -n "$cur" ] && [ $pages -lt 60 ] || break
  done
}
A_IDS=$(walk_ids "$A") || exit 1; B_IDS=$(walk_ids "$B") || exit 1
export EXPECT_QUEUE_TOTAL; EXPECT_QUEUE_TOTAL=$(printf '%s\n' "$A_IDS" | grep -c .)
export EXPECT_SUR_IDS; EXPECT_SUR_IDS=$(printf '%s\n' "$B_IDS" | grep . | paste -sd,)
export EXPECT_VOLUME; EXPECT_VOLUME=$(psqlq "select count(*) from dokene.customers where tenant_id='$A' and display_name like 'QA340 Volumen %' and status='ACTIVE'")
export EXPECT_PURCHASES; EXPECT_PURCHASES=$(psqlq "select count(*) from dokene.purchases where customer_id='$LONG_C'")
export EXPECT_PURCHASE_IDS_MD5; EXPECT_PURCHASE_IDS_MD5=$(printf '%s' "$(psqlq "select id from dokene.purchases where customer_id='$LONG_C'" | LC_ALL=C sort)" | md5sum | cut -d' ' -f1)
export QA_PW; QA_PW=$(pw_for testuser)
echo "expected: queue=$EXPECT_QUEUE_TOTAL volume=$EXPECT_VOLUME b_ids=$(printf '%s' "$EXPECT_SUR_IDS" | tr ',' '\n' | grep -c .) purchases=$EXPECT_PURCHASES"
rc=0
for br in chromium firefox; do
  node "$(dirname "$0")/reg04-ui340.mjs" "$br" > "$OUTD/reg04-ui-$br.txt" 2>&1 || rc=1
  tail -1 "$OUTD/reg04-ui-$br.txt"
done
exit $rc
