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
touch "$OUTD/window.open"
# Watchdog: whatever happens in the browser, the window closes itself after 10 minutes.
setsid bash -c "sleep 600; [ -f '$OUTD/window.open' ] && bash '$HERE/window-close334.sh' watchdog >>'$OUTD/watchdog.log' 2>&1" </dev/null >/dev/null 2>&1 &
QA333_OUTBOUND=true bash "$QA333_DIR/stack333.sh" restart >&2 || { bash "$HERE/window-close334.sh" failed-open; exit 3; }
assert_safe_stack true || { bash "$HERE/window-close334.sh" unsafe; exit 3; }
qa_login testuser
integ; req testuser "$A" PUT /api/integrations/whatsapp/outbound '{"enabled":true}' -H "If-Match: $(etag)"
if [ "$ROT" = rotate ]; then
  integ; req testuser "$A" POST /api/integrations/whatsapp/credential-rotations - -H "If-Match: $(etag)"
  rec WINDOW "[$L] credential rotation (integration change after approval)" NOTE "http=$CODE state=$(tenant_state)"
  integ; req testuser "$A" POST /api/integrations/whatsapp/ownership-verifications - -H "If-Match: $(etag)"
  rec WINDOW "[$L] ownership re-proven against the stub after rotation" NOTE "http=$CODE"
fi
rec WINDOW "[$L] OPEN: Graph base URL loopback stub, global ON, tenant A enabled/proven/outbound=$(tenant_state)" NOTE "stub sends=$(stub_sends)"
