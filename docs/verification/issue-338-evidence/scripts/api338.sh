#!/usr/bin/env bash
# ABOUTME: Suite #338: the second actor of the browser recovery cases, over real HTTP through the BFF (cookie, CSRF,
# ABOUTME: tenant header): a parallel approval, membership revoke/reactivation, a recommendation burst. Prints codes only.
#   api338.sh approve <user> <message>     approve the current revision as <user> (tenant A)
#   api338.sh revoke <user> | reactivate <user>   OWNER testuser changes <user>'s tenant A membership
#   api338.sh recburst <customer> <n>      <n> recommendation requests as testuser (tenant A), prints the status codes
set -uo pipefail
. "$(dirname "$0")/qa338.sh" >/dev/null 2>&1
case ${1:-} in
  approve)
    qa_login "$2" || exit 1
    req "$2" "$A" GET "/api/messages/$3" -; rev=$(jb .template.revision); v=$(etag)
    req "$2" "$A" POST "/api/messages/$3/approvals" "$(jq -nc --arg r "$rev" '{decision:"APPROVE",templateRevision:$r}')" -H "If-Match: $v" -H "Idempotency-Key: $(uuidgen)"
    echo "approve $CODE $(jb .status)" ;;
  revoke|reactivate)
    qa_login testuser || exit 1
    id=$(qa_login "$2" && identity "$2")
    if [ "$1" = revoke ]; then
      req testuser "$A" DELETE "/api/memberships/$id" -
      echo "revoke $CODE"
    else
      req testuser "$A" POST "/api/memberships/$id/reactivation" -; echo "reactivate $CODE"
      qa_login "$2" || exit 1
      req "$2" - POST "/api/tenants/$A/invitation/acceptance" -; echo "accept $CODE"
    fi ;;
  recburst)
    qa_login testuser || exit 1
    codes=""; for _ in $(seq 1 "$3"); do
      req testuser "$A" GET "/api/customers/$2/follow-up-policy" -; v=$(etag)
      req testuser "$A" POST "/api/customers/$2/recommendation" - -H "If-Match: $v"; codes="$codes $CODE"
    done
    echo "recommendation codes:$codes" ;;
  *) echo "usage: see header" >&2; exit 2 ;;
esac
