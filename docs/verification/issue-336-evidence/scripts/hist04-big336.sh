#!/usr/bin/env bash
# ABOUTME: Suite #336 HIST-04 rerun of the oversized-body check after the first run read curl's interim "100 Continue"
# ABOUTME: status (setup note 2): a signed body one byte above the 4 MiB limit is refused before it is read.
# shellcheck disable=SC2046  # $(... echo PASS || echo FAIL) always yields one word
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/qa336.sh"
assert_safe_stack false || exit 2
C=HIST-04; M=$M_H4; R0=$(mrow "$M"); I0=$(inbox_rows ''); S0=$(stub_sends)
head -c 4194305 /dev/zero > "$OUTD/h04-big.bin"
wh_post "$OUTD/h04-big.bin" "$(wh_sig "$OUTD/h04-big.bin")"
rm -f "$OUTD/h04-big.bin"
rec $C "declared length above the 4 MiB limit -> 403 before reading (rerun)" "$([ "$WCODE" = 403 ] && no_cookie && [ "$(inbox_rows '')" = "$I0" ] && [ "$(mrow "$M")" = "$R0" ] && [ "$(stub_sends)" = "$S0" ] && echo PASS || echo FAIL)" "http=$WCODE set_cookie=$(no_cookie && echo none || echo PRESENT) inbox_delta=$(( $(inbox_rows '') - I0 ))"
qa_finish
