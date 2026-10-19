#!/usr/bin/env bash
# QA suite #123 RES-08: uncertain business mutation (response lost after commit) with the documented If-Match / Idempotency-Key controls. API level, persisted state compared in PostgreSQL.
cd /home/stevdrey/Projects/Java/dokene-qa-run; . /home/stevdrey/qa123/s/base.sh
fresh() { local id=$(mk "$1" "$(rnd_phone)" ""); consent $id GRANTED >/dev/null; buy $id 2026-07-01T14:00:00Z "Café de grano 1 kg" >/dev/null; echo $id; }
cnt() { psqlq "select count(*) from dokene.$1 where customer_id='$2'"; }
aud() { psqlq "select count(*) from dokene.audit_events where target_id='$2' and event_type='$1'"; }
pv() { api testoperator GET /api/customers/$1/follow-up-policy ''; etag; }
post() { # path key etag notes -> CODE BODY
  api testoperator POST "$1" "{\"notes\":\"$4\"}" -H "If-Match: \"$3\"" -H "Idempotency-Key: $2"; }
for M in ${MUTATIONS:-manual dismissal}; do
  if [ $M = manual ]; then P=manual-follow-ups; T=manual_follow_up_completions; E=MANUAL_FOLLOW_UP_RECORDED; else P=follow-up-dismissals; T=follow_up_dismissals; E=FOLLOW_UP_DISMISSED; fi
  echo "== $M: response lost after commit"; C=$(fresh "Res08 $M $RUN"); V0=$(pv $C); K="res08-$M-$RUN-1"
  post /api/customers/$C/$P $K $V0 "primer envío" ; echo "  first request (response treated as lost): http=$CODE $( [ "$CODE" != 201 ] && echo "$BODY" | cut -c1-200)"; ORIGID=$(echo "$BODY"|jq -r .id); V1=$(pv $C)
  chk RES-08 "$M: persisted rows after the first request" 1 "$(cnt $T $C)"; chk RES-08 "$M: the policy version advanced exactly once" "$((V0+1))" "$V1"
  post /api/customers/$C/$P $K $V0 "primer envío"; chk RES-08 "$M: replay with the same key and the old If-Match returns the original record (200, same id)" "200 $ORIGID" "$CODE $(echo "$BODY"|jq -r .id)"
  post /api/customers/$C/$P $K $V0 "NOTAS DISTINTAS EN EL REINTENTO"; chk RES-08 "$M: replay with different notes returns the original record unchanged" "200 primer envío" "$CODE $(echo "$BODY"|jq -r .notes)"
  chk RES-08 "$M: still exactly 1 persisted row and 1 audit event; policy version unchanged by replays" "1 1 $V1" "$(cnt $T $C) $(aud $E $C) $(pv $C)"
  post /api/customers/$C/$P "res08-$M-$RUN-2" $V0 "nueva llave, ETag viejo"; chk RES-08 "$M: new key with the stale If-Match is rejected (409), no duplicate" "409 1" "$CODE $(cnt $T $C)"
  post /api/customers/$C/$P "res08-$M-$RUN-3" $V1 "nueva llave, ETag fresco"; echo "  new key with a fresh If-Match after the customer was already handled -> http=$CODE ($(echo "$BODY"|jq -r '.message // .error // "-"'  2>/dev/null)); rows now $(cnt $T $C)"; if [ $M = manual ]; then chk RES-08 "$M: a deliberate second completion (new key, fresh If-Match) is allowed by design for an eligible customer (ADR 0013, early manual touch)" "201 2" "$CODE $(cnt $T $C)"; else chk RES-08 "$M: a second dismissal after the customer was handled is rejected (409), no duplicate" "409 1" "$CODE $(cnt $T $C)"; fi
  C2=$(fresh "Res08 $M other $RUN"); V2=$(pv $C2); post /api/customers/$C2/$P $K $V2 "misma llave otro cliente"; chk RES-08 "$M: reusing the key for another customer is rejected (409) and writes nothing" "409 0" "$CODE $(cnt $T $C2)"
  echo "  -- concurrent replays: 6 parallel identical requests"; C3=$(fresh "Res08 $M parallel $RUN"); V3=$(pv $C3); K3="res08-$M-$RUN-par"; j=$QA_TMP/jar-testoperator.txt; tok=$(curl -s -b $j $BFF/api/session|jq -r .csrfToken); for i in 1 2 3 4 5 6; do ( curl -s -o /dev/null -w '%{http_code}\n' -b $j -X POST "$BFF/api/customers/$C3/$P" -H "X-Tenant-Id: $TENANT" -H "X-CSRF-TOKEN: $tok" -H 'Content-Type: application/json' -H "If-Match: \"$V3\"" -H "Idempotency-Key: $K3" -d '{"notes":"paralelo"}' >> $QA_TMP/par-$M.txt ) & done; wait; echo "  codes: $(sort $QA_TMP/par-$M.txt | uniq -c | tr '\n' ' ')"; chk RES-08 "$M: parallel identical requests persist exactly one record" 1 "$(cnt $T $C3)"; chk RES-08 "$M: exactly one 201 among the parallel responses" 1 "$(grep -c 201 $QA_TMP/par-$M.txt)"; chk RES-08 "$M: no 5xx among the parallel responses" 0 "$(grep -c '^5' $QA_TMP/par-$M.txt)"
  echo "  -- AI calls after the disposition stay independent"; a0=$(cnt $T $C); sleep 3.3; rec $C; echo "  recommendation after the disposition: $(echo "$REC_BODY"|jq -c '{status}')"; chk RES-08 "$M: an AI call neither creates nor changes dispositions" "$a0" "$(cnt $T $C)"
done
echo "== snooze: response lost after commit"; C=$(fresh "Res08 snooze $RUN"); V0=$(pv $C); a0=$(aud FOLLOW_UP_SNOOZED $C)
api testoperator PUT /api/customers/$C/follow-up-snooze '{"until":"2030-01-01"}' -H "If-Match: \"$V0\""; echo "  first snooze: http=$CODE"; V1=$(pv $C)
api testoperator PUT /api/customers/$C/follow-up-snooze '{"until":"2030-01-01"}' -H "If-Match: \"$V0\""; chk RES-08 "snooze: replay with the stale If-Match is rejected (409)" 409 "$CODE"
api testoperator PUT /api/customers/$C/follow-up-snooze '{"until":"2030-01-01"}' -H "If-Match: \"$V1\""; echo "  re-snooze to the same date with the fresh If-Match: http=$CODE, audit snooze events: $(aud FOLLOW_UP_SNOOZED $C)"
chk RES-08 "snooze: final state is the requested date, one policy row" "2030-01-01 1" "$(getj testoperator /api/customers/$C/follow-up-policy | jq -r .snoozedUntil) $(psqlq "select count(*) from dokene.customer_follow_up_policies where customer_id='$C'")"
echo "PASS=$PASSN FAIL=$FAILN"
