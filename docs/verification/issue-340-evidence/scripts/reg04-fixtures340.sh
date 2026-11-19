#!/usr/bin/env bash
# ABOUTME: Suite #340 REG-04 volume fixture: N due/overdue customers in workspace A (default 230, "QA340 Volumen NNN"),
# ABOUTME: each with WhatsApp consent, one purchase and an explicit next date spread over the last 25 days, plus one
# ABOUTME: customer with 120 purchases for long purchase-history pages. Synthetic data only; re-runnable (skips existing).
set -uo pipefail
. "$(dirname "$0")/qa340.sh"
N=${N:-230}
qa_login testuser || exit 1
have=$(psqlq "select count(*) from dokene.customers where tenant_id='$A' and display_name like 'QA340 Volumen %' and status='ACTIVE'")
for i in $(seq $((have + 1)) "$N"); do
  name=$(printf 'QA340 Volumen %03d' "$i"); phone=$(next_phone)
  req testuser "$A" POST /api/customers "$(jq -cn --arg n "$name" --arg p "$phone" '{displayName:$n,phones:[{number:$p,region:"CL",primary:true}]}')"
  [ "$CODE" = 201 ] || { echo "create $name: $CODE" >&2; exit 1; }
  cid=$(jb .id); pid=$(jb '.phones[0].id')
  req testuser "$A" PUT "/api/customers/$cid/contacts/$pid/consents/WHATSAPP" '{"status":"GRANTED","source":"CUSTOMER_VERBAL"}' -H 'If-Match: "0"'
  req testuser "$A" POST "/api/customers/$cid/purchases" "$(jq -cn --arg t "$(date -u -d "-$((40 + i % 30)) days" +%FT12:00:00Z)" '{purchasedAt:$t,description:"Compra volumen QA340"}')" -H "Idempotency-Key: qa340-vol-$cid"
  req testuser "$A" GET "/api/customers/$cid/follow-up-policy" -
  req testuser "$A" PUT "/api/customers/$cid/follow-up-policy" "$(jq -cn --arg d "$(date -u -d "-$((i % 25)) days" +%F)" '{cadenceDays:null,explicitNextDate:$d}')" -H "If-Match: $(etag)"
  [ "$CODE" = 200 ] || { echo "policy $name: $CODE" >&2; exit 1; }
  [ $((i % 50)) = 0 ] && echo "  $i customers"
done
if [ -z "${LONG_C:-}" ]; then
  mkcust LONG_C "QA340 Historial Largo" || exit 1
  for k in $(seq 1 120); do
    req testuser "$A" POST "/api/customers/$LONG_C/purchases" "$(jq -cn --arg t "$(date -u -d "-$((k + 1)) days" +%FT10:00:00Z)" --arg d "$(printf 'Compra larga %03d' "$k")" '{purchasedAt:$t,description:$d}')" -H "Idempotency-Key: qa340-long-$k"
  done
fi
rec REG-04 "volume fixture ready" NOTE "volume_customers=$(psqlq "select count(*) from dokene.customers where tenant_id='$A' and display_name like 'QA340 Volumen %' and status='ACTIVE'") long_history_purchases=$(psqlq "select count(*) from dokene.purchases where customer_id='$LONG_C'")"
qa_finish
