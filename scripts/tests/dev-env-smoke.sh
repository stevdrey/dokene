#!/usr/bin/env bash
# ABOUTME: Smoke tests for scripts/dev-env.sh, the Compose model in compose.yaml and ShellCheck on the local scripts.
# ABOUTME: Asserts topology, secret handling and container restrictions without starting the stack.
# Smoke tests for scripts/dev-env.sh and the compose topology. Needs bash, python3 and the `docker compose` CLI
# (no running daemon or containers).
#
# Usage: ./scripts/tests/dev-env-smoke.sh
set -uo pipefail
# Test stubs (docker, have, warn, compose) shadow functions the sourced dev-env.sh calls; ShellCheck cannot see that.
# shellcheck disable=SC2329

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
restart_backend_rejects_unknown_option() { ! "$SCRIPT" restart-backend --bogus; }

check "help lists restart-backend" help_lists_restart_backend
check "help prints only the header comment" help_has_no_code_leak
check "unknown command exits 2" unknown_command_fails
check "restart-backend rejects unknown options" restart_backend_rejects_unknown_option

# Keycloak data is a tmpfs since #319, so `reset` deletes only the PostgreSQL volume.
help_reset_names_only_the_database() {
  local line=""
  line="$(grep 'dev-env.sh reset' <<<"$help_text")" || return 1
  grep -q 'PostgreSQL' <<<"$line" && ! grep -q 'Keycloak' <<<"$line"
}
check "help: reset deletes only the PostgreSQL volume (#319 review)" help_reset_names_only_the_database

# Every Keycloak start imports the realm from the template (#319), so `up` has no existing realm to reconcile.
up_does_not_reconcile_keycloak() {
  # shellcheck source=../dev-env.sh
  source "$SCRIPT" || return 1
  ! declare -f cmd_up | grep -q 'keycloak-apply-'
}
check "up does not run a Keycloak realm reconcile script (#319 review)" \
  bash -c "$(declare -f up_does_not_reconcile_keycloak) ; SCRIPT='$SCRIPT'; up_does_not_reconcile_keycloak"

# Scripts that are ShellCheck-clean today. The scripts/verify-issue-*.sh helpers still carry findings and are not listed.
shellcheck_targets=(
  scripts/dev-env.sh scripts/tests/dev-env-smoke.sh scripts/tests/nginx-headers-smoke.sh scripts/seed-local-qa.sh scripts/verify-keycloak-bff-e2e.sh
  scripts/keycloak-apply-realm-settings.sh scripts/tests/keycloak-apply-realm-settings-test.sh
  scripts/tests/keycloak-realm-config-test.sh scripts/lib/test-passwords.sh
  infra/docker/backend/entrypoint.sh infra/docker/postgres/init/01-create-database-roles.sh
)
shellcheck_min_version=0.11.0   # the annotations target this release (SC2329 does not exist before it)
# True when VERSION ($1) is the minimum or newer; an empty VERSION (not installed) is not.
shellcheck_supported() {
  [ -n "$1" ] && [ "$(printf '%s\n%s\n' "$shellcheck_min_version" "$1" | sort -V | head -n 1)" = "$shellcheck_min_version" ]
}
shellcheck_version="$(shellcheck --version 2>/dev/null | sed -n 's/^version: //p')"
if shellcheck_supported "$shellcheck_version"; then
  if shellcheck_output="$(cd "$ROOT_DIR" && shellcheck -x "${shellcheck_targets[@]}" 2>&1)"; then
    printf '[pass] %s\n' "shell scripts are ShellCheck-clean"
  else
    printf '[FAIL] %s\n%s\n' "shell scripts are ShellCheck-clean" "$shellcheck_output" >&2
    failures=$((failures + 1))
  fi
elif [ "${CI:-false}" = "true" ]; then
  # On a developer machine the lint is optional; in CI a runner image with an older ShellCheck must not drop it quietly.
  printf '[FAIL] ShellCheck >= %s is required in CI (found: %s)\n' "$shellcheck_min_version" "${shellcheck_version:-none}" >&2
  failures=$((failures + 1))
else
  printf '[skip] ShellCheck >= %s not installed\n' "$shellcheck_min_version"
fi
check "dev-env.sh and entrypoint.sh are valid bash" bash -c "bash -n '$SCRIPT' && bash -n '$ROOT_DIR/infra/docker/backend/entrypoint.sh'"

# Render the Compose model with throwaway values for the required variables (no daemon or containers needed).
# Arguments are extra NAME=value pairs. Only the committed compose.yaml is rendered: without -f, Compose would merge a
# git-ignored compose.override.yaml into the model under test.
compose_config_json() {
  local -a env_vars=("$@")
  local v
  for v in DOKENE_DB_NAME DOKENE_DB_BOOTSTRAP_USERNAME DOKENE_DB_BOOTSTRAP_PASSWORD DOKENE_DB_PASSWORD \
           DOKENE_DB_RUNTIME_PASSWORD DOKENE_DB_MIGRATION_PASSWORD DOKENE_TENANT_CONTEXT_SIGNING_KEY \
           KC_BOOTSTRAP_ADMIN_USERNAME KC_BOOTSTRAP_ADMIN_PASSWORD \
           SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_DOKENE_CLIENT_SECRET; do
    env_vars+=("$v=unused")
  done
  (cd "$ROOT_DIR" && env "${env_vars[@]}" docker compose -f compose.yaml config --format json)
}

