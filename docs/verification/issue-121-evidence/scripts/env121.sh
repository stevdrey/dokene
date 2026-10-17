# Common prelude for suite #121 scripts: source from the QA worktree root with the stack up.
set -a; . ./.env; set +a; export QA_TMP=${QA_TMP:-$(mktemp -d)}; . "$(dirname "${BASH_SOURCE[0]}")/qa.sh"; . "$(dirname "${BASH_SOURCE[0]}")/helpers.sh"
STUB=${STUB:-/home/stevdrey/qa121/stub}
for u in testuser testoperator testviewer; do qa_login $u; done
A=$(curl -s -b $QA_TMP/jar-testuser.txt $BFF/api/tenants | jq -r '[.[]|select(.displayName=="QA Café Norte")][0].tenantId // .[0].tenantId'); TENANT=$A
B=$(psqlq "select id from dokene.tenants where display_name like 'SEC121 B %' order by created_at desc limit 1"); C=$(psqlq "select id from dokene.tenants where display_name like 'SEC121 C %' order by created_at desc limit 1")
cid() { psqlq "select id from dokene.customers where display_name='$1' order by created_at desc limit 1"; }
UO=$(curl -s -b $QA_TMP/jar-testoperator.txt $BFF/api/session | jq -r .identityId); UV=$(curl -s -b $QA_TMP/jar-testviewer.txt $BFF/api/session | jq -r .identityId); UU=$(curl -s -b $QA_TMP/jar-testuser.txt $BFF/api/session | jq -r .identityId)
