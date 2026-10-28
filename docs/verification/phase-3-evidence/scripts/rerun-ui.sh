#!/usr/bin/env bash
# Issue 140: rerun ux06 (after refreshing the emoji fixture) on live OpenAI, then ux07 on the stub.
export PATH=$HOME/.nvm/versions/node/v24.21.0/bin:$PATH
O=/home/stevdrey/qa140/out; P=$O/progress.txt
cd /home/stevdrey/Projects/Java/dokene-qa-run; unset COMPOSE_FILE
runjs() { ( cd /home/stevdrey/qa122; timeout 900 node $1.mjs > $O/ui-$1-rerun.txt 2>&1; echo "exit=$?" >> $O/ui-$1-rerun.txt ); echo "ui-$1-rerun $(tail -1 $O/ui-$1-rerun.txt) fail-lines=$(grep -ciE '^FAIL' $O/ui-$1-rerun.txt)" >> $P; ps -eo pid,args | awk '/[h]eadless/{print $1}' | xargs -r kill 2>/dev/null; sleep 2; }
( cd /home/stevdrey/qa122; bash fx-emoji.sh > $O/ui-fx-emoji.txt 2>&1; echo "exit=$?" >> $O/ui-fx-emoji.txt ); echo "fx-emoji $(tail -1 $O/ui-fx-emoji.txt)" >> $P
runjs ux06
export DOKENE_AI_OPENAI_BASE_URL=http://127.0.0.1:18080/v1; docker rm -f qa-stub >/dev/null 2>&1; ./scripts/dev-env.sh restart-backend --no-build --yes >/dev/null 2>&1
docker run -d --name qa-stub --network container:dokene-qa-run-oidc-bridge-1 -v /home/stevdrey/qa121/stub:/stub --entrypoint java dokene-backend:local -cp /stub Stub >/dev/null; sleep 3
runjs ux07
docker rm -f qa-stub >/dev/null 2>&1; export DOKENE_AI_OPENAI_BASE_URL=; ./scripts/dev-env.sh restart-backend --no-build --yes >/dev/null 2>&1
echo RERUNDONE >> $P