topology_is_inverted() {
  local json=""
  json="$(compose_config_json)" || return 1
  python3 -I -c '
import json, re, sys
s = json.load(sys.stdin)["services"]
bridge, backend = s["oidc-bridge"], s["backend"]
assert backend["network_mode"] == "service:oidc-bridge", "backend must join the bridge namespace"
assert "network_mode" not in bridge, "bridge must own its namespace"
assert any(p.get("published") == "8080" for p in bridge.get("ports", [])), "bridge must publish 8080"
assert not backend.get("ports"), "backend must not publish ports itself"
assert "backend" in bridge["networks"]["default"]["aliases"], "bridge must carry the backend alias"
assert re.search(r"nslookup\s+oidc-bridge\.(\s|$)", " ".join(bridge["healthcheck"]["test"])), \
    "bridge must health-check name resolution with an absolute name (#171)"
assert backend["depends_on"]["oidc-bridge"]["condition"] == "service_healthy", "backend must wait for a healthy bridge"
' <<<"$json"
}
check "compose: backend joins the oidc-bridge namespace (topology from #155)" topology_is_inverted

migration_credentials_stay_in_migrate() {
  local json=""
  json="$(compose_config_json)" || return 1
  python3 -I -c '
import json, sys
s = json.load(sys.stdin)["services"]
migrate, backend = s["migrate"], s["backend"]
names = lambda svc: [x["source"] for x in svc.get("secrets", [])]
assert not [k for k in backend.get("environment", {}) if "MIGRATION" in k or k.endswith("SIGNING_KEY")], \
    "backend environment must hold no migration credential or signing key"
assert names(backend) == ["DOKENE_TENANT_CONTEXT_SIGNING_KEY"], "backend gets only the signing key secret"
assert sorted(names(migrate)) == ["DOKENE_DB_MIGRATION_PASSWORD", "DOKENE_TENANT_CONTEXT_SIGNING_KEY"]
assert not [k for k in migrate.get("environment", {}) if k.endswith(("PASSWORD", "SIGNING_KEY"))], \
    "migrate reads its secrets from files, not the environment"
assert backend["depends_on"]["migrate"]["condition"] == "service_completed_successfully"
assert migrate["read_only"] and migrate["restart"] == "no"
' <<<"$json"
}
check "compose: only the migrate job holds the migration credential (#308)" migration_credentials_stay_in_migrate

keycloak_gets_one_password_per_test_user() {
  local json="" role
  json="$(compose_config_json)" || return 1
  python3 -I -c '
import json, sys
env = json.load(sys.stdin)["services"]["keycloak"]["environment"]
for role in ("OWNER", "OPERATOR", "VIEWER"):
    assert f"DOKENE_TEST_{role}_PASSWORD" in env, role
assert "DOKENE_TEST_USER_PASSWORD" not in env, "the shared password variable is gone (#314)"
' <<<"$json" || return 1
  # The rendered model carries whatever .env holds, so the empty defaults are checked in the source.
  for role in OWNER OPERATOR VIEWER; do
    grep -qF "DOKENE_TEST_${role}_PASSWORD: \${DOKENE_TEST_${role}_PASSWORD:-}" "$ROOT_DIR/compose.yaml" || return 1
  done
  ! grep -q 'testpassword' "$ROOT_DIR/compose.yaml"
}
check "compose: keycloak gets one password variable per test user, none with a default (#314)" \
  keycloak_gets_one_password_per_test_user

# Runs the rendered keycloak entrypoint outside Docker: Keycloak's paths point into a scratch directory and the final
# `exec kc.sh` is replaced, so only the checks and the realm substitution run.
keycloak_entrypoint_substitutes_each_password() {
  local work rc=1 script
  work="$(mktemp -d)" || return 1
  mkdir -p "$work/template"
  cp "$ROOT_DIR/infra/docker/keycloak/import/dokene-realm.json" "$work/template/"
  script="$(compose_config_json | python3 -I -c 'import json, sys; print(json.load(sys.stdin)["services"]["keycloak"]["entrypoint"][2])')" \
    || { rm -rf "$work"; return 1; }
  # `config` output keeps Compose's `$$` escapes; Compose turns them into `$` only when it runs the container.
  script="${script//\$\$/\$}"
  script="${script//\/opt\/keycloak\/import-template/$work/template}"
  script="${script//\/opt\/keycloak\/data\/import/$work/import}"
  script="${script//exec \/opt\/keycloak\/bin\/kc.sh/exit 0 #}"
  (
    export KC_BOOTSTRAP_ADMIN_USERNAME=admin KC_BOOTSTRAP_ADMIN_PASSWORD=unused DOKENE_OIDC_CLIENT_SECRET=unused
    export DOKENE_TEST_OWNER_PASSWORD='owner"pass\word1' DOKENE_TEST_OPERATOR_PASSWORD=operatorpass12 \
           DOKENE_TEST_VIEWER_PASSWORD=viewerpass123
    bash -c "$script" >/dev/null 2>&1 || exit 1
    [ "$(jq -r '[.users[] | .credentials[0].value] | join(",")' "$work/import/dokene-realm.json")" \
      = 'owner"pass\word1,operatorpass12,viewerpass123' ] || exit 1
    # An empty password refuses to start and names the variable.
    out="$(DOKENE_TEST_VIEWER_PASSWORD='' bash -c "$script" 2>&1)" && exit 1
    grep -q 'Set DOKENE_TEST_VIEWER_PASSWORD in .env' <<<"$out"
  ) && rc=0
  rm -rf "$work"
  return "$rc"
}
check "keycloak entrypoint fills each user's password and refuses an empty one (#314)" \
  keycloak_entrypoint_substitutes_each_password

