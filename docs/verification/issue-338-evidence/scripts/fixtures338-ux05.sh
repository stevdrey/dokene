#!/usr/bin/env bash
# ABOUTME: Suite #338 UX-05 fixtures: one pending message per recovery case (interrupted approval, parallel 409,
# ABOUTME: offline, ambiguous 500, ended session, revoked member, latency/double activation). Tenant A, synthetic data.
set -uo pipefail
. "$(dirname "$0")/qa338.sh"
C=SETUP
qa_login testuser || exit 1
mk() { [ -n "${!1:-}" ] || mkcust "$1" "$2" || exit 1; }
mm() { [ -n "${!1:-}" ] || mkmsg "$1" testuser "$2" "$3" || exit 1; }
# SUF/IDSUF (optional) build a parallel set for a rerun in another browser, e.g. SUF=" FF" IDSUF=F.
S=${SUF:-}; I=${IDSUF:-}
for pair in "R1:QA338 Red Interrumpida" "R2:QA338 Conflicto Paralelo" "R3:QA338 Sesion Vencida" "E5:QA338 Error Servidor" "G1:QA338 Sin Permiso" "H1:QA338 Latencia" "L1:QA338 Sesion Terminada"; do
  [ -z "$I" ] && [ "${pair%%:*}" != E5 ] && [ "${pair%%:*}" != G1 ] && [ "${pair%%:*}" != H1 ] && [ "${pair%%:*}" != L1 ] && continue
  mk "${pair%%:*}$I" "${pair#*:}$S"
done
for k in R1 R2 R3 E5 G1 H1 L1; do c="$k$I"; cc="${k}${I}_C"; mm "${k}M$I" "${!c}" "${!cc}"; done
ok=PASS; for k in R1M R2M R3M E5M G1M H1M L1M; do m=${k}$I; m=${!m}; [ "$(mstate "$m" | cut -d/ -f1)" = PENDING_APPROVAL ] || ok=FAIL; done
rec $C "UX-05 fixtures${S}: seven pending messages" "$ok" "suffix=${I:-none}"
qa_finish
