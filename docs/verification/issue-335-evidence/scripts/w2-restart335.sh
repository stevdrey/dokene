#!/usr/bin/env bash
# ABOUTME: Suite #335 W2 crash restart: under the shared window lock, re-enable global outbound only while the W2 window
# ABOUTME: this run opened is still active (same token, live watchdog). Otherwise abort without restarting.
#   w2-restart335.sh <expected-window-token>
# Exit: 0 restarted with global ON (W2 policy overrides), 6 window closed/replaced or watchdog dead (nothing restarted),
# 5 restart failed, 2 restarted but the stack is not the expected stub environment.
set -uo pipefail
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
. "$HERE/qa335.sh"
TOKEN=${1:?expected W2 window token}
# The same lock window-open334.sh and window-close334.sh take: a close (manual or watchdog) and this restart never
# interleave, and the guard below is evaluated with no close able to run until the restart is done.
exec 9>"$OUTD/window.lock"; flock 9
CUR=$(cat "$OUTD/window.open" 2>/dev/null || true)
if [ "$CUR" != "$TOKEN" ]; then
  echo "W2 window closed or replaced (token now: ${CUR:-none}): not re-enabling outbound (backend left as it is)" >&2; exit 6
fi
W=$(cat "$OUTD/window.watchdog" 2>/dev/null || true)
if [ -z "$W" ] || ! kill -0 "$W" 2>/dev/null; then
  echo "W2 watchdog is not live: not re-enabling outbound (backend left as it is)" >&2; exit 6
fi
# Children run with fd 9 closed, so the lock is released when this script ends, not held by the backend start.
QA333_OUTBOUND=true QA335_MIN_INTERVAL=3m QA335_CLAIM_TIMEOUT=2m QA335_RECOVERY_INTERVAL=15s \
  bash "$STACK333" restart >&2 9>&- || exit 5
assert_safe_stack true || exit 2