# check_env against an .env from before #314: the old shared variable, none of the per user ones.
check_env_upgrades_test_passwords() {
  local work rc=1
  work="$(mktemp -d)" || return 1
  cat > "$work/.env" <<ENV
DOKENE_DB_NAME=dokene
DOKENE_DB_BOOTSTRAP_USERNAME=admin
DOKENE_DB_BOOTSTRAP_PASSWORD=unused
DOKENE_DB_PASSWORD=unused
DOKENE_DB_RUNTIME_PASSWORD=unused
DOKENE_DB_MIGRATION_PASSWORD=unused
DOKENE_TENANT_CONTEXT_SIGNING_KEY=$(printf '0123456789abcdef%.0s' 1 2 3 4)
KC_BOOTSTRAP_ADMIN_USERNAME=admin
KC_BOOTSTRAP_ADMIN_PASSWORD=unused
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_DOKENE_CLIENT_SECRET=unused
DOKENE_TEST_USER_PASSWORD=old-shared-value
DOKENE_TEST_VIEWER_PASSWORD=
ENV
  (
    # shellcheck source=../dev-env.sh
    source "$SCRIPT" || exit 1
    ENV_FILE="$work/.env"
    run_check_env() { CHECK_FAILURES=0; check_env >"$work/out" 2>&1; }
    run_check_env && [ "$CHECK_FAILURES" -eq 0 ] || exit 1
    for key in DOKENE_TEST_OWNER_PASSWORD DOKENE_TEST_OPERATOR_PASSWORD DOKENE_TEST_VIEWER_PASSWORD; do
      value="$(env_get "$key")"
      grep -Eq '^[0-9a-f]{32}$' <<<"$value" || exit 1
      ! grep -qF "$value" "$work/out" || exit 1
    done
    [ "$(env_get DOKENE_TEST_OWNER_PASSWORD)" != "$(env_get DOKENE_TEST_VIEWER_PASSWORD)" ] || exit 1
    grep -q 'Generated test user passwords in .env: DOKENE_TEST_OWNER_PASSWORD DOKENE_TEST_OPERATOR_PASSWORD DOKENE_TEST_VIEWER_PASSWORD' "$work/out" || exit 1
    grep -q 'DOKENE_TEST_USER_PASSWORD in .env is no longer used' "$work/out" || exit 1
    ! grep -q 'old-shared-value' "$work/out" || exit 1
    # A second run keeps the values it generated.
    owner="$(env_get DOKENE_TEST_OWNER_PASSWORD)"
    run_check_env && [ "$(env_get DOKENE_TEST_OWNER_PASSWORD)" = "$owner" ] && ! grep -q 'Generated' "$work/out" || exit 1
    # Custom values: 12 characters inside one quote pair pass; 11 inside quotes (13 raw) fail naming only the variable.
    env_set DOKENE_TEST_OPERATOR_PASSWORD '"twelve-chars"'
    run_check_env && [ "$CHECK_FAILURES" -eq 0 ] || exit 1
    env_set DOKENE_TEST_OPERATOR_PASSWORD "'eleven-char'"
    ! run_check_env || [ "$CHECK_FAILURES" -eq 1 ] || exit 1
    grep -q 'DOKENE_TEST_OPERATOR_PASSWORD in .env must be at least 12 characters' "$work/out" || exit 1
    ! grep -q 'eleven-char' "$work/out" || exit 1
    # Compose does not pass these on as written: an inline comment or `$` in an unquoted value, `$` or a backslash
    # inside double quotes. Each fails preflight naming only the variable. Single quotes are literal and pass.
    # Written directly, so each case is the exact .env text under test.
    set_operator_line() {
      grep -v '^DOKENE_TEST_OPERATOR_PASSWORD=' "$work/.env" > "$work/.env.next"
      printf 'DOKENE_TEST_OPERATOR_PASSWORD=%s\n' "$1" >> "$work/.env.next"
      mv "$work/.env.next" "$work/.env"
    }
    # shellcheck disable=SC2016  # the values are literal .env text
    for value in 'abc123 # padded-out' 'twelve$chars-x' '"twelve$chars-x"' '"twelve\chars-x"'; do
      set_operator_line "$value"
      [ "$(env_get DOKENE_TEST_OPERATOR_PASSWORD)" = "$value" ] || exit 1
      run_check_env
      [ "$CHECK_FAILURES" -eq 1 ] || exit 1
      grep -q 'DOKENE_TEST_OPERATOR_PASSWORD in .env .* Compose' "$work/out" || exit 1
      ! grep -qF "${value:1:5}" "$work/out" || exit 1
    done
    set_operator_line "'twelve\$chars # x'"
    run_check_env && [ "$CHECK_FAILURES" -eq 0 ]
  ) && rc=0
  rm -rf "$work"
  return "$rc"
}
check "check_env generates missing test passwords, keeps them, and enforces 12 characters and Compose-safe values (#314)" \
  bash -c "$(declare -f check_env_upgrades_test_passwords) ; SCRIPT='$SCRIPT'; check_env_upgrades_test_passwords"

