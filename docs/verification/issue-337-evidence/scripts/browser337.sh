#!/usr/bin/env bash
# ABOUTME: Records the #337 browser observations (built-in Chromium, OWNER testuser) for SEC-03 and SEC-05 and checks the
# ABOUTME: server-side state they depend on: no mutation from the foreign-origin page, the canary message stored as text.
# Usage: browser337.sh <xss-message-id>. The observations themselves were read from the page (innerText, DOM queries,
# storage dump, network panel); this script writes them down next to the database checks.
set -uo pipefail
# This script sends session values: it loads the protected curl wrapper itself (scripts/tests/bff-login-test.sh, #401).
. "$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)/scripts/lib/curl-protected.sh" || { echo "scripts/lib/curl-protected.sh not found" >&2; exit 1; }
. "$(dirname "$0")/qa337.sh"
XM=$1
rec SEC-03 "Browser: page on http://localhost:18337 (same site, session cookie attached): no-cors text/plain POST cancel -> 403; form POST cancel -> 403; credentialed send -> preflight 403, blocked; credentialed GET /api/session -> blocked by CORS" PASS "manual browser observation"
rec SEC-03 "Browser: page on http://127.0.0.1:18337 (cross site): the same four requests -> 403 or blocked" PASS "manual browser observation"
rec SEC-03 "after both foreign-origin pages: target approved message unchanged, no stub request" "$( { [ "$(psqlq "select status from dokene.outbound_messages where id='$PT'")" = APPROVED ] && [ "$(stub_sends)" = 22 ]; } && echo PASS || echo FAIL)" "status=$(psqlq "select status||'/v'||version from dokene.outbound_messages where id='$PT'") stub_sends=$(stub_sends)"
rec SEC-05 "Browser: queue, customer detail, AI recommendation and draft show the XSS canary as literal text; window flag unset, no injected img/svg, no on* attributes, {{7*7}} not evaluated" PASS "manual browser observation"
rec SEC-05 "Browser: canary AI draft submitted through the UI review; preview (Lo que recibirá el cliente), message history row and timeline render it as text; flag still unset" PASS "manual browser observation; message ${XM:0:8}"
rec SEC-05 "Browser storage: localStorage empty; sessionStorage holds only the active tenant id; no token, CSRF, canary or secret; document.cookie exposes no cookie (HttpOnly)" PASS "manual browser observation"
rec SEC-05 "Network: draft and message JSON carry the canary as an escaped JSON string; renderedBody keeps {{7*7}} literal; no QA333-CANARY secret in any page or response read" PASS "manual browser observation"
rec SEC-05 "Browser: workspace switched to QA Café Sur shows no tenant A name, canary, customer or message id" PASS "manual browser observation"
rec SEC-05 "canary message stored verbatim as text (AI_DRAFT origin, pending approval)" "$( { psqlq "select body from dokene.outbound_messages where id='$XM'" | grep -qF 'QA337-XSS <img src=x onerror=' && [ "$(psqlq "select origin||'/'||status from dokene.outbound_messages where id='$XM'")" = AI_DRAFT/PENDING_APPROVAL ]; } && echo PASS || echo FAIL)" "msg=${XM:0:8}"
L=$(docker compose -f "$WT/compose.yaml" logs --no-color --since 3h backend frontend oidc-bridge 2>&1)
hits=0; for p in QA337-XSS onerror 'QA337 Canario' 'QA337 nota' QA333-CANARY 56983360018 JSESSIONID= X-CSRF; do hits=$((hits + $(printf '%s\n' "$L" | grep -cF -- "$p"))); done
rec SEC-05 "container logs (backend, frontend, oidc-bridge; last 3 h) hold no canary, name, note, phone, secret canary, session or CSRF value" "$( { [ $hits = 0 ]; } && echo PASS || echo FAIL)" "lines=$(printf '%s\n' "$L" | wc -l) hits=$hits"
rec SEC-05 "audit rows hold no canary text (AI detail and denial reasons are closed codes)" "$( { [ "$(psqlq "select count(*) from dokene.audit_events where coalesce(ai_detail,'')||coalesce(denial_reason,'') ~* 'QA337|onerror|<'")" = 0 ]; } && echo PASS || echo FAIL)" "-"
setid XM "$XM"
# Recorded FAIL lines make the exit status nonzero.
qa_finish
