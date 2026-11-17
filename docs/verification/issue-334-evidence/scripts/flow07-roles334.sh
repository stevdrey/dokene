#!/usr/bin/env bash
# ABOUTME: Suite #334 FLOW-07 role checks through the real BFF: OWNER submits; VIEWER can read but every
# ABOUTME: draft/approve/reject/cancel/send/AI command is refused 403 and changes nothing. Global outbound OFF.
# shellcheck disable=SC2046  # $(... echo PASS || echo FAIL) always yields one word
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/qa334.sh"; [ -f "$OUTD/ids" ] && . "$OUTD/ids"
C=FLOW-07
assert_safe_stack false || exit 2
for u in testuser testviewer; do qa_login $u; done
code() { printf '%s' "$BODY" | jq -r '.code // empty'; }
req testuser "$A" GET /api/customers/$CUST/follow-up-policy -; POL=$(etag)
req testuser "$A" POST /api/customers/$CUST/messages "$(jq -nc --arg c "$CONTACT" '{contactId:$c,action:"REPEAT_PURCHASE_FOLLOW_UP",templateIntent:"REPEAT_PURCHASE",body:"QA334 FLOW-07 mensaje del OWNER.",locale:"es-419",origin:"MANUAL"}')" -H "If-Match: $POL" -H "Idempotency-Key: $(uuidgen)"
M6=$(printf '%s' "$BODY" | jq -r '.messageId // empty'); V=$(etag); REV=$(printf '%s' "$BODY" | jq -r '.template.revision // empty')
rec $C "OWNER testuser submits a probe -> PENDING_APPROVAL" $([ "$(printf '%s' "$BODY" | jq -r .status)" = PENDING_APPROVAL ] && echo PASS || echo FAIL) "http=$CODE msg=${M6:0:8} code=$(code)"
[ -n "$M6" ] || exit 1
echo "M6=$M6" >> "$OUTD/ids"
# VIEWER: read allowed, every mutation refused, nothing changes
req testviewer "$A" GET /api/messages/$M6 -; VV=$(etag)
rec $C "VIEWER reads the pending message (MESSAGE_READ)" $([ "$CODE" = 200 ] && echo PASS || echo FAIL) "http=$CODE etag=$VV"
req testviewer "$A" POST /api/messages/$M6/approvals "{\"decision\":\"APPROVE\",\"templateRevision\":\"$REV\"}" -H "If-Match: $V" -H "Idempotency-Key: $(uuidgen)"; rec $C "VIEWER approve -> 403" $([ "$CODE" = 403 ] && echo PASS || echo FAIL) "http=$CODE code=$(code)"
req testviewer "$A" POST /api/messages/$M6/approvals '{"decision":"REJECT"}' -H "If-Match: $V" -H "Idempotency-Key: $(uuidgen)"; rec $C "VIEWER reject -> 403" $([ "$CODE" = 403 ] && echo PASS || echo FAIL) "http=$CODE code=$(code)"
req testviewer "$A" POST /api/messages/$M6/cancellations '{}' -H "If-Match: $V" -H "Idempotency-Key: $(uuidgen)"; rec $C "VIEWER cancel -> 403" $([ "$CODE" = 403 ] && echo PASS || echo FAIL) "http=$CODE code=$(code)"
req testviewer "$A" POST /api/messages/$M6/sends - -H "If-Match: $V" -H "Idempotency-Key: $(uuidgen)"; rec $C "VIEWER send -> 403" $([ "$CODE" = 403 ] && echo PASS || echo FAIL) "http=$CODE code=$(code)"
req testviewer "$A" POST /api/customers/$CUST/messages "$(jq -nc --arg c "$CONTACT" '{contactId:$c,action:"REPEAT_PURCHASE_FOLLOW_UP",templateIntent:"REPEAT_PURCHASE",body:"x",locale:"es-419",origin:"MANUAL"}')" -H "If-Match: $POL" -H "Idempotency-Key: $(uuidgen)"; rec $C "VIEWER submit -> 403" $([ "$CODE" = 403 ] && echo PASS || echo FAIL) "http=$CODE code=$(code)"
req testviewer "$A" POST /api/customers/$CUST/follow-up-draft '{}'; rec $C "VIEWER AI draft request -> 403" $([ "$CODE" = 403 ] && case "$(code)" in ""|FORBIDDEN) true;; *) false;; esac && echo PASS || echo FAIL) "http=$CODE code=$(code)"
req testviewer "$A" GET /api/messaging/locales -; rec $C "VIEWER compose locales (MESSAGE_DRAFT) -> 403" $([ "$CODE" = 403 ] && echo PASS || echo FAIL) "http=$CODE"
req testuser "$A" GET /api/messages/$M6 -
rec $C "after VIEWER attempts M6 unchanged (PENDING_APPROVAL, same ETag)" $([ "$(printf '%s' "$BODY" | jq -r .status)" = PENDING_APPROVAL ] && [ "$(etag)" = "$V" ] && echo PASS || echo FAIL) "etag=$(etag)"
rec $C "audit records no VIEWER message command" $([ "$(psqlq "select count(*) from dokene.audit_events where target_id='$M6' and event_type like 'MESSAGE_%' and event_type<>'MESSAGE_SUBMITTED'")" = 0 ] && echo PASS || echo FAIL) "-"
qa_finish
