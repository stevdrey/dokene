#!/usr/bin/env bash
# ABOUTME: Suite #340 REG-01 addendum for #421 (stated provisioning policy, merged after suites #333-#337): with the dev
# ABOUTME: policy allow-all-identities, a signed in identity provisions one workspace, a replay is idempotent, and the
# ABOUTME: creator becomes OWNER of only that workspace. Creates one disposable synthetic workspace.
set -uo pipefail
. "$(dirname "$0")/qa340.sh"
C=REG-01
qa_login testnone || exit 1
rec $C "#421 backend provisioning policy" NOTE "$(docker compose -f "$WT/compose.yaml" exec -T backend sh -c 'echo enabled=$DOKENE_PROVISIONING_ENABLED allow_all=$DOKENE_PROVISIONING_ALLOW_ALL_IDENTITIES allowlist_set=$([ -n "$DOKENE_PROVISIONING_ALLOWED_IDENTITIES" ] && echo y || echo n)')"
K=qa340-prov-$(date +%s)
req testnone - POST /api/tenants '{"displayName":"QA340 Taller Provisionado"}' -H "Idempotency-Key: $K"
chk $C "#421 identity without workspaces provisions one (allow-all policy) -> 201" 201 "$CODE"; T=$(jb .tenantId)
req testnone - POST /api/tenants '{"displayName":"QA340 Taller Provisionado"}' -H "Idempotency-Key: $K"
chk $C "#421 replay with the same key returns the same workspace" "$T" "$(jb .tenantId)" "http=$CODE"
req testnone - GET /api/tenants -
chk $C "#421 creator is OWNER of exactly the new workspace" "1 OWNER $T" "$(jb '[length, .[0].role, .[0].tenantId]|join(" ")')"
req testnone "$A" GET /api/customers -; chk $C "#421 new owner still has no access to workspace A" 403 "$CODE"
qa_finish
