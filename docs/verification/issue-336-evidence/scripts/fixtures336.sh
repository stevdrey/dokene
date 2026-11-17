#!/usr/bin/env bash
# ABOUTME: Suite #336 setup with the global switch OFF: disposable QA336 customers in tenant A, one message per state
# ABOUTME: (pending, approved, cancelled, approved-to-send), and a customer with more than one history page.
# shellcheck disable=SC2046  # $(... echo PASS || echo FAIL) always yields one word
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/qa336.sh"
assert_safe_stack false || exit 2
stub_reset
qa_login testuser || exit 3
req testuser "$A" GET /api/templates -; v=$(jb '.items[]|select(.templateIntent=="REPEAT_PURCHASE").version')
req testuser "$A" POST /api/templates/REPEAT_PURCHASE/approval-refresh - -H "If-Match: \"$v\""
rec SETUP "refresh REPEAT_PURCHASE approval evidence against the stub" "$([ "$CODE" = 200 ] && [ "$(jb .sendable)" = true ] && echo PASS || echo FAIL)" "http=$CODE sendable=$(jb .sendable)"

mk() { # var name [pending|approve|cancel|none]
  local v=$1 n=$2 how=${3:-approve} r=0
  mkcust "$v" "$n" || { rec SETUP "customer $n" FAIL "create failed"; return; }
  local cv=${!v} kv; kv=${v}_C; kv=${!kv}
  case $how in
    none) rec SETUP "customer $n (no message)" PASS "cust=${cv:0:8}"; return ;;
    pending) mkmsg "M_$v" testuser "$cv" "$kv" || r=1 ;;
    approve) mkmsg "M_$v" testuser "$cv" "$kv" approve || r=1 ;;
    cancel) if mkmsg "M_$v" testuser "$cv" "$kv"; then local m="M_$v"; cancel "${!m}"; [ "$(jb .status)" = CANCELLED ] || r=1; else r=1; fi ;;
  esac
  local mv="M_$v"
  rec SETUP "customer $n + message ($how)" "$([ $r = 0 ] && echo PASS || echo FAIL)" "msg=${!mv:0:8} status=$(mstate "${!mv}")"
}

mk H0  "QA336 Abel SinMensajes" none
mk H1P "QA336 Berta Pendiente" pending
mk H1A "QA336 Carla Aprobada" approve
mk H1C "QA336 Dario Cancelado" cancel
mk H1S "QA336 Elena Aceptada" approve        # sent (stub accept), no callback: stays SENT
mk H1D "QA336 Olga Entregada" approve        # sent, then a delivered callback only
mk H2  "QA336 Fabio Entrega" approve         # sent, then delivered and read callbacks with the browser open
mk H3  "QA336 Gina Repeticion" approve       # sent, then replayed, reordered and duplicated callbacks
mk H4  "QA336 Hector Cruce" approve          # sent, then forged, unmatched and cross-tenant callbacks
mk H5F "QA336 Iris FalloEnvio" approve       # send refused by the stub (401): FAILED synchronously
mk H5W "QA336 Jose FalloWebhook" approve     # sent, then a failed callback (131026)
mk H5U "QA336 Karen SinConfirmar" approve    # stub "paused": unknown outcome with a provider id; T12 later
mk H5R "QA336 Luis Reconciliado" approve     # stub "paused": unknown outcome, then reconciled by a delivered callback

# HIST-06: one customer with 25 cancelled messages and one pending (26 rows: a 20-row page plus 6), and a neighbour.
mkcust H6 "QA336 Mia Paginada" || rec SETUP "customer QA336 Mia Paginada" FAIL "create failed"
ok=0
for i in $(seq 1 25); do
  mkmsg "M_H6_$i" testuser "$H6" "$H6_C" && v="M_H6_$i" && cancel "${!v}" && [ "$(jb .status)" = CANCELLED ] && ok=$((ok + 1))
done
mkmsg M_H6_26 testuser "$H6" "$H6_C"
rec SETUP "QA336 Mia Paginada: 25 cancelled + 1 pending" "$([ "$ok" = 25 ] && [ "$(cust_msgs "$H6")" = 26 ] && echo PASS || echo FAIL)" "cancelled=$ok total=$(cust_msgs "$H6")"
mk H6N "QA336 Nico Vecino" pending

rec SETUP "side effects after setup" NOTE "attempts_total=$(attempts_all) stub_sends=$(stub_sends) inbox_rows=$(inbox_rows)"
qa_finish
