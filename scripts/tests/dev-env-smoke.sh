#!/usr/bin/env bash
# Smoke tests for scripts/dev-env.sh and the compose topology. Needs bash, python3 and the `docker compose` CLI
# (no running daemon or containers).
#
# Usage: ./scripts/tests/dev-env-smoke.sh
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="$ROOT_DIR/scripts/dev-env.sh"
failures=0

check() {
  # check "description" command...
  local description="$1"; shift
  if "$@" >/dev/null 2>&1; then
    printf '[pass] %s\n' "$description"
  else
    printf '[FAIL] %s\n' "$description" >&2
    failures=$((failures + 1))
  fi
}

help_text="$("$SCRIPT" help 2>&1)"
help_lists_restart_backend() { grep -q 'restart-backend' <<<"$help_text"; }
help_has_no_code_leak() { ! grep -q 'set -uo pipefail' <<<"$help_text"; }
unknown_command_fails() { "$SCRIPT" definitely-not-a-command; [ $? -eq 2 ]; }
restart_backend_rejects_unknown_option() { "$SCRIPT" restart-backend --bogus; [ $? -ne 0 ]; }

check "help lists restart-backend" help_lists_restart_backend
check "help prints only the header comment" help_has_no_code_leak
check "unknown command exits 2" unknown_command_fails
check "restart-backend rejects unknown options" restart_backend_rejects_unknown_option
check "dev-env.sh and entrypoint.sh are valid bash" bash -c "bash -n '$SCRIPT' && bash -n '$ROOT_DIR/infra/docker/backend/entrypoint.sh'"

topology_is_inverted() {
  local env_vars="" v json=""
  for v in DOKENE_DB_NAME DOKENE_DB_BOOTSTRAP_USERNAME DOKENE_DB_BOOTSTRAP_PASSWORD DOKENE_DB_PASSWORD \
           DOKENE_DB_RUNTIME_PASSWORD DOKENE_DB_MIGRATION_PASSWORD DOKENE_TENANT_CONTEXT_SIGNING_KEY \
           KC_BOOTSTRAP_ADMIN_USERNAME KC_BOOTSTRAP_ADMIN_PASSWORD \
           SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_DOKENE_CLIENT_SECRET; do
    env_vars="$env_vars $v=unused"
  done
  json="$(cd "$ROOT_DIR" && env $env_vars docker compose config --format json)" || return 1
  python3 -I -c '
import json, sys
s = json.load(sys.stdin)["services"]
bridge, backend = s["oidc-bridge"], s["backend"]
assert backend["network_mode"] == "service:oidc-bridge", "backend must join the bridge namespace"
assert "network_mode" not in bridge, "bridge must own its namespace"
assert any(p.get("published") == "8080" for p in bridge.get("ports", [])), "bridge must publish 8080"
assert not backend.get("ports"), "backend must not publish ports itself"
assert "backend" in bridge["networks"]["default"]["aliases"], "bridge must carry the backend alias"
' <<<"$json"
}
check "compose: backend joins the oidc-bridge namespace (topology from #155)" topology_is_inverted

if [ "$failures" -gt 0 ]; then
  printf '%s check(s) failed\n' "$failures" >&2
  exit 1
fi
printf 'All checks passed\n'
