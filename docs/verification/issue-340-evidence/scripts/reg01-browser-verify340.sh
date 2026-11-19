#!/usr/bin/env bash
# ABOUTME: Suite #340 REG-01 browser leg, read-back after the person-driven dialogs: one completion, one dismissal,
# ABOUTME: the archive transition and that no provider request was made. Read-only.
set -uo pipefail
. "$(dirname "$0")/qa340.sh"
C=REG-01-UI
qa_login testuser || exit 1
chk $C "'Registrar seguimiento' dialog stored exactly one completion" 1 "$(psqlq "select count(*) from dokene.manual_follow_up_completions where customer_id='$UI_DONE'")"
chk $C "'Descartar' dialog stored exactly one dismissal" 1 "$(psqlq "select count(*) from dokene.follow_up_dismissals where customer_id='$UI_DISM'")"
chk $C "'Confirmar y archivar' archived the dismissed customer" ARCHIVED "$(psqlq "select status from dokene.customers where id='$UI_DISM'")"
req testuser "$A" GET "/api/customers/$UI_DONE/follow-up-eligibility" -; chk $C "completed customer NOT_YET_DUE" NOT_YET_DUE "$(jb .status)"
chk $C "no provider request during the browser leg" "$STUB0_340" "$(stub_sends)"
qa_finish
