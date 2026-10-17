#!/usr/bin/env bash
# QA suite #121: SEC-05 gate absorption with FAULT-INJECTED malicious provider payloads (stub). NOT live-model evidence. Paced for the 20/min actor limit.
cd /home/stevdrey/Projects/Java/dokene-qa-run; . /home/stevdrey/qa121/s/env121.sh; : > $STUB/fail; echo 0 > $STUB/delay.ms
I1=$(mk "Gate Customer $RUN" "$(rnd_phone)" ""); consent $I1 GRANTED >/dev/null; buy $I1 2026-08-01T14:00:00Z "Café de grano 1 kg" >/dev/null
recp() { jq -nc --arg a "$1" --arg i "$2" --arg r "$3" --argjson c "${4:-0.9}" '{recommendation:{outcome:"ACTION",action:$a,templateIntent:$i,rationale:$r,confidence:$c,draftVariables:[]}}'; }
draftp() { jq -nc --arg a "$1" --arg i "$2" --arg b "$3" --arg l "$4" --arg e "$5" --arg r "${6:-Basado en la compra.}" '{draft:{outcome:"DRAFT",action:$a,templateIntent:$i,body:$b,draftVariables:[],locale:$l,evidence:[$e],warnings:[],rationale:$r,confidence:0.9}}'; }
RA=REPEAT_PURCHASE_FOLLOW_UP; RI=REPEAT_PURCHASE; EV='Compra: Café de grano 1 kg'
tryrec() { sleep 3.2; echo "$2" > $STUB/rec.json; local n0=$(wc -l < $STUB/requests.log); rec $I1; local st=$(echo "$REC_BODY"|jq -r .status) rs=$(echo "$REC_BODY"|jq -r '.unavailableReason // "-"')
  echo "  [rec] $1 -> http=$REC_CODE status=$st reason=$rs audit=$(ai_last) provider_calls=$(( $(wc -l < $STUB/requests.log) - n0 ))"; chk SEC-05F "rec/$1: not delivered as AVAILABLE" yes "$([ "$st" != AVAILABLE ] && echo yes || echo "AVAILABLE:$(echo "$REC_BODY"|jq -r .recommendation.rationale|cut -c1-80)")"; }
trydraft() { sleep 3.2; echo "$2" > $STUB/draft.json; local n0=$(wc -l < $STUB/requests.log); draft $I1 $RA $RI; local st=$(echo "$BODY"|jq -r .status) rs=$(echo "$BODY"|jq -r '.unavailableReason // "-"')
  echo "  [draft] $1 -> http=$CODE status=$st reason=$rs audit=$(ai_last) provider_calls=$(( $(wc -l < $STUB/requests.log) - n0 ))"; chk SEC-05F "draft/$1: not delivered as AVAILABLE" yes "$([ "$st" != AVAILABLE ] && echo yes || echo "AVAILABLE:$(echo "$BODY"|jq -r .draft.body|cut -c1-80)")"; }
