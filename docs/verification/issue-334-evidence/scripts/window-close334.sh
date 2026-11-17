#!/usr/bin/env bash
# ABOUTME: Suite #334: close the stub-only window. Tenant A switch OFF, backend restarted with the global switch OFF,
# ABOUTME: effective values verified after the restart. Ignores interrupts; exits nonzero if the close cannot be proven.
# shellcheck disable=SC2046  # $(... echo PASS || echo FAIL) always yields one word
set -uo pipefail
trap '' INT TERM HUP
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
. "$HERE/qa334.sh"
L=${1:-manual}; bad=0
qa_login testuser || bad=1
integ; req testuser "$A" PUT /api/integrations/whatsapp/outbound '{"enabled":false}' -H "If-Match: $(etag)" || bad=1
QA333_OUTBOUND=false setsid --wait bash "$QA333_DIR/stack333.sh" restart >&2 || bad=1
if assert_safe_stack false; then G=off; else G=ON; bad=1; fi
qa_login testuser || bad=1
T=$(tenant_state)
[ "$G/$T" = off/true/true/false ] || bad=1
rm -f "$OUTD/window.open"
rec WINDOW "[$L] CLOSED: global $G, tenant A enabled/proven/outbound=$T (verified after restart)" "$([ $bad -eq 0 ] && echo PASS || echo FAIL)" "stub sends=$(stub_sends)"
exit $bad
