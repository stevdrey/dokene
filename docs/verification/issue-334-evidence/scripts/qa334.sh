# shellcheck shell=bash
# ABOUTME: Helpers for manual QA suite #334 (Phase 3 draft review and revision-bound approval). Reuses the #333 stack
# ABOUTME: helpers (BFF login, tenant-scoped requests with CSRF, sanitized recorder) with suite-local result files.
# shellcheck disable=SC2034  # sourced library: variables are used by the scripts that source it
#
# Source it from the other scripts. QA_HOME (default ~/qa333) holds the #333 stack, stub and ids; OUTD (default
# ~/qa334/out) holds this suite's results. Never prints tokens, cookies, CSRF values or passwords.
QA334_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
QA333_DIR=$(cd "$QA334_DIR/../../issue-333-evidence/scripts" && pwd)
OUTD=${OUTD:-$HOME/qa334/out}
RESULTS=${RESULTS:-$OUTD/results.tsv}
. "$QA333_DIR/qa333.sh"
. "$QA_HOME/out/ids"   # A and B tenant ids written by fixtures333.sh
STUB_LOG=$QA_HOME/stub/requests.log
stub_sends() { [ -f "$STUB_LOG" ] || { echo "stub log missing" >&2; return 1; }; grep -c "POST .*/messages" "$STUB_LOG" || true; }
integ() { req testuser "$A" GET /api/integrations/whatsapp -; }
tenant_state() { integ; printf '%s' "$BODY" | jq -r '"\(.enabled)/\(.webhookVerified)/\(.outboundEnabled)"'; }
