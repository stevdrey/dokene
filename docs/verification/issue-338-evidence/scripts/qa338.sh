# shellcheck shell=bash
# ABOUTME: Helpers for manual QA suite #338 (Phase 3 UI/UX: responsive layouts, accessibility, operator recovery).
# ABOUTME: Reuses the #337 helpers (stack, fixtures, counters); adds QA338 tag/phone range. Synthetic data only.
# shellcheck disable=SC2034  # sourced library: variables are used by the scripts that source it
#
# Source it from the other scripts. QA_HOME (default ~/qa333) holds the #333 stack and stub; OUTD (default ~/qa338/out)
# holds this suite's results and ids. Never prints tokens, cookies, CSRF values, passwords or secrets.
QA338_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
QA338_OUTD=${OUTD:-$HOME/qa338/out}
QA338_RESULTS=${RESULTS:-$QA338_OUTD/results.tsv}
export OUTD=$QA338_OUTD RESULTS=$QA338_RESULTS
. "$QA338_DIR/../../issue-337-evidence/scripts/qa337.sh"
# qa337.sh sets its own tag, phone range and OUTD: restore this suite's values.
export OUTD=$QA338_OUTD RESULTS=$QA338_RESULTS QA_TAG=QA338 QA_PHONE_PREFIX=+5698338
mkdir -p "$OUTD"
[ -f "$OUTD/ids" ] && . "$OUTD/ids"
