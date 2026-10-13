cd /home/stevdrey/Projects/Java/dokene-qa-run; . /home/stevdrey/qa121/s/env121.sh
mkp() { api testoperator POST /api/customers/$1/purchases "$(jq -nc --arg d "$3" --arg t "$2" '{purchasedAt:$t,description:$d}')" -H "Idempotency-Key: e122-$RUN-$RANDOM" >/dev/null; }
V1=$(mk "Emoji ☕🇨🇱👨‍👩‍👧‍👦 Ñandú V1$RUN" "$(rnd_phone)" ""); consent $V1 GRANTED >/dev/null; mkp $V1 2026-08-01T14:00:00Z "Café 1 kg"
V2=$(mk "Normal Dos V2$RUN" "$(rnd_phone)" ""); consent $V2 GRANTED >/dev/null; mkp $V2 2026-08-01T14:00:00Z "Café ☕ 1 kg 🇨🇱"
V3=$(mk "Normal Tres V3$RUN" "$(rnd_phone)" ""); consent $V3 GRANTED >/dev/null; mkp $V3 2026-08-01T14:00:00Z 'Café "comillas" <b>negrita</b> & más'
V4=$(mk "Ñandú Cuatro V4$RUN" "$(rnd_phone)" ""); consent $V4 GRANTED >/dev/null; mkp $V4 2026-08-01T14:00:00Z "Café 1 kg"
for v in V1 V2 V3 V4; do id=${!v}; for i in 1 2 3; do sleep 3.5; draft $id REPEAT_PURCHASE_FOLLOW_UP REPEAT_PURCHASE; echo "$v draft#$i: $(echo "$BODY"|jq -c '{status,u:.unavailableReason}')"; done; done