# `doctor` changes .env only after a yes: without a terminal it must leave the file alone and name what is missing.
doctor_does_not_write_test_passwords_unasked() {
  local work rc=1
  work="$(mktemp -d)" || return 1
  printf 'DOKENE_DB_NAME=dokene\n' > "$work/.env"
  (
    # shellcheck source=../dev-env.sh
    source "$SCRIPT" || exit 1
    declare -f cmd_doctor | grep -q 'CONFIRM_ENV_WRITES=true' || exit 1
    ENV_FILE="$work/.env"
    before="$(cat "$work/.env")"
    CHECK_FAILURES=0; ASSUME_YES=false; CONFIRM_ENV_WRITES=true
    check_env >"$work/out" 2>&1 </dev/null
    [ "$CHECK_FAILURES" -ge 1 ] && [ "$(cat "$work/.env")" = "$before" ] || exit 1
    ! grep -q 'Generated test user passwords' "$work/out" || exit 1
    grep -q "dev-env.sh up' generates" "$work/out"
  ) && rc=0
  rm -rf "$work"
  return "$rc"
}
check "doctor does not write test passwords to .env without a yes (#314 review)" \
  bash -c "$(declare -f doctor_does_not_write_test_passwords_unasked) ; SCRIPT='$SCRIPT'; doctor_does_not_write_test_passwords_unasked"

