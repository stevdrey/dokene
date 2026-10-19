cd /home/stevdrey/Projects/Java/dokene-qa-run; . /home/stevdrey/qa123/s/base.sh
B1=$(mk "Res Baseline $RUN" "$(rnd_phone)" ""); consent $B1 GRANTED >/dev/null; buy $B1 2026-08-01T14:00:00Z "Café de grano 1 kg" >/dev/null
rec $B1; echo "recommendation: http=$REC_CODE status=$(echo "$REC_BODY"|jq -r .status) action=$(echo "$REC_BODY"|jq -r .recommendation.action) audit=$(ai_last)"
sleep 3.2; draft $B1 "$(echo "$REC_BODY"|jq -r .recommendation.action)" "$(echo "$REC_BODY"|jq -r .recommendation.templateIntent)"; echo "draft: http=$CODE status=$(echo "$BODY"|jq -r .status) locale=$(echo "$BODY"|jq -r .draft.locale) audit=$(ai_last)"; echo "$B1" > /home/stevdrey/qa123/out/baseline.id
