#!/usr/bin/env bash
# ABOUTME: Suite #340 REG-02 fixture and baseline: one due customer with a grounded purchase description, plus a snapshot
# ABOUTME: of its consent, follow-up policy, messages and the global counters before the live AI draft. Synthetic data.
set -uo pipefail
. "$(dirname "$0")/qa340.sh"
C=REG-02
qa_login testuser || exit 1
[ -n "${AI_C:-}" ] || mkcust AI_C "QA340 Lucía Borrador" || exit 1
req testuser "$A" POST "/api/customers/$AI_C/purchases" "$(jq -cn --arg t "$(date -u -d '-35 days' +%FT14:00:00Z)" '{purchasedAt:$t,description:"Café de grano tostado medio 1 kg y filtros de papel"}')" -H "Idempotency-Key: qa340-ai-$AI_C"
req testuser "$A" GET "/api/customers/$AI_C/contact-policy" -; POL0="$(etag)|$(jb '[.doNotContact, (.consents|map(.status)|join(","))]|@json')"; setid AI_POL0 "$(printf '%s' "$POL0" | md5sum | cut -c1-12)"
req testuser "$A" GET "/api/customers/$AI_C/follow-up-policy" -; FU0="$(etag)|$(jb '[.cadenceDays,.explicitNextDate,.snoozedUntil]|@json')"; setid AI_FU0 "$(printf '%s' "$FU0" | md5sum | cut -c1-12)"
setid AI_SNAP0 "$(snap1)"
rec $C "baseline before the live draft" NOTE "customer=${AI_C:0:8} policy=$POL0 ($AI_POL0) followup=$FU0 ($AI_FU0) snap=$AI_SNAP0 ai_rows=$(ai_n)"
qa_finish
