#!/usr/bin/env bash
# ABOUTME: Suite #336 browser access: the history panel lives in the follow-up workbench, which lists only due customers,
# ABOUTME: and a send makes a customer not yet due; this puts the named customers back on today's queue (no message change).
#   due336.sh H1S H1D ...
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/qa336.sh"
qa_login testuser || exit 3
for v in "$@"; do
  m="M_$v"; r0=$(mrow "${!m}"); due_today "${!v}"; hc=$CODE
  rec SETUP "$v back on today's queue (policy only); message row unchanged" "$([ "$hc" = 200 ] && [ "$(mrow "${!m}")" = "$r0" ] && echo PASS || echo FAIL)" "http=$hc row=$r0"
done
qa_finish