# The scripts that log in as the test users share one loader, so a role or variable changes in one place.
# The bash -c scripts below are single quoted on purpose: the child shell expands them, with the passwords given only
# through `env`, so this file never assigns the variables the other checks set in their own subshells.
# shellcheck disable=SC2016
test_password_lib_loads_and_fails_closed() {
  local scripts_dir lib out
  scripts_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)" || return 1
  lib="$scripts_dir/lib/test-passwords.sh"
  [ -f "$lib" ] || return 1
  env DOKENE_TEST_OWNER_PASSWORD=owner-value-12 DOKENE_TEST_OPERATOR_PASSWORD=operator-value \
      DOKENE_TEST_VIEWER_PASSWORD=viewer-value-1 bash -c '
    source "$1" || exit 1
    [ "$OWNER_PASSWORD" = owner-value-12 ] && [ "$(test_password_for testoperator)" = operator-value ] \
      && [ "$(test_password_for testviewer)" = viewer-value-1 ] || exit 1
    ! out="$(test_password_for nobody 2>&1)" && grep -q "no test password for nobody" <<<"$out"
  ' _ "$lib" || return 1
  # A missing variable stops the sourcing script and names the variable and the fix.
  ! out="$(env -u DOKENE_TEST_VIEWER_PASSWORD DOKENE_TEST_OWNER_PASSWORD=a DOKENE_TEST_OPERATOR_PASSWORD=b \
            bash -c 'source "$1"; echo reached' _ "$lib" 2>&1)" || return 1
  grep -q 'DOKENE_TEST_VIEWER_PASSWORD is not set; run ./scripts/dev-env.sh up' <<<"$out" && ! grep -q reached <<<"$out" || return 1
  # No copy of the block is left, and no login posts the result of a lookup whose failure errexit cannot see.
  ! grep -l 'MISSING_PASSWORD_HINT=' "$scripts_dir"/*.sh || return 1
  ! grep -n 'password=\$(test_password_for' "$scripts_dir"/*.sh
}
check "test user passwords load from one sourced file that fails closed (#314 review)" test_password_lib_loads_and_fails_closed

shellcheck_gate_rejects_old_versions() {
  shellcheck_supported 0.11.0 && shellcheck_supported 0.12.1 && ! shellcheck_supported 0.10.0 && ! shellcheck_supported ''
}
check "the ShellCheck gate accepts only the minimum version or newer" shellcheck_gate_rejects_old_versions

infrastructure_containers_are_restricted() {
  local json=""
  json="$(compose_config_json)" || return 1
  python3 -I -c '
import json, sys
s = json.load(sys.stdin)["services"]
# Every writable path of the two services; anything else must be read-only.
writable = {
    "postgres": {"volumes": ["/var/lib/postgresql/data"], "tmpfs": ["/tmp", "/var/run/postgresql"]},
    "keycloak": {"volumes": [], "tmpfs": ["/opt/keycloak/data", "/tmp"]},
}
for name, expected in writable.items():
    svc = s[name]
    assert svc.get("read_only") is True, name + " must have a read-only root filesystem"
    assert svc.get("cap_drop") == ["ALL"] and not svc.get("cap_add"), name + " must hold no Linux capability"
    assert "no-new-privileges:true" in svc.get("security_opt", []), name + " must set no-new-privileges"
    assert not svc.get("privileged"), name + " must not be privileged"
    volumes = sorted(v["target"] for v in svc.get("volumes", []) if not v.get("read_only"))
    assert volumes == expected["volumes"], name + " writable volumes: " + repr(volumes)
    tmpfs = svc.get("tmpfs", [])
    assert sorted(t.split(":")[0] for t in tmpfs) == expected["tmpfs"], name + " tmpfs: " + repr(tmpfs)
    assert all("size=" in t for t in tmpfs), name + " tmpfs mounts must be size-capped"
# The stock entrypoint starts as root and needs CHOWN/SETUID/SETGID to step down; starting as postgres needs none.
assert s["postgres"].get("user") == "70:70", "postgres must start as the unprivileged postgres user"
options = lambda name, path: [t.split(":", 1)[1].split(",") for t in s[name]["tmpfs"] if t.split(":")[0] == path][0]
assert {"uid=70", "gid=70"} <= set(options("postgres", "/var/run/postgresql")), "socket tmpfs must belong to postgres"
# The image sets USER 1000; Compose must not override it with another user.
assert s["keycloak"].get("user") in (None, "1000", "1000:0"), "keycloak must run as the unprivileged keycloak user"
# This directory holds the rendered realm import (client secret, test password).
assert {"uid=1000", "mode=0700"} <= set(options("keycloak", "/opt/keycloak/data")), \
    "keycloak data tmpfs must be private to the keycloak user"
# The home directory is on the read-only root; psql must keep its history on a writable path.
assert s["postgres"]["environment"].get("PSQL_HISTORY", "").startswith("/tmp/"), "psql history must be writable"
' <<<"$json"
}
check "compose: postgres and keycloak run restricted with explicit writable paths (#319)" \
  infrastructure_containers_are_restricted

# A Debian-based postgres image uses uid 999; the user and the owner of the socket tmpfs must change together.
postgres_uid_is_set_in_one_place() {
  local json=""
  json="$(compose_config_json DOKENE_POSTGRES_UID=999)" || return 1
  python3 -I -c '
import json, sys
postgres = json.load(sys.stdin)["services"]["postgres"]
assert postgres["user"] == "999:999", "user: " + repr(postgres["user"])
socket = [t for t in postgres["tmpfs"] if t.startswith("/var/run/postgresql:")][0]
assert {"uid=999", "gid=999"} <= set(socket.split(":", 1)[1].split(",")), "socket tmpfs: " + socket
' <<<"$json"
}
check "compose: one variable sets the postgres uid for the user and its socket tmpfs (#319 review)" \
  postgres_uid_is_set_in_one_place

compose_minimum_includes_patch() {
  # shellcheck source=../dev-env.sh
  source "$SCRIPT" || return 1
  compose_version_supported 2 27 2 && compose_version_supported 2 28 0 && compose_version_supported 5 6 1 \
    && ! compose_version_supported 2 27 1 && ! compose_version_supported 2 17 0 && ! compose_version_supported 1 99 9
}
check "compose minimum is 2.27.2, patch included (#308 review)" \
  bash -c "$(declare -f compose_minimum_includes_patch) ; SCRIPT='$SCRIPT'; compose_minimum_includes_patch"

doctor_does_not_write_secret_files() {
  # shellcheck source=../dev-env.sh
  source "$SCRIPT" || return 1
  # shellcheck disable=SC2329  # stubs the sourced cmd_doctor must not reach
  resolve_secret_files() { echo called; }
  # shellcheck disable=SC2329
  write_secret_files() { echo called; }
  check_docker() { :; }; check_tools() { :; }; check_env() { :; }; check_ports() { :; }; ok() { :; }
  [ -z "$(cmd_doctor)" ]
}
check "doctor leaves secret files to the start commands (#308 review)" \
  bash -c "$(declare -f doctor_does_not_write_secret_files) ; SCRIPT='$SCRIPT'; doctor_does_not_write_secret_files"

# Runs the real `docker compose config --environment` against a copy of compose.yaml and a quoted .env (#308 review).
secret_files_follow_compose_interpolation() {
  local work key rc=1
  work="$(mktemp -d)" || return 1
  # Built at run time: a synthetic 64-hex value, kept out of the source so secret scanners have nothing to match.
  key="$(printf '0123456789abcdef%.0s' 1 2 3 4)"
  cp "$ROOT_DIR/compose.yaml" "$work/"
  cat > "$work/.env" <<ENV
DOKENE_DB_NAME=dokene
DOKENE_DB_BOOTSTRAP_USERNAME=admin
DOKENE_DB_BOOTSTRAP_PASSWORD=unused
DOKENE_DB_PASSWORD=unused
DOKENE_DB_RUNTIME_PASSWORD=unused
DOKENE_DB_MIGRATION_PASSWORD="quoted pass"
DOKENE_TENANT_CONTEXT_SIGNING_KEY='$key'
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_DOKENE_CLIENT_SECRET=unused
DOKENE_SECRETS_DIR=./custom-secrets
ENV
  (
    # shellcheck source=../dev-env.sh
    source "$SCRIPT" || exit 1
    ROOT_DIR="$work"
    CALLS=""; RM_FAILS=false
    warn() { :; }; fail() { :; }
    compose() {
      if [ "$1" = "rm" ]; then CALLS="$CALLS|$*"; [ "$RM_FAILS" = "false" ]; else (cd "$ROOT_DIR" && docker compose "$@"); fi
    }
    refresh_secret_files || exit 1
    [ "$(cat "$work/custom-secrets/DOKENE_DB_MIGRATION_PASSWORD")" = "quoted pass" ] || exit 1
    [ "$(cat "$work/custom-secrets/DOKENE_TENANT_CONTEXT_SIGNING_KEY")" = "$key" ] || exit 1
    [ ! -e "$work/.secrets" ] || exit 1
    [ "$(stat -c %a "$work/custom-secrets" 2>/dev/null || stat -f %Lp "$work/custom-secrets")" = "700" ] || exit 1
    # Unchanged values remove nothing.
    refresh_secret_files && [ -z "$CALLS" ] || exit 1
    # A changed key whose container removal fails keeps the old file, so the next run detects the change again.
    sed -i.bak "s/^DOKENE_TENANT_CONTEXT_SIGNING_KEY=.*/DOKENE_TENANT_CONTEXT_SIGNING_KEY=$(printf 'fedcba9876543210%.0s' 1 2 3 4)/" "$work/.env"
    RM_FAILS=true
    ! refresh_secret_files || exit 1
    [ "$(cat "$work/custom-secrets/DOKENE_TENANT_CONTEXT_SIGNING_KEY")" = "$key" ] || exit 1
    RM_FAILS=false; CALLS=""
    refresh_secret_files && [ "$CALLS" = "|rm -f -s migrate backend" ] || exit 1
    [ "$(cat "$work/custom-secrets/DOKENE_TENANT_CONTEXT_SIGNING_KEY")" != "$key" ] || exit 1
    # A shared, world-writable directory is refused and left as it was.
    mkdir -p "$work/shared" && chmod 1777 "$work/shared"
    sed -i.bak "s|^DOKENE_SECRETS_DIR=.*|DOKENE_SECRETS_DIR=$work/shared|" "$work/.env"
    ! refresh_secret_files || exit 1
    [ "$(stat -c %a "$work/shared" 2>/dev/null || stat -f %Lp "$work/shared")" = "1777" ] || exit 1
    [ ! -e "$work/shared/DOKENE_DB_MIGRATION_PASSWORD" ] || exit 1
    # So is a readable 0755 directory of the user's own: others could read the 0444 files through it.
    mkdir -p "$work/readable" && chmod 755 "$work/readable"
    sed -i.bak "s|^DOKENE_SECRETS_DIR=.*|DOKENE_SECRETS_DIR=$work/readable|" "$work/.env"
    ! refresh_secret_files || exit 1
    [ "$(stat -c %a "$work/readable" 2>/dev/null || stat -f %Lp "$work/readable")" = "755" ] || exit 1
    [ ! -e "$work/readable/DOKENE_TENANT_CONTEXT_SIGNING_KEY" ] || exit 1
    # A dedicated 0700 directory is accepted.
    mkdir -p "$work/private" && chmod 700 "$work/private"
    sed -i.bak "s|^DOKENE_SECRETS_DIR=.*|DOKENE_SECRETS_DIR=$work/private|" "$work/.env"
    refresh_secret_files && [ -f "$work/private/DOKENE_TENANT_CONTEXT_SIGNING_KEY" ]
  ) && rc=0
  rm -rf "$work"
  return "$rc"
}
check "secret files: Compose-resolved values, safe DOKENE_SECRETS_DIR, recreation survives a failed removal (#308)" secret_files_follow_compose_interpolation

