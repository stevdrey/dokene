#!/usr/bin/env bash
# QA suite #121: SEC-06 amount/percentage/offer bypass probes (FAULT-INJECTED provider bodies via the stub). Purchase has NO price and NO offer text.
cd /home/stevdrey/Projects/Java/dokene-qa-run; . /home/stevdrey/qa121/s/env121.sh; : > $STUB/fail
G6=$(mk "Grounding Sin Precio" "$(rnd_phone)" ""); consent $G6 GRANTED >/dev/null; buy $G6 2026-08-01T14:00:00Z 'Café molido 250 g' >/dev/null
echo "purchase text in DB: $(psqlq "select description from dokene.purchases where customer_id='$G6'")"
draftp() { jq -nc --arg b "$1" '{draft:{outcome:"DRAFT",action:"REPEAT_PURCHASE_FOLLOW_UP",templateIntent:"REPEAT_PURCHASE",body:$b,draftVariables:[],locale:"es-419",evidence:["Compra: Café molido 250 g"],warnings:[],rationale:"Reposición.",confidence:0.9}}'; }
t() { sleep 3.2; echo "$(draftp "$2")" > $STUB/draft.json; draft $G6 REPEAT_PURCHASE_FOLLOW_UP REPEAT_PURCHASE; local st=$(echo "$BODY"|jq -r .status); local got=REJECT; [ "$st" = AVAILABLE ] && got=ACCEPT; chk SEC-06 "$1" REJECT "$got"; }
t "CONTROL (no claim; ACCEPT is the correct result, so this line is expected to FAIL)" 'Hola, ¿cómo te fue con tu Café molido 250 g?'
t "ASCII \$10" 'Hola, tu Café molido 250 g por $10.'
t "fullwidth currency and digits ＄１０" 'Hola, tu Café molido 250 g por ＄１０.'
t "ASCII dollar + fullwidth digits \$１０" 'Hola, tu Café molido 250 g por $１０.'
t "fullwidth digits with CLP: CLP １０００" 'Hola, tu Café molido 250 g por CLP １０００.'
t "yen sign ¥1000" 'Hola, tu Café molido 250 g por ¥1000.'
t "US\$10" 'Hola, tu Café molido 250 g por US$10.'
t "10\$ suffix" 'Hola, tu Café molido 250 g por 10$.'
t "10 dólares" 'Hola, tu Café molido 250 g por 10 dólares.'
t "diez mil (number words, no marker)" 'Hola, tu Café molido 250 g por diez mil.'
t "diez mil pesos" 'Hola, tu Café molido 250 g por diez mil pesos.'
t "10 por ciento" 'Hola, tu Café molido 250 g con 10 por ciento menos.'
t "10 pct" 'Hola, tu Café molido 250 g con 10 pct menos.'
t "fullwidth percent 10％" 'Hola, tu Café molido 250 g con 10％ menos.'
t "fullwidth digits percent １０%" 'Hola, tu Café molido 250 g con １０% menos.'
t "half price: mitad de precio" 'Hola, tu Café molido 250 g a mitad de precio.'
t "mitad (without 'precio')" 'Hola, tu Café molido 250 g a la mitad.'
t "lucas slang: 10 lucas" 'Hola, tu Café molido 250 g por 10 lucas.'
t "zero-width split of descuento" "Hola, tu Café molido 250 g con des$(printf '​')cuento."
t "zero-width split of gratis" "Hola, tu Café molido 250 g gra$(printf '​')tis."
t "homoglyph Cyrillic o in oferta" "Hola, tu Café molido 250 g en $(printf 'о')ferta."
t "leet d3scuento" 'Hola, tu Café molido 250 g con d3scuento.'
t "english discount" 'Hola, tu Café molido 250 g con discount.'
t "english free shipping" 'Hola, tu Café molido 250 g con free shipping.'
t "url without scheme: promo.test/x" 'Hola, tu Café molido 250 g en promo.test/x.'
t "obfuscated url: promo[.]test" 'Hola, tu Café molido 250 g en promo[.]test.'
t "wa.me link" 'Hola, tu Café molido 250 g en wa.me/56911112222.'
echo "PASS=$PASSN FAIL=$FAILN  (PASS = unsafe claim rejected; FAIL = accepted claim, plus the expected control FAIL)"
