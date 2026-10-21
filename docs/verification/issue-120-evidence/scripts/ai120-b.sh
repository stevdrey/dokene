#!/usr/bin/env bash
# QA suite #120, API level, LIVE OpenAI (DOKENE_AI_PROVIDER=openai): AI-02, AI-03, AI-05, AI-06, AI-07, AI-09, AI-10 (live part).
# Synthetic data only. Run from the repository root with the stack up. Writes AI samples to $OUT (sanitized text only).
set -a; . ./.env; set +a; export QA_TMP=$(mktemp -d); . "$(dirname "$0")/qa.sh"
OUT=${OUT:-$QA_TMP/ai-samples.jsonl}; : > "$OUT"
for u in testuser testoperator testviewer; do qa_login $u; done
TENANT=$(curl -s -b $QA_TMP/jar-testoperator.txt $BFF/api/tenants | jq -r '.[0].tenantId')
ai_rows() { psqlq "select count(*) from dokene.audit_events where event_type='AI_INVOCATION_OUTCOME'"; }
ai_last() { psqlq "select ai_operation||'/'||ai_outcome||'/'||ai_detail from dokene.audit_events where event_type='AI_INVOCATION_OUTCOME' order by occurred_at desc limit 1"; }
snap() { # customer state snapshot (no AI data)
  getj testoperator /api/customers/$1 | jq -c '{v:.version,st:.status}'; getj testoperator /api/customers/$1/contact-policy | jq -c '{cp:.version,dnc:.doNotContact,c:[.consents[].status]}'
  getj testoperator /api/customers/$1/follow-up-policy | jq -c .; getj testoperator /api/customers/$1/purchases | jq -c '[.purchases[]|{status,version}]'
  getj testoperator /api/follow-up-queue | jq -c "[.items[]|select(.customerId==\"$1\")|{status,dueDate,policyVersion}]"; }
mk() { # name phone notes -> id (consent granted unless NOCONSENT)
  local notes=${3:-}; api testoperator POST /api/customers "$(jq -nc --arg n "$1" --arg p "$2" --arg no "$notes" '{displayName:$n,notes:(if $no=="" then null else $no end),phones:[{number:$p,region:"CL",primary:true}]}')"; echo "$BODY"|jq -r .id; }
consent() { local pid=$(getj testoperator /api/customers/$1 | jq -r '.phones[0].id') v=$(getj testoperator /api/customers/$1/contact-policy | jq .version); api testoperator PUT /api/customers/$1/contacts/$pid/consents/WHATSAPP "{\"status\":\"$2\",\"source\":\"CUSTOMER_VERBAL\"}" -H "If-Match: \"$v\""; }
buy() { api testoperator POST /api/customers/$1/purchases "{\"purchasedAt\":\"$2\",\"description\":\"$3\"}" -H "Idempotency-Key: ai120-$RUN-$RANDOM"; }
fpv() { api testoperator GET /api/customers/$1/follow-up-policy ''; etag; }
rec() { api testoperator POST /api/customers/$1/recommendation '' -H "If-Match: \"$(fpv $1)\""; REC_HDRS="$HDRS"; REC_BODY="$BODY"; REC_CODE=$CODE; }
draft() { # id action intent
  local v=$(fpv $1); api testoperator POST /api/customers/$1/draft "{\"action\":\"$2\",\"templateIntent\":\"$3\"}" -H "If-Match: \"$v\""; }
