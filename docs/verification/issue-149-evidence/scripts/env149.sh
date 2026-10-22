#!/usr/bin/env bash
# ABOUTME: Common prelude for the suite #149 scripts: loads .env, the helpers, logs in the seven actors and reads
# ABOUTME: the tenant and identity ids that fixtures149.sh wrote. Source it from the repository root with the stack up.

set -a; . ./.env; set +a
QA149_DIR=${QA149_DIR:-docs/verification/issue-149-evidence}
export OUTD=${OUTD:-$QA149_DIR/out}
export STUBDIR=${STUBDIR:-$QA149_DIR/stub}
export QA_TMP=${QA_TMP:-$(mktemp -d)}
export PROXY=${PROXY:-http://localhost:5173}
. "$(dirname "${BASH_SOURCE[0]}")/qa149.sh"

# Fixture actors (phase-3-security-cases.md "Tenants and actors"). The four extra Keycloak users are created by
# fixtures149.sh through the admin API; the first three come from the standard seed.
A_OWNER=testuser; A_ADMIN=testadmin; A_OPERATOR=testoperator; A_VIEWER=testviewer
B_OWNER=testowner2; B_OPERATOR=testoperator2; X_NONE=testnone
ALL_USERS="$A_OWNER $A_ADMIN $A_OPERATOR $A_VIEWER $B_OWNER $B_OPERATOR $X_NONE"
for u in $ALL_USERS; do qa_login "$u"; done

if [ -f "$OUTD/ids" ]; then
  # A B: tenant ids. ID_*: identity ids. PN_A PN_B WABA_A WABA_B: Meta ids used by IA and IB.
  . "$OUTD/ids"
fi
PN_A=${PN_A:-100000000000001}; PN_B=${PN_B:-100000000000002}
WABA_A=${WABA_A:-200000000000001}; WABA_B=${WABA_B:-200000000000002}
REF_TOKEN_A=qa149-token-a; REF_TOKEN_B=qa149-token-b; REF_APP_SECRET=qa149-app-secret; REF_VERIFY=qa149-verify
CANARY_TOKEN=CANARY-TOKEN; CANARY_APP_SECRET=CANARY-APP-SECRET; CANARY_VERIFY=CANARY-VERIFY
TOKEN_B=QA149-TOKEN-B-SYNTHETIC
if [ -n "${A:-}" ] && [ -z "${QA149_NO_ENSURE:-}" ]; then stub_set templates approved; stub_set phones owned; ensure_fixtures; fi
