#!/usr/bin/env bash
# ABOUTME: Suite #334: close the stub-only window. Backend restarted with the global switch OFF, tenant A switch OFF,
# ABOUTME: both verified; window state is cleared only after that. Fails closed (backend stopped) when it cannot verify.
# shellcheck disable=SC2046  # $(... echo PASS || echo FAIL) always yields one word
#   window-close334.sh [label]   (label "watchdog" is used by the window's own watchdog)
set -uo pipefail
trap '' INT TERM HUP
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
. "$HERE/qa334.sh"
L=${1:-manual}
# One close at a time: a manual close and the watchdog never restart the backend concurrently.
exec 9>"$OUTD/window.lock"; flock 9
# Nothing to do when the window was already closed and verified (the token is cleared only after verification).
if [ "$L" = watchdog ] && [ ! -f "$OUTD/window.open" ]; then echo "window already closed" >&2; exit 0; fi

# close_once: restart with the global switch OFF first (sessions end), then tenant A OFF, then verify both.
close_once() {
  QA333_OUTBOUND=false setsid --wait bash "$STACK333" restart >&2 || return 1
  assert_safe_stack false || return 1
  qa_login testuser || return 1
  integ; req testuser "$A" PUT /api/integrations/whatsapp/outbound '{"enabled":false}' -H "If-Match: $(etag)"
  [ "$CODE" = 200 ] || return 1
  T=$(tenant_state); [ "$T" = true/true/false ]
}
T=unknown
if close_once || close_once; then
  # Verified OFF: only now disarm this window's watchdog (unless this is the watchdog) and clear the window state.
  if [ "$L" != watchdog ] && [ -f "$OUTD/window.watchdog" ]; then
    W=$(cat "$OUTD/window.watchdog"); pkill -P "$W" sleep 2>/dev/null; kill "$W" 2>/dev/null
  fi
  rm -f "$OUTD/window.open" "$OUTD/window.watchdog"
  rec WINDOW "[$L] CLOSED: global off, tenant A enabled/proven/outbound=$T (verified after restart)" PASS "stub sends=$(stub_sends)"
  exit 0
fi
# Two attempts could not prove global and tenant OFF: fail closed. A stopped backend cannot send; the window state and
# the watchdog stay armed, so a later close (manual or watchdog) retries the restart with the global switch OFF.
stop_backend
if backend_running; then S="backend STILL RUNNING"; else S="backend stopped"; fi
rec WINDOW "[$L] CLOSE NOT VERIFIED after 2 attempts: fail closed, $S; window state kept for retry" FAIL "tenant=$T"
echo "window close FAILED ($S): rerun window-close334.sh after checking the stack" >&2
exit 4
