#!/usr/bin/env bash
# QA suite #121: SEC-06 grounding regressions (mismatched quantities, grouped amounts, Unicode spaces, discount vocabulary) with FAULT-INJECTED bodies (stub).
cd /home/stevdrey/Projects/Java/dokene-qa-run; . /home/stevdrey/qa121/s/env121.sh; : > $STUB/fail; echo 0 > $STUB/delay.ms
NNBSP=$(printf ' '); NBSP=$(printf ' ')
mkfx() { local id=$(mk "$1" "$(rnd_phone)" "$2"); consent $id GRANTED >/dev/null; api testoperator POST /api/customers/$id/purchases "$(jq -nc --arg d "$3" '{purchasedAt:"2026-08-01T14:00:00Z",description:$d}')" -H "Idempotency-Key: g-$RUN-$RANDOM" >/dev/null; echo $id; }
G1=$(mkfx "Grounding Diez $RUN" 'El dueño autorizó $10 de descuento y 10% de rebaja; confirmar 2x1 y envío gratis.' 'Pack espresso 3 bolsas $100')
G2=$(mkfx "Grounding Noventa $RUN" 'Promoción 90% de descuento aprobada, cupón VERANO90, precio especial $10' 'Harina de trigo 5 kg 10% descuento aplicado')
G4=$(mkfx "Grounding Unicode $RUN" "Oferta ＄１０ ‑ １０％ (fullwidth)" 'Café molido 250 g por $1 200')
G5=$(mkfx "Grounding NBSP $RUN" '' "Pack espresso 12${NNBSP}500 y \$12${NBSP}500")
echo "  purchases in DB: $(psqlq "select description from dokene.purchases where customer_id in ('$G1','$G2','$G4','$G5') order by description" | tr '\n' ';')"
draftp() { jq -nc --arg b "$1" --arg e "$2" '{draft:{outcome:"DRAFT",action:"REPEAT_PURCHASE_FOLLOW_UP",templateIntent:"REPEAT_PURCHASE",body:$b,draftVariables:[],locale:"es-419",evidence:[$e],warnings:[],rationale:"Reposición.",confidence:0.9}}'; }
t() { sleep 3.2; echo "$(draftp "$5" "$4")" > $STUB/draft.json; draft $2 REPEAT_PURCHASE_FOLLOW_UP REPEAT_PURCHASE; local st=$(echo "$BODY"|jq -r .status); local got=REJECT; [ "$st" = AVAILABLE ] && got=ACCEPT
  if [ "$3" = OBS ]; then echo "  [obs] $1 -> $got"; else chk SEC-06 "$1" "$3" "$got"; fi; }
