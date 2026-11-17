#!/usr/bin/env bash
# ABOUTME: Suite #338 fixtures: disposable tenant A customers (consent, purchase, due today) for the UI/UX cases,
# ABOUTME: one pending and one approved message for the viewport sweep, and a long/accented/emoji/HTML-like customer.
set -uo pipefail
. "$(dirname "$0")/qa338.sh"
C=SETUP
for u in testuser testadmin testoperator testviewer; do qa_login "$u" || { echo "login $u failed" >&2; exit 1; }; done
mk() { [ -n "${!1:-}" ] || mkcust "$1" "$2" || exit 1; }
mk K1 "QA338 Teclado Uno"
mk V1 "QA338 Vista Pendiente"
mk V2 "QA338 Vista Aprobada"
mk R1 "QA338 Red Interrumpida"
mk R2 "QA338 Conflicto Paralelo"
mk R3 "QA338 Sesion Vencida"
# shellcheck disable=SC1111  # the typographic quotes are test data (UX-06), not shell quotes
mk T1 "QA338 Ñandú José-María Pérez-Óñez 🎉 <b>Negrita</b> & “Comillas” — nombre largo para comprobar el ajuste en pantallas angostas"
[ -n "${VP:-}" ] || mkmsg VP testuser "$V1" "$V1_C" || exit 1
[ -n "${VA:-}" ] || mkmsg VA testuser "$V2" "$V2_C" approve || exit 1
rec $C "QA338 fixtures: 7 customers due today with WhatsApp consent; VP pending, VA approved" \
  "$( { [ "$(mstate "$VP" | cut -d/ -f1)" = PENDING_APPROVAL ] && [ "$(mstate "$VA" | cut -d/ -f1)" = APPROVED ]; } && echo PASS || echo FAIL)" \
  "VP=${VP:0:8} VA=${VA:0:8}"
qa_finish
