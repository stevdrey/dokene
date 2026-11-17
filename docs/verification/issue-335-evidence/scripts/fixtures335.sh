#!/usr/bin/env bash
# ABOUTME: Suite #335 setup with the global switch OFF: refresh template evidence against the stub, create the
# ABOUTME: disposable QA335 customers in tenant A and one approved (or pending) synthetic message per case.
#   fixtures335.sh w1   (messages for W1)   |   fixtures335.sh w2   (messages approved after W1's rotation)
# shellcheck disable=SC2046  # $(... echo PASS || echo FAIL) always yields one word
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/qa335.sh"
assert_safe_stack false || exit 2
stub_reset
for u in testuser testoperator; do qa_login $u || exit 3; done
refresh() {
  req testuser "$A" GET /api/templates -; local v; v=$(jb ".items[]|select(.templateIntent==\"$1\").version")
  req testuser "$A" POST "/api/templates/$1/approval-refresh" - -H "If-Match: \"$v\""
  rec SETUP "refresh $1 approval evidence against the stub" "$([ "$CODE" = 200 ] && [ "$(jb .sendable)" = true ] && echo PASS || echo FAIL)" "http=$CODE status=$(jb .approvalStatus) sendable=$(jb .sendable)"
}
refresh REPEAT_PURCHASE; refresh GENERAL_FOLLOW_UP
mk() { # var name user [approve] [GENERAL]
  local v=$1 n=$2 u=$3 ap=${4:-} g=${5:-}
  mkcust "$v" "$n" || { rec SETUP "customer $n" FAIL "create failed"; return; }
  local cv=${!v} kv; kv=${v}_C; kv=${!kv}
  if [ "$g" = GENERAL ]; then MSG_ACTION=GENERAL_CHECK_IN MSG_INTENT=GENERAL_FOLLOW_UP mkmsg "M_$v" "$u" "$cv" "$kv" "$ap"
  else mkmsg "M_$v" "$u" "$cv" "$kv" "$ap"; fi
  local r=$?; local mv="M_$v"
  rec SETUP "customer $n + message ($u, ${ap:-pending}, ${g:-REPEAT_PURCHASE})" "$([ $r = 0 ] && echo PASS || echo FAIL)" "msg=${!mv:0:8} status=$(mstate "${!mv}")"
}
case ${1:-} in
  w1)
    mk S1 "QA335 Sofia Interruptor" testuser approve
    mk S2CONS "QA335 Raul Consentimiento" testuser approve
    mk S2DNC "QA335 Marta NoContactar" testuser approve
    mk S2ARCH "QA335 Ines Archivada" testuser approve
    mk S2CONT "QA335 Hugo Contacto" testuser approve
    mk S2INEL "QA335 Lidia Pospuesta" testuser approve   # used by the superseded first ineligibility attempt
    mk S3ROLE "QA335 Omar Rol" testoperator approve
    mk S3INT "QA335 Pia Integracion" testuser approve
    mk S3PROOF "QA335 Tomas Prueba" testuser approve
    mk S3MAPD "QA335 Vera MapeoOff" testuser approve GENERAL
    mk S3MAPM "QA335 Wilma MapeoCambio" testuser approve GENERAL
    mk S4 "QA335 Nora Envio" testuser          # submitted only: approved and sent in the browser
    mk S5DBL "QA335 Ciro DobleClic" testuser   # submitted only: approved and double-clicked in the browser
    mk S5CONC "QA335 Gala Concurrente" testuser approve
    mk S5KEY "QA335 Ivan Clave" testuser approve
    mk S6HANG "QA335 Kira SinRespuesta" testuser   # submitted only: approved and sent in the browser (stub hangs)
    ;;
  w2)
    mk S6CRASH "QA335 Leo Caida" testuser approve
    mk S7 "QA335 Elsa Intervalo" testuser approve
    ;;
  *) echo "usage: $0 w1|w2" >&2; exit 2 ;;
esac
rec SETUP "side effects after setup" NOTE "attempts_total=$(attempts_all) stub_sends=$(stub_sends)"
qa_finish
