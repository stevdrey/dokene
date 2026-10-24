#!/usr/bin/env bash
# Driver: UI/UX re-execution (suite #122 scripts) on main acb7856. Stub phase first (ux06b, ux07), then live phase.
export PATH=$HOME/.nvm/versions/node/v24.21.0/bin:$PATH
cd /home/stevdrey/Projects/Java/dokene-qa-run; unset COMPOSE_FILE
P=/home/stevdrey/qa-final/out/progress-ui.txt; : > $P
restart() { docker rm -f qa-stub >/dev/null 2>&1; ./scripts/dev-env.sh restart-backend --no-build --yes >/dev/null 2>&1; }
runjs() { ( cd /home/stevdrey/qa122; timeout 900 node $1.mjs > /home/stevdrey/qa-final/out/ui-$1.txt 2>&1; echo "exit=$?" >> /home/stevdrey/qa-final/out/ui-$1.txt ); echo "$1 $(grep -ciE '^(PASS|ok)' /home/stevdrey/qa-final/out/ui-$1.txt) pass / $(grep -ciE '^(FAIL)' /home/stevdrey/qa-final/out/ui-$1.txt) fail; $(tail -1 /home/stevdrey/qa-final/out/ui-$1.txt)" >> $P; ps -eo pid,args | awk '/[h]eadless/{print $1}' | xargs -r kill 2>/dev/null; sleep 2; }
export DOKENE_AI_OPENAI_BASE_URL=http://127.0.0.1:18080/v1; restart
docker rm -f qa-stub >/dev/null 2>&1; docker run -d --name qa-stub --network container:dokene-qa-run-oidc-bridge-1 -v /home/stevdrey/qa121/stub:/stub --entrypoint java dokene-backend:local -cp /stub Stub >/dev/null; sleep 3
for s in ux06b ux07; do runjs $s; done
docker rm -f qa-stub >/dev/null 2>&1; unset DOKENE_AI_OPENAI_BASE_URL; export DOKENE_AI_OPENAI_BASE_URL=; restart
echo "backend back on the real OpenAI provider" >> $P
for s in ux01 ux01b ux02 ux03 ux04 ux05 ux06 ux08 ux09 ux10_11 ux11d; do runjs $s; done
echo ALLDONE >> $P
