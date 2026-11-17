#!/usr/bin/env bash
# ABOUTME: Suite #334 user-approved stub-only window: restart with the global switch ON (loopback Graph stub and
# ABOUTME: synthetic canary tokens only), tenant A switch ON, optionally rotate the credential and re-prove ownership.
# shellcheck disable=SC2046  # $(... echo PASS || echo FAIL) always yields one word
#   window-open334.sh <label> [rotate]
set -uo pipefail
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
. "$HERE/qa334.sh"
L=${1:?label}; ROT=${2:-}
assert_safe_stack false || exit 2
# Per-window token: the watchdog closes only the window it was started for, never a later one.
TOKEN="$L-$$-$(date +%s)"; printf '%s\n' "$TOKEN" > "$OUTD/window.open"
# Watchdog: whatever happens in the browser, the window closes itself after 10 minutes. window-close334.sh kills it.
setsid bash -c "sleep 600; [ \"\$(cat '$OUTD/window.open' 2>/dev/null)\" = '$TOKEN' ] && bash '$HERE/window-close334.sh' watchdog >>'$OUTD/watchdog.log' 2>&1" </dev/null >/dev/null 2>&1 &
echo $! > "$OUTD/window.watchdog"
QA333_OUTBOUND=true bash "$QA333_DIR/stack333.sh" restart >&2 || { bash "$HERE/window-close334.sh" failed-open; exit 3; }
assert_safe_stack true || { bash "$HERE/window-close334.sh" unsafe; exit 3; }
qa_login testuser || { bash "$HERE/window-close334.sh" failed-open; exit 3; }
ok() { [ "$CODE" = 200 ] && echo PASS || echo FAIL; }
integ; req testuser "$A" PUT /api/integrations/whatsapp/outbound '{"enabled":true}' -H "If-Match: $(etag)"
rec WINDOW "[$L] tenant A outbound ON" "$(ok)" "http=$CODE"
if [ "$ROT" = rotate ]; then
  integ; req testuser "$A" POST /api/integrations/whatsapp/credential-rotations - -H "If-Match: $(etag)"
  rec WINDOW "[$L] credential rotation (integration change after approval)" "$(ok)" "http=$CODE"
  integ; req testuser "$A" POST /api/integrations/whatsapp/ownership-verifications - -H "If-Match: $(etag)"
  rec WINDOW "[$L] ownership re-proven against the stub after rotation" "$(ok)" "http=$CODE"
fi
T=$(tenant_state)
rec WINDOW "[$L] OPEN: Graph base URL loopback stub, global ON, tenant A enabled/proven/outbound=$T" \
  "$([ "$T" = true/true/true ] && echo PASS || echo FAIL)" "stub sends=$(stub_sends)"
# A window that did not open exactly as intended is closed again, so no check runs on a half-configured tenant.
[ "$QA_FAILS" -eq 0 ] || { bash "$HERE/window-close334.sh" failed-open; exit 1; }
qa_finish
