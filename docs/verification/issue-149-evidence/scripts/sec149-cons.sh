#!/usr/bin/env bash
# ABOUTME: Suite #149, revoked consent cases P3-CONS-01 to P3-CONS-10 over real HTTP: archive, do not contact, consent
# ABOUTME: and eligibility re-checked at submit, approve and send, plus the revoke-versus-send race.
cd "$(git rev-parse --show-toplevel)"; . docs/verification/issue-149-evidence/scripts/env149.sh
H=HTTP; stub_reset; stub_set send accept
O=$A_OPERATOR

echo "== P3-CONS-01 submit against an archived, do not contact, unconsented or not due customer"
read -r c p <<< "$(mk_customer $O "$A" "CONS01 archived $RUN")"; make_due $O "$A" "$c" "$p"; archive_customer $A_OWNER "$A" "$c"; S=$(snap); submit $O "$A" "$c" "$p" "" '"0"'; refused P3-CONS-01 $H "archived" CUSTOMER_ARCHIVED 409 "$S"
read -r c p <<< "$(mk_customer $O "$A" "CONS01 dnc $RUN")"; make_due $O "$A" "$c" "$p"; dnc $O "$A" "$c" true; S=$(snap); submit $O "$A" "$c" "$p"; refused P3-CONS-01 $H "do not contact" DO_NOT_CONTACT 409 "$S"
read -r c p <<< "$(mk_customer $O "$A" "CONS01 noconsent $RUN")"; buy $O "$A" "$c" "$(date -u -v-45d +%FT%TZ 2>/dev/null || date -u -d '45 days ago' +%FT%TZ)" >/dev/null; S=$(snap); submit $O "$A" "$c" "$p"; refused P3-CONS-01 $H "no consent" NO_CONTACT_CONSENT 409 "$S"
read -r c p <<< "$(mk_customer $O "$A" "CONS01 notdue $RUN")"; consent $O "$A" "$c" "$p" GRANTED >/dev/null; buy $O "$A" "$c" "$(date -u +%FT%TZ)" >/dev/null; S=$(snap); submit $O "$A" "$c" "$p"; refused P3-CONS-01 $H "not due" FOLLOW_UP_INELIGIBLE 409 "$S"

echo "== P3-CONS-02 and P3-CONS-03 approve after a consent revoke or a do not contact"
read -r c p MP <<< "$(ready_pending $O "$A" "CONS02 $RUN")"; consent $O "$A" "$c" "$p" REVOKED >/dev/null; S=$(snap); approve $O "$A" "$MP"; refused P3-CONS-02 $H "approve after revoke" NO_CONTACT_CONSENT 409 "$S"
read -r c p MP <<< "$(ready_pending $O "$A" "CONS03 $RUN")"; dnc $O "$A" "$c" true >/dev/null; S=$(snap); approve $O "$A" "$MP"; refused P3-CONS-03 $H "approve after do not contact" DO_NOT_CONTACT 409 "$S"

echo "== P3-CONS-04 to P3-CONS-07 send after a consent, do not contact, archive or eligibility change"
read -r c p MA <<< "$(ready_approved $O "$A" "CONS04 $RUN")"; consent $O "$A" "$c" "$p" REVOKED >/dev/null; S=$(snap); send $O "$A" "$MA"; refused P3-CONS-04 $H "send after revoke" NO_CONTACT_CONSENT 409 "$S"; chk P3-CONS-04 $H "still APPROVED, no attempt" "APPROVED 0" "$(msg_status "$MA" | cut -d/ -f1) $(attempts_n "$MA")"
read -r c p MA <<< "$(ready_approved $O "$A" "CONS05 $RUN")"; dnc $O "$A" "$c" true >/dev/null; S=$(snap); send $O "$A" "$MA"; refused P3-CONS-05 $H "send after do not contact" DO_NOT_CONTACT 409 "$S"; chk P3-CONS-05 $H "no attempt" 0 "$(attempts_n "$MA")"
read -r c p MA <<< "$(ready_approved $O "$A" "CONS06 $RUN")"; archive_customer $A_OWNER "$A" "$c"; S=$(snap); send $O "$A" "$MA"; refused P3-CONS-06 $H "send after archive" CUSTOMER_ARCHIVED 409 "$S"; chk P3-CONS-06 $H "no attempt" 0 "$(attempts_n "$MA")"
read -r c p MA <<< "$(ready_approved $O "$A" "CONS07 $RUN")"; manual_follow_up $O "$A" "$c"; mf=$CODE; S=$(snap); send $O "$A" "$MA"; refused P3-CONS-07 $H "send after a manual follow up (recorded: $mf)" FOLLOW_UP_INELIGIBLE 409 "$S"; chk P3-CONS-07 $H "no attempt" 0 "$(attempts_n "$MA")"

