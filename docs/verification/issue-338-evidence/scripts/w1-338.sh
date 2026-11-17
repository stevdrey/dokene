#!/usr/bin/env bash
# ABOUTME: Suite #338 window W1 API part (global switch ON, loopback stub only): sends that produce the delivered, read,
# ABOUTME: failed and uncertain states, signed synthetic status callbacks, and a refused resend of the uncertain message.
#   w1-338.sh sends | callbacks | reconcile
set -uo pipefail
. "$(dirname "$0")/qa338.sh"
C=UX-04
qa_login testuser || exit 1
one() { # <message var> <stub send mode> <expected status> <expected unknown>
  local m=${!1} s0; s0=$(stub_sends); stub_mode send "$2"
  send testuser "$m"
  stub_mode send accept
  local r; r=$(mrow "$m")
  rec $C "W1 send $1 (stub $2) -> $3" "$( { [[ "$r" == "$3/unknown=$4"* ]] && [ "$(attempts "$m")" = 1 ] && [ $(( $(stub_sends) - s0 )) = 1 ]; } && echo PASS || echo FAIL)" "http=$CODE row=$r stub=+$(( $(stub_sends) - s0 ))"
}
cb() { # <message var> <status...>: signed callback(s) for the message's provider id
  local m=${!1} p f=$OUTD/w1-cb-$1.json items=(); p=$(pmid "$m")
  for st in "${@:2}"; do items+=("$p:$st"); done
  wh_body "$f" "$PNID_A" "${items[@]}"; wh_post "$f"
  rec $C "W1 callback $1 ${*:2}" "$( { [ "$WCODE" = 200 ]; } && echo PASS || echo FAIL)" "http=$WCODE row=$(mrow "$m")"
}
case ${1:-} in
  sends)
    one SDM accept SENT false; one SRM accept SENT false
    one SFM error401canary FAILED false; one SUM paused SENDING true
    # The uncertain message must not be sendable again (no second provider request).
    s0=$(stub_sends); send testuser "$SUM"
    rec UX-05 "UX-05 resend of the uncertain message is refused, no provider request" "$( { [[ "$CODE" == 409 ]] && [ "$(attempts "$SUM")" = 1 ] && [ "$(stub_sends)" = "$s0" ]; } && echo PASS || echo FAIL)" "http=$CODE code=$(code) attempts=$(attempts "$SUM")" ;;
  callbacks) cb SDM delivered; cb SRM delivered read; sleep 2
    rec $C "W1 states: SD delivered, SR read" "$( { [[ "$(mrow "$SDM")" == DELIVERED* ]] && [[ "$(mrow "$SRM")" == READ* ]]; } && echo PASS || echo FAIL)" "SD=$(mrow "$SDM") SR=$(mrow "$SRM")" ;;
  reconcile) cb SUM delivered; sleep 2
    rec $C "cleanup: the uncertain message reconciled by a delivered callback (no resend)" "$( { [[ "$(mrow "$SUM")" == DELIVERED/unknown=false* ]] && [ "$(attempts "$SUM")" = 1 ]; } && echo PASS || echo FAIL)" "row=$(mrow "$SUM")" ;;
  *) echo "usage: $0 sends|callbacks|reconcile" >&2; exit 2 ;;
esac
qa_finish
