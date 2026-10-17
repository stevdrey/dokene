cd /home/stevdrey/Projects/Java/dokene-qa-run; . /home/stevdrey/qa121/s/env121.sh
mkp() { api testoperator POST /api/customers/$1/purchases "$(jq -nc --arg d "$3" --arg t "$2" '{purchasedAt:$t,description:$d}')" -H "Idempotency-Key: s122-$RUN-$RANDOM"; echo "purchase $CODE"; }
LONG=$(printf 'Wolfeschlegelsteinhausenbergerdorff%.0s' 1 2); UNB=$(printf 'X%.0s' $(seq 1 180))
C1=$(mk "$LONG" "$(rnd_phone)" "Nota larga: $UNB"); consent $C1 GRANTED >/dev/null; mkp $C1 2026-08-01T14:00:00Z "Descripción sin espacios: $UNB"
C2=$(mk "Emoji ☕🇨🇱👨‍👩‍👧‍👦 Ñandú" "$(rnd_phone)" "línea1
línea2
línea3"); consent $C2 GRANTED >/dev/null; mkp $C2 2026-08-01T14:00:00Z "Café ☕ 1 kg 🇨🇱 \"comillas\" <b>negrita</b> & más"
C3=$(mk "Nombre Normal Multilínea $RUN" "$(rnd_phone)" "a
b
c"); consent $C3 GRANTED >/dev/null; mkp $C3 2026-08-01T14:00:00Z "Producto con descripción larga larga larga larga larga larga larga larga larga larga larga larga larga larga larga larga larga larga larga larga larga larga larga larga larga larga larga larga larga larga larga larga larga larga larga larga larga larga larga larga larga larga larga larga larga larga larga larga larga larga larga."
echo "$C1 $C2 $C3" > /home/stevdrey/qa122/out/stress.ids; echo "ids: $C1 $C2 $C3"; getj testoperator /api/customers/$C2 | jq -c '{displayName,notes}'