is_es() { local t=" $(echo "$1" | tr 'A-ZÁÉÍÓÚÑ' 'a-záéíóúñ') "; local es=0 en=0 w; for w in " de " " que " " el " " la " " para " " con " " tu " " en " " te " " los " " un " " una " " compraste " " como "; do [[ "$t" == *"$w"* ]] && es=$((es+1)); done; for w in " the " " your " " and " " for " " with " " you " " is " " was " " this "; do [[ "$t" == *"$w"* ]] && en=$((en+1)); done; [ $es -ge 3 ] && [ $en -le 1 ] && echo es || echo "notes-es$es-en$en"; }
forbidden() { # text purchases_text -> list of violations (empty = none)
  local t="$1" ok="$2" v=""
  echo "$t" | grep -Eiq 'https?://|www\.|\.(com|cl|net|org)\b|wa\.me|bit\.ly' && v="$v link"
  echo "$t" | grep -Eiq '[0-9]+ ?%|por ciento|descuento|promoci[oó]n|oferta especial|2x1|gratis|cup[oó]n' && v="$v offer"
  for a in $(echo "$t" | grep -Eo '(\$ ?[0-9][0-9.,]*|[0-9]{1,3}(\.[0-9]{3})+|CLP ?[0-9.,]+|USD ?[0-9.,]+)'); do echo "$ok" | grep -qF "$a" || v="$v amount:$a"; done
  echo "$t" | grep -Eiq 'hsm|template[_ -]?id|plantilla [a-z0-9_]+_[a-z0-9_]+|[a-z]+_[a-z]+_[0-9]{2,}' && v="$v template-id"
  echo "$v" | sed 's/^ //'; }
sample() { echo "$1" >> "$OUT"; }
declare_vocab_actions="REPEAT_PURCHASE_FOLLOW_UP GENERAL_CHECK_IN RELATED_PRODUCT_OFFER DORMANT_REENGAGEMENT SEASONAL_GREETING"
declare_vocab_intents="GENERAL_FOLLOW_UP REPEAT_PURCHASE RELATED_PRODUCT SEASONAL_EVENT DORMANT_CUSTOMER"
in_set() { [[ " $2 " == *" $1 "* ]]; }

auditsum() { psqlq "select md5(string_agg(event_type||'='||c, ',' order by event_type)) from (select event_type, count(*) c from dokene.audit_events where event_type<>'AI_INVOCATION_OUTCOME' group by event_type) t"; }
echo "== AI-07a: purchase history changed after a live result, then draft"
ST=$(mk "Stale QA" "$(rnd_phone)"); consent $ST GRANTED >/dev/null; buy $ST 2026-07-20T14:00:00Z "Café 1 kg" >/dev/null
rec $ST; chk AI-07 "baseline recommendation AVAILABLE" AVAILABLE "$(echo "$REC_BODY"|jq -r .status)"; sa=$(echo "$REC_BODY"|jq -r .recommendation.action); si=$(echo "$REC_BODY"|jq -r .recommendation.templateIntent); V0=$(fpv $ST)
buy $ST 2026-08-30T14:00:00Z "Molino manual (compra nueva registrada por otro operador)" >/dev/null
api testoperator POST /api/customers/$ST/draft "{\"action\":\"$sa\",\"templateIntent\":\"$si\"}" -H "If-Match: \"$V0\""
chk AI-07 "same policy version: purchases do not bump the follow-up policy version" "$V0" "$(fpv $ST)"
chk AI-07 "draft after the purchase change is built from a FRESH evaluation (lastPurchaseAt = the new purchase)" "2026-08-30" "$(echo "$BODY"|jq -r '.evaluation.lastPurchaseAt'|cut -c1-10)"
echo "INFO  AI-07  draft after purchase change: status=$(echo "$BODY"|jq -r .status); evidence=$(echo "$BODY"|jq -c '.draft.evidence')"
echo "== AI-07b: change while the live call is IN FLIGHT (race), 4 attempts with different delays"
for d in 1 2 3.5 5; do
  T=$(mk "Race QA $d" "$(rnd_phone)"); consent $T GRANTED >/dev/null; buy $T 2026-07-20T14:00:00Z "Café 1 kg" >/dev/null; rec $T; ta=$(echo "$REC_BODY"|jq -r .recommendation.action); ti=$(echo "$REC_BODY"|jq -r .recommendation.templateIntent); v=$(fpv $T)
  tok=$(curl -s -b $QA_TMP/jar-testoperator.txt -c $QA_TMP/jar-testoperator.txt $BFF/api/session | jq -r .csrfToken)
  ( s=$(date +%s.%N); curl -s -b $QA_TMP/jar-testoperator.txt -X POST $BFF/api/customers/$T/draft -H "X-Tenant-Id: $TENANT" -H "X-CSRF-TOKEN: $tok" -H 'Content-Type: application/json' -H "If-Match: \"$v\"" -d "{\"action\":\"$ta\",\"templateIntent\":\"$ti\"}" > $QA_TMP/race-$d.json; e=$(date +%s.%N); echo "$(echo "$e - $s" | bc)" > $QA_TMP/race-$d.time ) &
  sleep $d; buy $T 2026-08-31T14:00:00Z "Compra durante la llamada" >/dev/null; wait
  echo "INFO  AI-07  race delay ${d}s: call took $(cat $QA_TMP/race-$d.time)s -> status=$(jq -r .status $QA_TMP/race-$d.json) rejection=$(jq -r '.rejectionReason' $QA_TMP/race-$d.json) lastPurchaseAtInEval=$(jq -r '.evaluation.lastPurchaseAt' $QA_TMP/race-$d.json | cut -c1-10)"
