#!/usr/bin/env bash
# ABOUTME: Suite #336 setup completion after the first fixtures336.sh run sent a wrong cancellation field (setup note 1):
# ABOUTME: cancels the Dario message and builds the 26-message history of QA336 Mia Paginada. Global switch OFF.
# shellcheck disable=SC2046  # $(... echo PASS || echo FAIL) always yields one word
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/qa336.sh"
assert_safe_stack false || exit 2
qa_login testuser || exit 3
cancel "$M_H1C"
rec SETUP "customer QA336 Dario Cancelado: message cancelled (rerun)" "$([ "$(jb .status)" = CANCELLED ] && echo PASS || echo FAIL)" "msg=${M_H1C:0:8} status=$(mstate "$M_H1C")"
ok=0
cancel "$M_H6_1"; [ "$(jb .status)" = CANCELLED ] && ok=1
for i in $(seq 2 25); do
  mkmsg "M_H6_$i" testuser "$H6" "$H6_C" && v="M_H6_$i" && cancel "${!v}" && [ "$(jb .status)" = CANCELLED ] && ok=$((ok + 1))
done
mkmsg M_H6_26 testuser "$H6" "$H6_C"
rec SETUP "QA336 Mia Paginada: 25 cancelled + 1 pending (rerun)" "$([ "$ok" = 25 ] && [ "$(cust_msgs "$H6")" = 26 ] && echo PASS || echo FAIL)" "cancelled=$ok total=$(cust_msgs "$H6")"
rec SETUP "side effects after setup" NOTE "attempts_total=$(attempts_all) stub_sends=$(stub_sends) inbox_rows=$(inbox_rows '')"
qa_finish