# Runs the real healthcheck command in a container whose resolver has a search domain. Needs a Docker daemon.
healthcheck_survives_search_domain() {
  local json image probe net="dokene-smoke-$$" ctr="dokene-smoke-$$-bridge" rc=1
  json="$(compose_config_json)" || return 1
  image="$(python3 -I -c 'import json,sys; print(json.load(sys.stdin)["services"]["oidc-bridge"]["image"])' <<<"$json")" || return 1
  probe="$(python3 -I -c 'import json,sys; print(json.load(sys.stdin)["services"]["oidc-bridge"]["healthcheck"]["test"][1])' <<<"$json")" || return 1
  docker network create "$net" >/dev/null || return 1
  # The container is named like the service so that the probe resolves it through the network's embedded DNS.
  if docker run -d --rm --name "$ctr" --network "$net" --network-alias oidc-bridge --dns-search lan \
       --entrypoint sleep "$image" 60 >/dev/null; then
    # The relative form must fail here (proves the setup reproduces #171); the shipped probe must pass.
    if ! docker exec "$ctr" sh -c 'nslookup oidc-bridge >/dev/null 2>&1' \
       && docker exec "$ctr" sh -c "$probe"; then
      rc=0
    fi
  fi
  docker rm -f "$ctr" >/dev/null 2>&1
  docker network rm "$net" >/dev/null 2>&1
  return $rc
}
if docker info >/dev/null 2>&1; then
  check "oidc-bridge healthcheck passes with a DNS search domain (#171)" healthcheck_survives_search_domain
