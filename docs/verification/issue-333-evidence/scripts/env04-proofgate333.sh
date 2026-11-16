#!/usr/bin/env bash
# ABOUTME: P3-MQA-ENV-04 isolated proof gate (user-approved controlled deviation): global outbound ON only against the
# ABOUTME: loopback Graph stub with synthetic tokens; unproven and disabled integrations must refuse with zero stub sends.
# shellcheck disable=SC2046  # $(... echo PASS || echo FAIL) always yields one word
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/qa333.sh"
. "$OUTD/ids"
C=ENV-04
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# Nothing is touched unless this is the stub stack with the global switch ON (otherwise no cleanup either: the stack
# is not ours to restart, and an ownership proof could reach a real provider).
assert_safe_stack true || exit 2
MID=
integ() { req testuser "$A" GET /api/integrations/whatsapp -; }
# Fail-safe cleanup, installed before anything can abort: restore the tenant, restart the backend with the global
# switch OFF, verify the effective global and tenant values, and exit nonzero on any cleanup failure or recorded FAIL.
cleanup() {
  local rc=$? bad=0
  trap - EXIT
  # Ignore further interrupts while restoring: ignored signals are inherited by the docker compose restart below, so
  # a second Ctrl-C or a process-group TERM (GNU timeout) cannot leave the backend half-restarted.
  trap '' INT TERM HUP
  set +e
  echo "proof-gate cleanup (script exit $rc)" >&2
  { qa_login testuser && qa_login testoperator; } || bad=1
  if [ -n "$MID" ]; then
    req testoperator "$A" GET "/api/messages/$MID" -
    case "$(printf '%s' "$BODY" | jq -r .status)" in APPROVED|PENDING_APPROVAL)
      req testoperator "$A" POST "/api/messages/$MID/cancellations" '{}' -H "If-Match: $(etag)" -H "Idempotency-Key: qa333-pg-c-$MID" ;;
    esac
    req testoperator "$A" GET "/api/messages/$MID" -
    case "$(printf '%s' "$BODY" | jq -r .status)" in APPROVED|PENDING_APPROVAL) bad=1 ;; esac
    rec $C "[proof-gate] cleanup: probe message" NOTE "status=$(printf '%s' "$BODY" | jq -r '.status // .code')"
  fi
  integ; req testuser "$A" PUT /api/integrations/whatsapp/outbound '{"enabled":false}' -H "If-Match: $(etag)"
  CFG=$(printf '%s' "$BODY" | jq -c '{phoneNumberId:"100000000000001",businessAccountId,displayPhoneNumber,enabled:true,secretRef}')
  req testuser "$A" PUT /api/integrations/whatsapp "$CFG" -H "If-Match: $(etag)"
  req testuser "$A" POST /api/integrations/whatsapp/ownership-verifications - -H "If-Match: $(etag)"
  # Tenant restored first, then the global switch OFF (the restart ends every session, so log in again to verify).
  # Own session: a process-group signal (Ctrl-C, GNU timeout) cannot reach docker compose, which re-arms SIGTERM.
  QA333_OUTBOUND=false setsid --wait bash "$HERE/stack333.sh" restart >&2 || bad=1
  if assert_safe_stack false; then G=off; else G=ON; bad=1; fi
  qa_login testuser || bad=1
  integ; T=$(printf '%s' "$BODY" | jq -r '"\(.enabled)/\(.webhookVerified)/\(.outboundEnabled)"')
  [ "$G/$T" = off/true/true/false ] || bad=1
  rec $C "[proof-gate] cleanup: global OFF, tenant OFF, integration enabled+proven (verified after restart)" \
    "$([ "$G/$T" = off/true/true/false ] && echo PASS || echo FAIL)" "global=$G enabled/proven/tenantOutbound=$T"
  [ "$bad" -eq 0 ] || { echo "proof-gate CLEANUP FAILED: check the global and tenant switches by hand" >&2; exit 4; }
  [ "$rc" -eq 0 ] || exit "$rc"
  qa_finish
}
trap cleanup EXIT
trap 'exit 130' INT TERM HUP
rec $C "[proof-gate] precondition: Graph base URL is the loopback stub, global switch ON" PASS "base=loopback-stub global=true"
S0=$(stub_sends) || exit 2
for u in testuser testoperator; do qa_login $u || exit 2; done
integ; CFG=$(printf '%s' "$BODY" | jq -c '{phoneNumberId:"100000000000003",businessAccountId,displayPhoneNumber,enabled:true,secretRef}')
req testuser "$A" PUT /api/integrations/whatsapp "$CFG" -H "If-Match: $(etag)"
req testuser "$A" PUT /api/integrations/whatsapp/outbound '{"enabled":true}' -H "If-Match: $(etag)"
rec $C "[proof-gate] integration unproven, enabled, tenant switch ON" $([ "$(printf '%s' "$BODY" | jq -r '"\(.webhookVerified)/\(.enabled)/\(.outboundEnabled)"')" = false/true/true ] && echo PASS || echo FAIL) "$(printf '%s' "$BODY" | jq -c '{webhookVerified,enabled,outboundEnabled}')"
req testoperator "$A" GET /api/customers -; ANA=$(printf '%s' "$BODY" | jq -r '.customers[]|select(.displayName=="QA333 Ana Vencida").id')
req testoperator "$A" GET "/api/customers/$ANA" -; CONTACT=$(printf '%s' "$BODY" | jq -r '.phones[0].id')
req testoperator "$A" GET "/api/customers/$ANA/follow-up-policy" -; PV=$(etag)
SUB=$(jq -nc --arg c "$CONTACT" '{contactId:$c,action:"GENERAL_CHECK_IN",templateIntent:"GENERAL_FOLLOW_UP",body:"Hola Ana, ¿cómo te ha ido con tu compra?",locale:"es-419",origin:"MANUAL"}')
req testoperator "$A" POST "/api/customers/$ANA/messages" "$SUB" -H "If-Match: $PV" -H "Idempotency-Key: qa333-pg-$(date +%s)"
MID=$(printf '%s' "$BODY" | jq -r '.messageId // .id // empty'); [ -n "$MID" ] || { rec $C "[proof-gate] submit failed" FAIL "http=$CODE"; exit 1; }
req testoperator "$A" POST "/api/messages/$MID/approvals" '{"decision":"APPROVE"}' -H "If-Match: $(etag)" -H "Idempotency-Key: qa333-pg-ap-$MID"
rec $C "[proof-gate] message submitted and approved" NOTE "status=$(printf '%s' "$BODY" | jq -r .status)"
req testoperator "$A" POST "/api/messages/$MID/sends" - -H "If-Match: $(etag)" -H "Idempotency-Key: qa333-pg-s1-$MID"
rec $C "[proof-gate] UNPROVEN integration cannot send" $([ "$(printf '%s' "$BODY" | jq -r .code)" = INTEGRATION_DISABLED ] && echo PASS || echo FAIL) "http=$CODE code=$(printf '%s' "$BODY" | jq -r '.code // .status')"
# proven but integration disabled
integ; CFG=$(printf '%s' "$BODY" | jq -c '{phoneNumberId:"100000000000001",businessAccountId,displayPhoneNumber,enabled:true,secretRef}')
req testuser "$A" PUT /api/integrations/whatsapp "$CFG" -H "If-Match: $(etag)"
req testuser "$A" POST /api/integrations/whatsapp/ownership-verifications - -H "If-Match: $(etag)"
CFG=$(printf '%s' "$BODY" | jq -c '{phoneNumberId,businessAccountId,displayPhoneNumber,enabled:false,secretRef}')
req testuser "$A" PUT /api/integrations/whatsapp "$CFG" -H "If-Match: $(etag)"
rec $C "[proof-gate] disabling the integration clears the proof" $([ "$(printf '%s' "$BODY" | jq -r '"\(.enabled)/\(.webhookVerified)"')" = false/false ] && echo PASS || echo FAIL) "$(printf '%s' "$BODY" | jq -c '{enabled,webhookVerified,outboundEnabled}')"
req testoperator "$A" GET "/api/messages/$MID" -; MST=$(printf '%s' "$BODY" | jq -r .status)
req testoperator "$A" POST "/api/messages/$MID/sends" - -H "If-Match: $(etag)" -H "Idempotency-Key: qa333-pg-s2-$MID"
rec $C "[proof-gate] DISABLED integration cannot send" $([ "$(printf '%s' "$BODY" | jq -r '.code // empty')" = INTEGRATION_DISABLED ] && echo PASS || echo FAIL) "pre=$MST http=$CODE code=$(printf '%s' "$BODY" | jq -r '.code // .status')"
S1=$(stub_sends) || S1=; rec $C "[proof-gate] zero provider send calls" $([ -n "$S1" ] && [ "$S1" = "$S0" ] && echo PASS || echo FAIL) "stub sends before=$S0 after=$S1"
