#!/usr/bin/env bash
# ABOUTME: Suite #336 sends inside the stub-only window W1: accepted sends for the delivery cases, a send the stub refuses
# ABOUTME: (401, FAILED synchronously) and two sends the stub answers "paused" (unknown outcome with a provider id).
# shellcheck disable=SC2046  # $(... echo PASS || echo FAIL) always yields one word
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/qa336.sh"
assert_safe_stack true || exit 2
qa_login testuser || exit 3
C=SETUP-W1
one() { # var mode expected-status expected-unknown
  local m=$1 mode=$2 want=$3 unk=$4 s0; s0=$(stub_sends)
  stub_mode send "$mode"
  send testuser "${!m}"
  local hc=$CODE; req testuser "$A" GET "/api/messages/${!m}" -
  rec $C "$m send with stub=$mode -> $want unknown=$unk, one attempt, one provider request" \
    "$([ "$(jb .status)" = "$want" ] && [ "$(jb .outcomeUnknown)" = "$unk" ] && [ "$(attempts "${!m}")" = 1 ] && [ $(( $(stub_sends) - s0 )) = 1 ] && echo PASS || echo FAIL)" \
    "http=$hc status=$(jb .status) unknown=$(jb .outcomeUnknown) pid=$([ "$(jb .providerMessageId)" = null ] && echo none || echo present) failure=$(jb .failureCategory) attempts=$(jb '[.attempts[].outcome]|join(",")')"
}
for m in M_H1S M_H1D M_H2 M_H3 M_H4 M_H5W; do one $m accept SENT false; done
one M_H5F error401canary FAILED false
one M_H5U paused SENDING true
one M_H5R paused SENDING true
stub_reset
setid W1_STUB_END "$(stub_sends)"   # baseline for the final "no provider request since W1" check
rec $C "side effects after the W1 sends" NOTE "attempts_total=$(attempts_all) stub_sends=$(stub_sends) inbox_rows=$(inbox_rows '')"
qa_finish