else
  printf '[skip] oidc-bridge healthcheck passes with a DNS search domain (#171): no Docker daemon\n'
fi

port_detection_is_privilege_free() {
  # Sourcing defines the functions without running a command.
  # shellcheck source=../dev-env.sh
  source "$SCRIPT" || return 1
  local port pid
  port="$(python3 -I -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])')" || return 1
  port_in_use "$port" && return 1   # free before the listener starts
  python3 -I -c 'import socket,sys,time; s=socket.socket(); s.bind(("127.0.0.1",int(sys.argv[1]))); s.listen(); time.sleep(30)' "$port" &
  pid=$!
  for _ in 1 2 3 4 5 6 7 8 9 10; do port_in_use "$port" && break; sleep 0.2; done
  local used=1
  port_in_use "$port" && used=0
  kill "$pid" 2>/dev/null; wait "$pid" 2>/dev/null
  [ "$used" -eq 0 ] && [ -n "$(port_listener "$port")" ]
}
check "port_in_use detects a listener without lsof/root (#163)" bash -c "$(declare -f port_detection_is_privilege_free) ; SCRIPT='$SCRIPT'; port_detection_is_privilege_free"

docker_mapping_counts_as_occupancy() {
  # userland-proxy=false: Docker publishes via NAT and no socket listens, but the mapping still blocks the port.
  # shellcheck source=../dev-env.sh
  source "$SCRIPT" || return 1
  docker() { printf 'other-project-web\t127.0.0.1:%s->8080/tcp\n' "$FAKE_PORT"; }
  have() { [ "$1" = "docker" ]; }
  FAKE_PORT=45999
  port_in_use 45999 && ! port_in_use 45998 && [ "$(port_listener 45999)" = "Docker container 'other-project-web'" ]
}
check "port_in_use counts a Docker-published mapping without a listening socket" \
  bash -c "$(declare -f docker_mapping_counts_as_occupancy) ; SCRIPT='$SCRIPT'; docker_mapping_counts_as_occupancy"

published_mapping_parsing() {
  # shellcheck source=../dev-env.sh
  source "$SCRIPT" || return 1
  hit()  { [ -n "$(printf 'web\t%s\n' "$2" | publishes_tcp_port "$1")" ]; }
  hit 8080 "127.0.0.1:8080->8080/tcp" \
    && hit 8080 "0.0.0.0:8080->8080/tcp, [::]:8080->8080/tcp" \
    && hit 8080 "127.0.0.1:8079-8081->8079-8081/tcp" \
    && hit 8080 "127.0.0.1:8080->9999/udp, 127.0.0.1:8080->8080/tcp" \
    && ! hit 8080 "127.0.0.1:8080->9999/udp" \
    && ! hit 8082 "127.0.0.1:8079-8081->8079-8081/tcp" \
    && ! hit 8080 "127.0.0.1:18080->8080/tcp" \
    && ! hit 8080 "8080/tcp"
}
check "published mappings: TCP only, ranges honoured" \
  bash -c "$(declare -f published_mapping_parsing) ; SCRIPT='$SCRIPT'; published_mapping_parsing"

networkless_detection() {
  # shellcheck source=../dev-env.sh
  source "$SCRIPT" || return 1
  [ "$(printf 'b\tcreated\t0\n' | networkless_containers)" = "b" ] \
    && [ "$(printf 'b\trunning\t0\n' | networkless_containers)" = "b" ] \
    && [ -z "$(printf 'b\trunning\t1\n' | networkless_containers)" ] \
    && [ -z "$(printf 'b\texited\t0\n' | networkless_containers)" ]
}
check "a bridge attached to no network is detected (#164)" \
  bash -c "$(declare -f networkless_detection) ; SCRIPT='$SCRIPT'; networkless_detection"

repair_removes_only_broken_bridge() {
  # shellcheck source=../dev-env.sh
  source "$SCRIPT" || return 1
  CALLS=""
  warn() { :; }
  compose() { if [ "$1" = "ps" ]; then echo "${FAKE_ID:-}"; else CALLS="$CALLS|$*"; fi; }
  docker() { printf '/dokene-oidc-bridge-1\t%s\n' "$FAKE_STATE"; }
  FAKE_ID=abc FAKE_STATE="created	0"; repair_networkless_bridge
  [ "$CALLS" = "|rm -f -s oidc-bridge backend" ] || return 1
  CALLS=""; FAKE_STATE="running	1"; repair_networkless_bridge; [ -z "$CALLS" ] || return 1
  CALLS=""; FAKE_STATE="exited	0"; repair_networkless_bridge; [ -z "$CALLS" ] || return 1
  CALLS=""; FAKE_ID=""; FAKE_STATE="created	0"; repair_networkless_bridge; [ -z "$CALLS" ]
}
check "repair removes a network-less bridge and leaves healthy or stopped ones (#164)" \
  bash -c "$(declare -f repair_removes_only_broken_bridge) ; SCRIPT='$SCRIPT'; repair_removes_only_broken_bridge"

