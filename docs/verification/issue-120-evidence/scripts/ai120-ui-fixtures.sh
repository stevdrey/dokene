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

# second workspace + membership for the isolation test
api testuser POST /api/tenants '{"displayName":"QA Café Sur","idempotencyKey":"qa-seed-cafe-sur"}' -H "Idempotency-Key: qa-seed-cafe-sur"; SUR=$(echo "$BODY"|jq -r .tenantId)
OPID=$(curl -s -b $QA_TMP/jar-testoperator.txt $BFF/api/session | jq -r .identityId)
NORTE=$TENANT; TENANT=$SUR; api testuser POST /api/memberships "{\"identityId\":\"$OPID\",\"role\":\"OPERATOR\"}"; echo "membership in Sur: $CODE"
mkS() { api testuser POST /api/customers "{\"displayName\":\"$1\",\"phones\":[{\"number\":\"$(rnd_phone)\",\"region\":\"CL\",\"primary\":true}]}"; local id=$(echo "$BODY"|jq -r .id) pid=$(echo "$BODY"|jq -r '.phones[0].id'); api testuser PUT /api/customers/$id/contacts/$pid/consents/WHATSAPP '{"status":"GRANTED","source":"CUSTOMER_VERBAL"}' -H 'If-Match: "0"' >/dev/null; api testuser POST /api/customers/$id/purchases '{"purchasedAt":"2026-08-04T14:00:00Z","description":"Aceite de oliva 1 L"}' -H "Idempotency-Key: sur-$RUN" >/dev/null; echo $id; }
mkS "UI Sur QA" > $QA_TMP/sur-id; TENANT=$NORTE
for n in "UI Iso A QA" "UI Iso B QA" "UI Stale QA" "UI Race QA" "UI Edit QA" "UI Inelig QA"; do id=$(mk "$n" "$(rnd_phone)"); consent $id GRANTED >/dev/null; buy $id 2026-08-0$((RANDOM%5+1))T14:00:00Z "Café de grano 1 kg" >/dev/null; echo "$n=$id" >> $QA_TMP/ui-ids; done
cat $QA_TMP/ui-ids; echo "SUR=$(cat $QA_TMP/sur-id)"; echo "NORTE=$NORTE SURTENANT=$SUR"; cp $QA_TMP/ui-ids /tmp/ui-ids.txt; echo "SURID=$(cat $QA_TMP/sur-id)" >> /tmp/ui-ids.txt; echo "NORTE=$NORTE" >> /tmp/ui-ids.txt; echo "SURTENANT=$SUR" >> /tmp/ui-ids.txt
