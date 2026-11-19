#!/usr/bin/env bash
# ABOUTME: Suite #340 REG-03 fixtures: due customers for the session-expiry, AI-unavailable and AI-disabled legs.
set -uo pipefail
. "$(dirname "$0")/qa340.sh"
qa_login testuser || exit 1
[ -n "${SE_C:-}" ] || mkcust SE_C "QA340 Sesión Vencida" || exit 1
[ -n "${UN_C:-}" ] || mkcust UN_C "QA340 IA Caída" || exit 1
[ -n "${DI_C:-}" ] || mkcust DI_C "QA340 IA Apagada" || exit 1
echo "fixtures ok"
