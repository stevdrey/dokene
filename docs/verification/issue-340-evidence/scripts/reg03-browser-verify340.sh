#!/usr/bin/env bash
# ABOUTME: Suite #340 REG-03 browser read-back: the snooze confirmed after the session ended was never stored; manual
# ABOUTME: dispositions made in the UI while AI was unavailable/disabled were stored once. Read-only.
set -uo pipefail
. "$(dirname "$0")/qa340.sh"
C=REG-03-UI
qa_login testuser || exit 1
req testuser "$A" GET "/api/customers/$SE_C/follow-up-policy" -
chk $C "snooze confirmed in a page whose session had ended was not stored" "null" "$(jb .snoozedUntil)"
req testuser "$A" GET "/api/customers/$SE_C/follow-up-eligibility" -; chk $C "session-expiry customer still DUE" DUE "$(jb .status)"
chk $C "completion saved in the UI while AI was unavailable: exactly one row" 1 "$(psqlq "select count(*) from dokene.manual_follow_up_completions where customer_id='$UN_C'")"
[ "${1:-}" = disabled ] && chk $C "snooze saved in the UI while AI was disabled" "$(date -u -d '+1 days' +%F)" "$(req testuser "$A" GET "/api/customers/$DI_C/follow-up-policy" -; jb .snoozedUntil)"
qa_finish
