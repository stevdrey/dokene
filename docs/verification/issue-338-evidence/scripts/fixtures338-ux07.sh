#!/usr/bin/env bash
# ABOUTME: Suite #338 UX-07 fixtures: two pending messages (approval in flight across navigation, Chromium only; and
# ABOUTME: across a viewport change) and a customer for the logout/relogin draft case. Tenant A, synthetic data.
#   [SUF=" FF2" IDSUF=G] fixtures338-ux07.sh   then   [IDK=G] node ux07-338.mjs <browser> "$SUF"
set -uo pipefail
. "$(dirname "$0")/qa338.sh"
C=SETUP
qa_login testuser || exit 1
S=${SUF:-}; I=${IDSUF:-}
mk() { [ -n "${!1:-}" ] || mkcust "$1" "$2" || exit 1; }
mm() { local c="$2" cc="${2}_C"; [ -n "${!1:-}" ] || mkmsg "$1" testuser "${!c}" "${!cc}" || exit 1; }
mk "W1$I" "QA338 Vuelo Uno$S";  mm "W1M$I" "W1$I"
mk "W3$I" "QA338 Vuelo Tres$S"; mm "W3M$I" "W3$I"
mk "D1$I" "QA338 Borrador Sesion$S"
m1="W1M$I"; m3="W3M$I"
rec $C "UX-07 fixtures${S}: two pending messages and a draft customer" "$( { [ "$(mstate "${!m1}" | cut -d/ -f1)" = PENDING_APPROVAL ] && [ "$(mstate "${!m3}" | cut -d/ -f1)" = PENDING_APPROVAL ]; } && echo PASS || echo FAIL)" "suffix=${I:-none}"
qa_finish