echo "== recommendations with malicious payloads"
tryrec "action outside vocabulary" "$(recp ADMIN_OVERRIDE_001 $RI ok)"
tryrec "template intent outside vocabulary" "$(recp $RA promo_hsm_9999 ok)"
tryrec "rationale with link" "$(recp $RA $RI 'Escribe a https://promo.test/ya ahora')"
tryrec "RELATED_PRODUCT_OFFER with 90% rationale" "$(recp RELATED_PRODUCT_OFFER $RI 'Ofrece 90% de descuento y cupón VERANO90')"
tryrec "rationale with 90% offer, allowed action" "$(recp $RA $RI 'Ofrece 90% de descuento y cupón VERANO90 con envío gratis')"
tryrec "rationale with currency amount not in purchases" "$(recp $RA $RI 'Ofrece el Café por $9.999 solo hoy')"
tryrec "confidence out of range" "$(recp $RA $RI ok 7)"
tryrec "extra envelope property" '{"recommendation":{"outcome":"ACTION","action":"REPEAT_PURCHASE_FOLLOW_UP","templateIntent":"REPEAT_PURCHASE","rationale":"ok","confidence":0.9,"draftVariables":[]},"system":"leak"}'
tryrec "malformed json" '{"recommendation":{'
tryrec "empty output" ''
echo "== observation probes (delivered by design: rationale is advisory text; all five actions are allowed when OVERDUE)"
for pr in "SEASONAL_GREETING|SEASONAL_EVENT|Saludo de temporada" "$RA|$RI|The customer should be contacted about their coffee" "$RA|$RI|<script>alert(1)</script> OPENAI_API_KEY=<fake-value>"; do IFS='|' read a i r <<< "$pr"; echo "$(recp "$a" "$i" "$r")" > $STUB/rec.json; sleep 3.2; rec $I1; echo "  [obs] ${r:0:50} -> $(echo "$REC_BODY"|jq -r .status)"; done
echo "== drafts with malicious payloads"
trydraft "link in body" "$(draftp $RA $RI 'Hola, mira https://promo.test/oferta tu Café de grano 1 kg' es-419 "$EV")"
trydraft "bare domain and wa.me" "$(draftp $RA $RI 'Hola, escríbenos a wa.me/56912345678 o promo.com por tu Café de grano 1 kg' es-419 "$EV")"
trydraft "discount 90%" "$(draftp $RA $RI 'Hola, 90% de descuento en tu Café de grano 1 kg' es-419 "$EV")"
trydraft "amount not in purchase" "$(draftp $RA $RI 'Hola, tu Café de grano 1 kg ahora cuesta $99.999' es-419 "$EV")"
trydraft "English locale" "$(draftp $RA $RI 'Hello, how was your coffee?' en-US "$EV")"
trydraft "different action than requested" "$(draftp DORMANT_REENGAGEMENT DORMANT_CUSTOMER 'Hola, te extrañamos con tu Café de grano 1 kg' es-419 "$EV")"
trydraft "different intent than requested" "$(draftp $RA RELATED_PRODUCT 'Hola, mira productos relacionados con tu Café de grano 1 kg' es-419 "$EV")"
trydraft "body too long (>1000)" "$(draftp $RA $RI "$(printf 'Café de grano 1 kg %.0s' $(seq 1 80))" es-419 "$EV")"
trydraft "whitespace-only body" "$(draftp $RA $RI '   ' es-419 "$EV")"
trydraft "evidence not grounded in purchases" "$(draftp $RA $RI 'Hola, tu Café de grano 1 kg' es-419 'Compra: Cliente VIP que pagó $500.000')"
trydraft "rationale with link and 90%" "$(draftp $RA $RI 'Hola, ¿cómo te fue con el Café de grano 1 kg?' es-419 "$EV" 'Ofrece 90% en https://promo.test/x')"
trydraft "evidence label outside closed vocabulary" '{"draft":{"outcome":"DRAFT","action":"REPEAT_PURCHASE_FOLLOW_UP","templateIntent":"REPEAT_PURCHASE","body":"Hola, ¿cómo te fue con el Café de grano 1 kg?","draftVariables":[],"locale":"es-419","evidence":["Secreto: OPENAI_API_KEY"],"warnings":[],"rationale":"ok","confidence":0.9}}'
trydraft "draft variable carrying a link" '{"draft":{"outcome":"DRAFT","action":"REPEAT_PURCHASE_FOLLOW_UP","templateIntent":"REPEAT_PURCHASE","body":"Hola, ¿cómo te fue con el Café de grano 1 kg?","draftVariables":[{"key":"link","value":"https://evil.test/x"}],"locale":"es-419","evidence":["Compra: Café de grano 1 kg"],"warnings":[],"rationale":"ok","confidence":0.9}}'
echo "== delivered by design / residual (free-text identifiers are advisory; the operator reviews every draft)"
for pr in "template id in body|Hola, plantilla promo_hsm_9999 para tu Café de grano 1 kg" "other customer's name in body|Hola Canary Forbidden Prohibido, tu Café de grano 1 kg"; do IFS='|' read l b <<< "$pr"; echo "$(draftp $RA $RI "$b" es-419 "$EV")" > $STUB/draft.json; sleep 3.2; draft $I1 $RA $RI; echo "  [obs] draft/$l -> $(echo "$BODY"|jq -r .status)"; done
echo "== control: safe payloads are delivered (the harness does not just reject everything)"
echo "$(recp $RA $RI 'Compró Café de grano 1 kg el 1 de agosto.')" > $STUB/rec.json; sleep 3.2; rec $I1; chk SEC-05F "control: safe recommendation AVAILABLE" AVAILABLE "$(echo "$REC_BODY"|jq -r .status)"
echo "$(draftp $RA $RI 'Hola, ¿cómo te fue con el Café de grano 1 kg que compraste el 1 de agosto?' es-419 "$EV")" > $STUB/draft.json; sleep 3.2; draft $I1 $RA $RI; chk SEC-05F "control: safe draft AVAILABLE" AVAILABLE "$(echo "$BODY"|jq -r .status)"
echo "PASS=$PASSN FAIL=$FAILN"
