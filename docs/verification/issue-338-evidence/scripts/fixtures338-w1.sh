#!/usr/bin/env bash
# ABOUTME: Suite #338 window W1 fixtures (created with the global switch OFF): one approved message per delivery state
# ABOUTME: to produce (accepted, delivered, read, failed, uncertain) and two for the browser send-ambiguity cases.
set -uo pipefail
. "$(dirname "$0")/qa338.sh"
C=SETUP
qa_login testuser || exit 1
for pair in "SA:QA338 Estado Aceptado" "SD:QA338 Estado Entregado" "SR:QA338 Estado Leido" "SF:QA338 Estado Fallido" \
            "SU:QA338 Estado Sin Confirmar" "SI:QA338 Envio Interrumpido" "SX:QA338 Envio Doble Clic"; do
  k=${pair%%:*}
  [ -n "${!k:-}" ] || mkcust "$k" "${pair#*:}" || exit 1
  m=${k}M; c=${k}_C
  [ -n "${!m:-}" ] || mkmsg "$m" testuser "${!k}" "${!c}" approve || exit 1
done
ok=PASS; for k in SAM SDM SRM SFM SUM SIM SXM; do [ "$(mstate "${!k}" | cut -d/ -f1)" = APPROVED ] || ok=FAIL; done
rec $C "W1 fixtures: seven approved messages (SA SD SR SF SU SI SX)" "$ok" "stub sends=$(stub_sends)"
qa_finish