discard_removes_created_services() {
  # shellcheck source=../dev-env.sh
  source "$SCRIPT" || return 1
  CALLS=""
  warn() { :; }
  compose() { if [ "$1" = "ps" ]; then printf '%s' "$FAKE_STALE"; else CALLS="$CALLS|$*"; fi; }
  FAKE_STALE=$'backend\noidc-bridge'; discard_unstarted_containers
  [ "$CALLS" = "|rm -f -s backend oidc-bridge" ] || return 1
  CALLS=""; FAKE_STALE=""; discard_unstarted_containers; [ -z "$CALLS" ]
}
check "failed start removes created services only" \
  bash -c "$(declare -f discard_removes_created_services) ; SCRIPT='$SCRIPT'; discard_removes_created_services"

start_failure_detection() {
  # shellcheck source=../dev-env.sh
  source "$SCRIPT" || return 1
  local f; f="$(mktemp)"
  echo 'Error response from daemon: driver failed programming external connectivity: Bind for 127.0.0.1:8080 failed: port is already allocated' > "$f"
  is_start_failure_log "$f" || { rm -f "$f"; return 1; }
  echo 'Error response from daemon: pull access denied for dokene-backend, repository does not exist' > "$f"
  ! is_start_failure_log "$f"; local rc=$?
  rm -f "$f"; return $rc
}
check "only start/bind errors count as a start failure" \
  bash -c "$(declare -f start_failure_detection) ; SCRIPT='$SCRIPT'; start_failure_detection"

# Issue 327: env_set writes generated secrets; the value must reach awk through its environment, not its argv, which
# any local user can read in the process table. The shadowing awk() records every argument list env_set passes.
env_set_keeps_the_value_off_awk_argv() {
  # shellcheck source=../dev-env.sh
  source "$SCRIPT" || return 1
  local work value rc=1
  work="$(mktemp -d)" || return 1
  ENV_FILE="$work/.env"
  printf 'OTHER=1\nDOKENE_DB_PASSWORD=old\n' > "$ENV_FILE"
  awk() { printf '%s\n' "$*" >> "$work/argv"; command awk "$@"; }
  # Synthetic, built at run time; the backslash and quote prove the value is written byte for byte.
  value="syn'the\\tic\$-$(printf '%s' 327327327)"
  env_set DOKENE_DB_PASSWORD "$value" && env_set DOKENE_NEW_KEY "$value" \
    && [ "$(env_get DOKENE_DB_PASSWORD)" = "$value" ] && [ "$(env_get DOKENE_NEW_KEY)" = "$value" ] \
    && [ "$(env_get OTHER)" = 1 ] && [ "$(grep -c '^DOKENE_DB_PASSWORD=' "$ENV_FILE")" -eq 1 ] \
    && [ -s "$work/argv" ] && ! grep -qF -- 327327327 "$work/argv" && rc=0
  rm -rf "$work"
  return "$rc"
}
check "env_set keeps the value out of awk's argument list and writes it byte for byte (#327)" \
  bash -c "$(declare -f env_set_keeps_the_value_off_awk_argv) ; SCRIPT='$SCRIPT'; env_set_keeps_the_value_off_awk_argv"

# Issue 327: the PostgreSQL init script creates the application roles without their passwords on psql's command line.
# A stub psql records its argv and stdin; the passwords must appear in neither (stdin carries \getenv by name only).
postgres_init_keeps_role_passwords_off_psql_argv() {
  local work rc=1 init="$ROOT_DIR/infra/docker/postgres/init/01-create-database-roles.sh"
  work="$(mktemp -d)" || return 1
  mkdir -p "$work/bin"
  printf '#!/usr/bin/env bash\nprintf "%%s\\n" "$*" > "%s/argv"; cat > "%s/stdin"\n' "$work" "$work" > "$work/bin/psql"
  chmod +x "$work/bin/psql"
  if PATH="$work/bin:$PATH" POSTGRES_USER=bootstrap POSTGRES_DB=dokene \
       DOKENE_DB_MIGRATION_PASSWORD="mig-$(printf '%s' 327001)" DOKENE_DB_RUNTIME_PASSWORD="run-$(printf '%s' 327002)" \
       bash "$init" \
     && ! grep -qE '327001|327002|password' "$work/argv" && grep -q -- '--set=database_name=dokene' "$work/argv" \
     && ! grep -qE '327001|327002' "$work/stdin" \
     && grep -qx '\\getenv migration_password DOKENE_DB_MIGRATION_PASSWORD' "$work/stdin" \
     && grep -qx '\\getenv runtime_password DOKENE_DB_RUNTIME_PASSWORD' "$work/stdin" \
     && grep -q "PASSWORD :'migration_password';" "$work/stdin" && grep -q "PASSWORD :'runtime_password';" "$work/stdin" \
     && ! PATH="$work/bin:$PATH" POSTGRES_USER=bootstrap POSTGRES_DB=dokene DOKENE_DB_RUNTIME_PASSWORD=x \
            bash "$init" 2>/dev/null; then
    rc=0
  fi
  rm -rf "$work"
  return "$rc"
}
check "PostgreSQL init passes role passwords by variable name, never on psql's argv, and fails closed (#327)" \
  postgres_init_keeps_role_passwords_off_psql_argv

if [ "$failures" -gt 0 ]; then
  printf '%s check(s) failed\n' "$failures" >&2
  exit 1
fi
printf 'All checks passed\n'
