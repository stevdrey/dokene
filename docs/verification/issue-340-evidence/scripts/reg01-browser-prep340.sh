#!/usr/bin/env bash
# ABOUTME: Suite #340 REG-01 browser leg: reads back what the person-driven UI steps wrote (customer, consent, purchase,
# ABOUTME: snooze) and prepares two more due customers for the completion and dismissal dialogs. Synthetic data only.
set -uo pipefail
. "$(dirname "$0")/qa340.sh"
C=REG-01-UI
qa_login testuser || exit 1
NAV=$(psqlq "select id from dokene.customers where tenant_id='$A' and display_name='QA340 Navegador Regresión' order by created_at desc limit 1")
setid UI_NAV "$NAV"
rec $C "UI-created customer stored with E.164 phone, WhatsApp consent GRANTED (CUSTOMER_VERBAL) and one valid purchase" \
  "$( { [ "$(psqlq "select count(*) from dokene.customer_phone_contacts where customer_id='$NAV' and normalized_phone like '+569834%'")" = 1 ] \
     && [ "$(psqlq "select count(*) from dokene.purchases where customer_id='$NAV'")" = 1 ]; } && echo PASS || echo FAIL)" "customer=${NAV:0:8}"
req testuser "$A" GET "/api/customers/$NAV/contact-policy" -
chk $C "UI consent stored as GRANTED from CUSTOMER_VERBAL" "GRANTED CUSTOMER_VERBAL" "$(jb '.consents[0]|"\(.status) \(.source)"')"
req testuser "$A" GET "/api/customers/$NAV/follow-up-policy" -
rec $C "snooze chosen in the dialog ('En 3 días') is stored as tenant today + 3" \
  "$([ "$(jb .snoozedUntil)" = "$(date -u -d '+3 days' +%F)" ] && echo PASS || echo FAIL)" "snoozedUntil=$(jb .snoozedUntil)"
req testuser "$A" GET "/api/customers/$NAV/follow-up-eligibility" -
chk $C "snoozed customer is NOT_YET_DUE (left the queue)" NOT_YET_DUE "$(jb .status)"
[ -n "${UI_DONE:-}" ] || mkcust UI_DONE "QA340 Navegador Completar" || exit 1
[ -n "${UI_DISM:-}" ] || mkcust UI_DISM "QA340 Navegador Descartar" || exit 1
qa_finish