done
echo "== AI-07c: consent revoked while the live call is in flight"
T=$(mk "Race Consent QA" "$(rnd_phone)"); consent $T GRANTED >/dev/null; buy $T 2026-07-20T14:00:00Z "Café 1 kg" >/dev/null; rec $T; ta=$(echo "$REC_BODY"|jq -r .recommendation.action); ti=$(echo "$REC_BODY"|jq -r .recommendation.templateIntent); v=$(fpv $T)
tok=$(curl -s -b $QA_TMP/jar-testoperator.txt -c $QA_TMP/jar-testoperator.txt $BFF/api/session | jq -r .csrfToken)
( curl -s -b $QA_TMP/jar-testoperator.txt -X POST $BFF/api/customers/$T/draft -H "X-Tenant-Id: $TENANT" -H "X-CSRF-TOKEN: $tok" -H 'Content-Type: application/json' -H "If-Match: \"$v\"" -d "{\"action\":\"$ta\",\"templateIntent\":\"$ti\"}" > $QA_TMP/race-consent.json ) &
sleep 2; consent $T REVOKED >/dev/null; wait
echo "INFO  AI-07  consent revoked during the call: status=$(jq -r .status $QA_TMP/race-consent.json) rejection=$(jq -r '.rejectionReason' $QA_TMP/race-consent.json) draftPresent=$(jq -r '(.draft!=null)' $QA_TMP/race-consent.json)"
chk AI-07 "consent revoked in flight: the draft is never delivered" "false" "$(jq -r '(.draft!=null)' $QA_TMP/race-consent.json)"
echo "== AI-09 no business side effects and no send/approve route"
SE=$(mk "SideEffects QA" "$(rnd_phone)" "nota interna"); consent $SE GRANTED >/dev/null; buy $SE 2026-08-03T14:00:00Z "Café 1 kg" >/dev/null
B1=$(snap $SE); a0=$(auditsum); n0=$(ai_rows)
rec $SE; ra=$(echo "$REC_BODY"|jq -r .recommendation.action); ri=$(echo "$REC_BODY"|jq -r .recommendation.templateIntent); draft $SE "$ra" "$ri"; draft $SE "$ra" "$ri"; rec $SE
B2=$(snap $SE); a1=$(auditsum); n1=$(ai_rows)
chk AI-09 "audit checksum is non-empty (query works)" ok "$([ -n "$a0" ] && echo ok)"
chk AI-09 "business state identical after 2 recommendations + 2 drafts" "$B1" "$B2"
chk AI-09 "no non-AI audit events were produced by the AI calls" "$a0" "$a1"
chk AI-09 "exactly 4 invocation audit rows (observability only)" 4 "$((n1-n0))"
for p in "POST /api/customers/$SE/send" "POST /api/customers/$SE/messages" "POST /api/customers/$SE/draft/approve" "POST /api/messages" "POST /api/customers/$SE/draft/send"; do m=${p%% *}; path=${p#* }; api testoperator $m $path '{}'; chk AI-09 "no send/approve route: $p" "404|405" "$([ "$CODE" = 404 ] || [ "$CODE" = 405 ] && echo '404|405' || echo $CODE)"; done
echo; echo "AI calls (audit rows) so far: $(ai_rows)"; echo "SUMMARY pass=$PASSN fail=$FAILN"