echo "== P3-CONS-08 a consent revocation and a send fired at the same moment, 8 rounds"
sent=0; refusedn=0; bad=0; n0=$(stub_calls send)
for i in $(seq 1 8); do read -r c p MA <<< "$(ready_approved $O "$A" "CONS08 $i $RUN")"
  v=$(contact_policy_version $O "$A" "$c"); e=$(msg_etag $O "$A" "$MA"); tok=$(csrf $O); j=$QA_TMP/jar-$O.txt
  curl -s -o "$QA_TMP/rv.$i" -w '%{http_code}' -b "$j" -X PUT "$BFF/api/customers/$c/contacts/$p/consents/WHATSAPP" -H "X-Tenant-Id: $A" -H "X-CSRF-TOKEN: $tok" -H 'Content-Type: application/json' -H "If-Match: \"$v\"" -d '{"status":"REVOKED","source":"CUSTOMER_VERBAL"}' > "$QA_TMP/rvc.$i" &
  curl -s -o "$QA_TMP/sd.$i" -w '%{http_code}' -b "$j" -X POST "$BFF/api/messages/$MA/sends" -H "X-Tenant-Id: $A" -H "X-CSRF-TOKEN: $tok" -H "Idempotency-Key: qa149-$RUN-race$i" -H "If-Match: $e" > "$QA_TMP/sdc.$i" &
  wait; sc=$(cat "$QA_TMP/sdc.$i"); st=$(msg_status "$MA" | cut -d/ -f1)
  case "$sc/$st" in 201/SENT) sent=$((sent+1));; 409/APPROVED) refusedn=$((refusedn+1));; *) bad=$((bad+1)); echo "  round $i: send $sc, status $st, revoke $(cat "$QA_TMP/rvc.$i")";; esac
done
chk P3-CONS-08 $H "every round either sent (201/SENT) or was refused (409/APPROVED): sent=$sent refused=$refusedn" 0 "$bad"
chk P3-CONS-08 $H "provider calls equal the number of sends that committed" "$sent" "$(stub_since send $n0)"
rec P3-CONS-08 $H PASS "ordering observed, not forced: the real stack has no hook to hold a send open across the revoke (fault injection limit)"

echo "== P3-CONS-09 consent on one contact never covers another"
req $O "$A" POST /api/customers "$(jq -nc --arg n "CONS09 $RUN" --arg p1 "$(rnd_phone)" --arg p2 "$(rnd_phone)" '{displayName:$n,phones:[{number:$p1,region:"CL",primary:true},{number:$p2,region:"CL",primary:false}]}')"
c=$(echo "$BODY" | jq -r .id); p=$(getj $O "$A" "/api/customers/$c" | jq -r '.phones[]|select(.primary==true)|.id'); p2=$(getj $O "$A" "/api/customers/$c" | jq -r '.phones[]|select(.primary==false)|.id')
chk P3-CONS-09 $H "customer with two contacts" "201 yes" "$CODE $([ -n "$p2" ] && [ -n "$p" ] && echo yes || echo no)"
make_due $O "$A" "$c" "$p"; S=$(snap); submit $O "$A" "$c" "$p2"; refused P3-CONS-09 $H "submit with the unconsented contact" NO_CONTACT_CONSENT 409 "$S"
submit $O "$A" "$c" "$p"; chk P3-CONS-09 $H "control: the consented contact submits" 201 "$CODE"

echo "== P3-CONS-10 reject and cancel still work after a revoke (not a finding)"
read -r c p MP <<< "$(ready_pending $O "$A" "CONS10r $RUN")"; consent $O "$A" "$c" "$p" REVOKED >/dev/null; reject $O "$A" "$MP"; chk P3-CONS-10 $H "reject after revoke" 201 "$CODE"
read -r c p MA <<< "$(ready_approved $O "$A" "CONS10c $RUN")"; consent $O "$A" "$c" "$p" REVOKED >/dev/null; cancel $O "$A" "$MA"; chk P3-CONS-10 $H "cancel after revoke" 201 "$CODE"
summary
