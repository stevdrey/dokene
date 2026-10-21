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

byname() { getj testoperator "/api/customers?name=$(python3 -c 'import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))' "$1")" | jq -r '.customers[0].id'; }
RP=$(byname "Repeat Café QA"); DM=$(byname "Dormant Té QA"); NA=$(byname "Probe NoOffers QA"); RT=$(byname "Probe Return QA")
echo "== draft requests that the data may not support (live); outcomes are recorded as observed"
for c in "$RP RELATED_PRODUCT_OFFER RELATED_PRODUCT" "$RP SEASONAL_GREETING SEASONAL_EVENT" "$RP GENERAL_CHECK_IN GENERAL_FOLLOW_UP" "$RP REPEAT_PURCHASE_FOLLOW_UP DORMANT_CUSTOMER" "$DM REPEAT_PURCHASE_FOLLOW_UP REPEAT_PURCHASE" "$NA REPEAT_PURCHASE_FOLLOW_UP REPEAT_PURCHASE" "$NA RELATED_PRODUCT_OFFER RELATED_PRODUCT" "$RT REPEAT_PURCHASE_FOLLOW_UP REPEAT_PURCHASE"; do set -- $c; draft $1 $2 $3
  echo "DRAFTPROBE  customer=$(getj testoperator /api/customers/$1 | jq -r .displayName) action=$2 intent=$3 -> HTTP $CODE status=$(echo "$BODY"|jq -r .status) reason=$(echo "$BODY"|jq -r '.refusalReason // .rejectionReason // .unavailableReason // empty') body=$(echo "$BODY"|jq -r '.draft.body // .refusal.rationale // empty'|cut -c1-110)"
  sample "$(jq -nc --arg c "draftprobe:$2/$3" --argjson r "$BODY" '{case:$c,kind:"probe-draft",value:($r|{status,refusalReason,rejectionReason,draft,refusal})}')"; done