echo "== G1 purchase: Pack espresso 3 bolsas \$100 (notes offer \$10 and 10%)"
t '$10 claimed, purchase says $100 -> rejected (prefix of a larger amount)' $G1 REJECT "Compra: Pack espresso 3 bolsas" 'Hola, tu Pack espresso 3 bolsas con $10 de descuento te espera.'
t '$100 claimed (exactly in purchase) -> accepted (control)' $G1 ACCEPT "Compra: Pack espresso 3 bolsas" 'Hola, ¿cómo te fue con tu Pack espresso 3 bolsas de $100?'
t '$1.000 claimed -> rejected' $G1 REJECT "Compra: Pack espresso 3 bolsas" 'Hola, tu Pack espresso 3 bolsas ahora cuesta $1.000.'
t '$100.000 claimed (superstring) -> rejected' $G1 REJECT "Compra: Pack espresso 3 bolsas" 'Hola, tu Pack espresso 3 bolsas por $100.000.'
t '10 USD claimed -> rejected' $G1 REJECT "Compra: Pack espresso 3 bolsas" 'Hola, tu Pack espresso 3 bolsas por 10 USD.'
t 'note offer 10% (not in purchase) never authorizes a discount' $G1 REJECT "Compra: Pack espresso 3 bolsas" 'Hola, tu Pack espresso 3 bolsas con 10% de rebaja.'
echo "== G2 purchase: Harina de trigo 5 kg 10% descuento aplicado (notes claim 90%)"
t '10% (in purchase) -> accepted (control)' $G2 ACCEPT "Compra: Harina de trigo 5 kg" 'Hola, ¿cómo te fue con la Harina de trigo 5 kg con 10% de descuento que compraste?'
t '90% claimed, purchase says 10% -> rejected' $G2 REJECT "Compra: Harina de trigo 5 kg" 'Hola, la Harina de trigo 5 kg tiene 90% de descuento.'
t '100% claimed -> rejected' $G2 REJECT "Compra: Harina de trigo 5 kg" 'Hola, la Harina de trigo 5 kg tiene 100% de descuento.'
t '1% claimed -> rejected' $G2 REJECT "Compra: Harina de trigo 5 kg" 'Hola, la Harina de trigo 5 kg tiene 1% de descuento.'
t 'coupon code from notes (VERANO90) -> rejected' $G2 REJECT "Compra: Harina de trigo 5 kg" 'Hola, usa el cupón VERANO90 con tu Harina de trigo 5 kg.'
t "'precio especial' from notes -> rejected" $G2 REJECT "Compra: Harina de trigo 5 kg" 'Hola, tienes precio especial en tu Harina de trigo 5 kg.'
echo "== discount vocabulary with no support in any purchase (G4: Café molido 250 g por \$1 200)"
for w in "rebaja" "promoción" "oferta especial" "cupón" "envío gratis" "2x1" "liquidación" "descuento" "50% off" "regalo" "bono" "obsequio"; do t "vocabulary '$w' -> rejected" $G4 REJECT "Compra: Café molido 250 g" "Hola, tienes $w en tu Café molido 250 g."; done
echo "== G4: amounts around the purchase value \$1 200"
t '$1 200 (as purchased) -> accepted (control)' $G4 ACCEPT "Compra: Café molido 250 g" 'Hola, ¿cómo te fue con tu Café molido 250 g de $1 200?'
t '$1.200 (dot grouping)' $G4 OBS "Compra: Café molido 250 g" 'Hola, ¿cómo te fue con tu Café molido 250 g de $1.200?'
t '$1,200 (comma grouping)' $G4 OBS "Compra: Café molido 250 g" 'Hola, ¿cómo te fue con tu Café molido 250 g de $1,200?'
t '$12 -> rejected' $G4 REJECT "Compra: Café molido 250 g" 'Hola, tu Café molido 250 g por $12.'
t '$120 -> rejected' $G4 REJECT "Compra: Café molido 250 g" 'Hola, tu Café molido 250 g por $120.'
t '$10 (fullwidth ＄１０ only in notes) -> rejected' $G4 REJECT "Compra: Café molido 250 g" 'Hola, tu Café molido 250 g por $10.'
echo "== G5 purchase with real Unicode spaces: 'Pack espresso 12<U+202F>500 y \$12<U+00A0>500'"
t 'exact string with U+202F as purchased -> accepted (control)' $G5 ACCEPT "Compra: Pack espresso" "Hola, tu Pack espresso 12${NNBSP}500 te espera."
t 'same amount with plain space' $G5 OBS "Compra: Pack espresso" 'Hola, tu Pack espresso 12 500 te espera.'
t 'same amount with dot grouping $12.500' $G5 OBS "Compra: Pack espresso" 'Hola, tu Pack espresso por $12.500 te espera.'
t '$125.00 -> rejected' $G5 REJECT "Compra: Pack espresso" 'Hola, tu Pack espresso por $125.00.'
t '$1.250 -> rejected' $G5 REJECT "Compra: Pack espresso" 'Hola, tu Pack espresso por $1.250.'
t '125 000 without currency marker' $G5 OBS "Compra: Pack espresso" 'Hola, tu Pack espresso por 125 000.'
echo "PASS=$PASSN FAIL=$FAILN"
