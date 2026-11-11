#!/usr/bin/env bash
# ABOUTME: Starts, stops and inspects the local Dokene environment (Docker Compose).
# ABOUTME: Also generates and checks the git ignored .env that holds every local secret.
# ==============================================================================
# dev-env.sh - start, stop and inspect the local Dokene environment
#
# Runs PostgreSQL, Keycloak, the backend and the frontend as Docker containers
# (see compose.yaml) so every contributor gets the same runtime configuration.
# Only Docker, curl and jq are required on the host.
#
# Usage:
#   ./scripts/dev-env.sh up [--seed] [--infra-only] [--no-build] [--yes]
#   ./scripts/dev-env.sh down
#   ./scripts/dev-env.sh restart [--seed]
#   ./scripts/dev-env.sh restart-backend [--no-build] [--yes]   (runs migrate, recreates backend + oidc-bridge)
#   ./scripts/dev-env.sh status
#   ./scripts/dev-env.sh logs [service]
#   ./scripts/dev-env.sh seed [--verify]
#   ./scripts/dev-env.sh doctor
#   ./scripts/dev-env.sh reset [--yes]     (destroys local database and Keycloak data)
#   ./scripts/dev-env.sh help
#
# Compatible with bash 3.2 (macOS default) and Linux.
# ==============================================================================

set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="$ROOT_DIR/.env"
ENV_EXAMPLE="$ROOT_DIR/.env.example"
MIN_COMPOSE_MAJOR=2
MIN_COMPOSE_MINOR=27
# 2.27.2 added `docker compose config --environment`, which resolve_secret_files relies on (issue 308). Its `up --wait`
# already waits for a service that another one depends on with service_completed_successfully (`migrate`) to complete
# rather than to stay running (getDependencyCondition in pkg/compose/start.go at v2.27.2).
MIN_COMPOSE_PATCH=2
WAIT_TIMEOUT="${DOKENE_WAIT_TIMEOUT:-300}"

FRONTEND_PORT=5173
BACKEND_PORT=8080
# One generated password per realm test user (issue 314): testuser, testoperator and testviewer.
TEST_PASSWORD_KEYS=(DOKENE_TEST_OWNER_PASSWORD DOKENE_TEST_OPERATOR_PASSWORD DOKENE_TEST_VIEWER_PASSWORD)
TEST_PASSWORD_MIN_LENGTH=12  # the realm passwordPolicy length(12)

if [ -t 1 ]; then
  RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; BOLD=$'\033[1m'; RESET=$'\033[0m'
else
  RED=''; GREEN=''; YELLOW=''; BOLD=''; RESET=''
fi

info() { printf '%s\n' "$*"; }
ok()   { printf '%s[ok]%s %s\n' "$GREEN" "$RESET" "$*"; }
warn() { printf '%s[warn]%s %s\n' "$YELLOW" "$RESET" "$*" >&2; }
fail() { printf '%s[error]%s %s\n' "$RED" "$RESET" "$*" >&2; }
die()  { fail "$*"; exit 1; }

usage() {
  # Print the leading comment block (up to and including the closing rule), without the leading "# " and without
  # the ABOUTME header lines.
  awk '/^# ABOUTME:/ { next } NR > 1 && /^#/ { print; if (/^# =+$/ && ++rules == 2) exit } NR > 1 && !/^#/ { exit }' "${BASH_SOURCE[0]}" \
    | sed 's/^# \{0,1\}//'
}

# ------------------------------------------------------------------------------
# Helpers
# ------------------------------------------------------------------------------
have() { command -v "$1" >/dev/null 2>&1; }

compose() { (cd "$ROOT_DIR" && docker compose "$@"); }

# `docker compose down` still interpolates compose.yaml, which requires secrets to be set. Teardown does not
# depend on their values, so placeholders let it work with a missing or incomplete .env.
compose_teardown() {
  (
    cd "$ROOT_DIR" || exit 1
    local v
    for v in DOKENE_DB_NAME DOKENE_DB_BOOTSTRAP_USERNAME DOKENE_DB_BOOTSTRAP_PASSWORD DOKENE_DB_PASSWORD \
             DOKENE_DB_RUNTIME_PASSWORD DOKENE_DB_MIGRATION_PASSWORD DOKENE_TENANT_CONTEXT_SIGNING_KEY \
             KC_BOOTSTRAP_ADMIN_USERNAME KC_BOOTSTRAP_ADMIN_PASSWORD \
             SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_DOKENE_CLIENT_SECRET "${TEST_PASSWORD_KEYS[@]}"; do
      export "$v=${!v:-unused}"
    done
    docker compose "$@"
  )
}

project_name() {
  compose_teardown config --format json 2>/dev/null | sed -n 's/^  "name": "\(.*\)",\{0,1\}$/\1/p' | head -n 1
}

# True when this checkout already has a PostgreSQL data volume (its passwords were fixed when it was created).
postgres_volume_exists() {
  local project
  project="$(project_name)"
  [ -n "$project" ] || return 1
  [ -n "$(docker volume ls -q --filter "name=^${project}_dokene-postgres$" 2>/dev/null)" ]
}

# Reads KEY from .env (last assignment wins); prints nothing when absent.
env_get() {
  [ -f "$ENV_FILE" ] || return 0
  grep -E "^$1=" "$ENV_FILE" | tail -n 1 | cut -d= -f2-
}

# Replaces (or appends) KEY=VALUE in .env without sed -i (portable between GNU and BSD sed).
env_set() {
  local key="$1" value="$2" tmp
  tmp="$(mktemp)"
  awk -v k="$key" -v v="$value" 'BEGIN{done=0}
    $0 ~ "^"k"=" { print k"="v; done=1; next }
    { print }
    END { if (!done) print k"="v }' "$ENV_FILE" > "$tmp" && cat "$tmp" > "$ENV_FILE"
  rm -f "$tmp"
}

confirm() {
  # confirm "question" -> 0 when the user answers yes; always 0 with --yes.
  [ "${ASSUME_YES:-false}" = "true" ] && return 0
  [ -t 0 ] || return 1
  local answer
  printf '%s [y/N] ' "$1"
  read -r answer
  case "$answer" in y|Y|yes|YES) return 0 ;; *) return 1 ;; esac
}

# publishes_tcp_port PORT: reads `docker ps` Ports columns on stdin (tab-separated "name<TAB>ports" lines) and prints
# the first name whose mapping binds host TCP port PORT on loopback or all interfaces. Understands the `start-end`
# ranges Docker groups consecutive mappings into, and ignores UDP-only mappings.
publishes_tcp_port() {
  awk -F'\t' -v p="$1" '
    {
      n = split($2, maps, /, /)
      for (i = 1; i <= n; i++) {
        if (maps[i] !~ /\/tcp$/) continue
        if (match(maps[i], /^(127\.0\.0\.1|0\.0\.0\.0|\[::\]):[0-9]+(-[0-9]+)?->/) == 0) continue
        spec = substr(maps[i], 1, RLENGTH - 2)
        sub(/^.*\]:|^[0-9.]+:/, "", spec)
        split(spec, r, "-")
        lo = r[1] + 0; hi = (r[2] == "" ? lo : r[2] + 0)
        if (p + 0 >= lo && p + 0 <= hi) { print $1; exit }
      }
    }'
}

docker_container_publishing() {
  # Prints the name of a running container that publishes host TCP port $1, if any.
  docker ps --format '{{.Names}}	{{.Ports}}' 2>/dev/null | publishes_tcp_port "$1"
}

port_in_use() {
  # True when TCP port $1 is taken. Needs no privileges: `ss -ltn` and a connect probe see sockets of every user
  # (e.g. root-owned docker-proxy), whereas `lsof` as a regular user does not on Linux.
  local port="$1"
  # With userland-proxy=false Docker publishes through NAT and opens no listening socket, yet it still rejects a
  # second mapping of the same address and port, so a published mapping counts as occupancy on its own.
  if have docker && [ -n "$(docker_container_publishing "$port")" ]; then
    return 0
  fi
  if have ss; then
    ss -ltn 2>/dev/null | awk -v p=":$port" '$4 ~ p"$" {found=1} END {exit !found}'
  elif have nc; then
    nc -z 127.0.0.1 "$port" >/dev/null 2>&1
  elif have lsof; then
    [ -n "$(lsof -nP -iTCP:"$port" -sTCP:LISTEN 2>/dev/null | awk 'NR>1 {print "x"; exit}')" ]
  else
    return 1
  fi
}

port_listener() {
  # Prints a best-effort description of the owner of TCP port $1. Naming only: use port_in_use to decide.
  local port="$1" name=""
  if have docker; then
    name="$(docker_container_publishing "$port")"
    [ -n "$name" ] && { echo "Docker container '$name'"; return; }
  fi
  if have lsof; then
    name="$(lsof -nP -iTCP:"$port" -sTCP:LISTEN 2>/dev/null | awk 'NR>1 {print $1" (pid "$2")"; exit}')"
  fi
  if [ -z "$name" ] && have ss; then
    name="$(ss -ltnp 2>/dev/null | awk -v p=":$port" '$4 ~ p"$" && $6 != "" {print $6; exit}')"
  fi
  echo "${name:-an unknown process (owned by another user; try: sudo ss -ltnp)}"
}

port_owned_by_compose() {
  # True when one of this project's containers already publishes host TCP port $1.
  [ -n "$(compose_teardown ps --format '{{.Name}}	{{.Ports}}' 2>/dev/null | publishes_tcp_port "$1")" ]
}

# ------------------------------------------------------------------------------
# Preflight checks
# ------------------------------------------------------------------------------
CHECK_FAILURES=0
check_fail() { fail "$*"; CHECK_FAILURES=$((CHECK_FAILURES + 1)); }

check_docker() {
  if ! have docker; then
    check_fail "Docker is not installed. Install Docker Desktop (macOS/Windows) or Docker Engine (Linux): https://docs.docker.com/get-docker/"
    return
  fi
  if ! docker info >/dev/null 2>&1; then
    check_fail "Docker is installed but the daemon is not reachable. Start Docker Desktop / the docker service and retry."
    return
  fi
  ok "Docker daemon reachable ($(docker version --format '{{.Server.Version}}' 2>/dev/null))"

  local version major minor patch
  version="$(docker compose version --short 2>/dev/null | sed 's/^v//')"
  if [ -z "$version" ]; then
    check_fail "Docker Compose v2 is not available ('docker compose'). Update Docker or install the compose plugin."
    return
  fi
  major="${version%%.*}"
  minor="${version#*.}"; minor="${minor%%.*}"
  patch=0
  case "$version" in *.*.*) patch="${version#*.*.}"; patch="${patch%%[!0-9]*}" ;; esac
  if ! compose_version_supported "$major" "$minor" "$patch"; then
    check_fail "Docker Compose $version is too old; $MIN_COMPOSE_MAJOR.$MIN_COMPOSE_MINOR.$MIN_COMPOSE_PATCH or newer is required (build additional_contexts, config --environment)."
    return
  fi
  ok "Docker Compose $version"
}

# compose_version_supported MAJOR MINOR PATCH: true when the version is at least the minimum (non-numeric parts fail).
compose_version_supported() {
  [ "$1" -gt "$MIN_COMPOSE_MAJOR" ] 2>/dev/null && return 0
  [ "$1" -eq "$MIN_COMPOSE_MAJOR" ] 2>/dev/null || return 1
  [ "$2" -gt "$MIN_COMPOSE_MINOR" ] 2>/dev/null && return 0
  [ "$2" -eq "$MIN_COMPOSE_MINOR" ] 2>/dev/null || return 1
  [ "$3" -ge "$MIN_COMPOSE_PATCH" ] 2>/dev/null
}

check_tools() {
  have curl || check_fail "curl is required for health checks."
  have openssl || warn "openssl not found: it is only needed to generate a new .env automatically."
  # Every `up` runs keycloak-apply-realm-settings.sh, which needs jq; without it an existing realm would keep its old
  # test user passwords while .env holds the new ones (#314).
  have jq || check_fail "jq is required by scripts/keycloak-apply-realm-settings.sh and scripts/seed-local-qa.sh (macOS: brew install jq)."
}

generate_env_file() {
  have openssl || die "openssl is required to generate .env. Create it manually from .env.example instead."
  info "Creating .env from .env.example with generated local-only secrets..."
  cp "$ENV_EXAMPLE" "$ENV_FILE"
  chmod 600 "$ENV_FILE"
  local runtime_password
  runtime_password="$(openssl rand -hex 16)"
  env_set DOKENE_DB_NAME dokene
  env_set DOKENE_DB_BOOTSTRAP_USERNAME dokene_admin
  env_set DOKENE_DB_BOOTSTRAP_PASSWORD "$(openssl rand -hex 16)"
  env_set DOKENE_DB_URL "jdbc:postgresql://localhost:5432/dokene"
  env_set DOKENE_DB_PASSWORD "$runtime_password"
  env_set DOKENE_DB_RUNTIME_PASSWORD "$runtime_password"
  env_set DOKENE_DB_MIGRATION_PASSWORD "$(openssl rand -hex 16)"
  env_set DOKENE_TENANT_CONTEXT_SIGNING_KEY "$(openssl rand -hex 32)"
  env_set KC_BOOTSTRAP_ADMIN_PASSWORD "$(openssl rand -hex 16)"
  env_set SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_DOKENE_CLIENT_SECRET "$(openssl rand -hex 16)"
  local key
  for key in "${TEST_PASSWORD_KEYS[@]}"; do
    env_set "$key" "$(openssl rand -hex 16)"
  done
  ok ".env created (git-ignored). AI provider defaults to 'fake'; set DOKENE_AI_PROVIDER=openai and DOKENE_AI_OPENAI_API_KEY for live OpenAI calls."
}

# Test user passwords added after a checkout's .env was created (issue 314) are generated in place, so an existing
# stack upgrades on its next `up` without a reset. Names the variables it adds, never the values.
fill_test_passwords() {
  local key added=""
  for key in "${TEST_PASSWORD_KEYS[@]}"; do
    [ -z "$(env_get "$key")" ] || continue
    have openssl || return 0  # the blank required keys check below then names the variable
    env_set "$key" "$(openssl rand -hex 16)"
    added="$added $key"
  done
  [ -z "$added" ] || ok "Generated test user passwords in .env:$added"
}

# check_test_passwords: each test user password is at least the realm policy's 12 characters, measured without one
# surrounding quote pair. Keycloak is not relied on to apply the policy to imported credentials.
check_test_passwords() {
  local key value
  for key in "${TEST_PASSWORD_KEYS[@]}"; do
    value="$(env_get "$key")"
    case "$value" in
      \"*\") value="${value#\"}"; value="${value%\"}" ;;
      \'*\') value="${value#\'}"; value="${value%\'}" ;;
    esac
    [ "${#value}" -ge "$TEST_PASSWORD_MIN_LENGTH" ] \
      || check_fail "$key in .env must be at least $TEST_PASSWORD_MIN_LENGTH characters (the realm password policy)."
  done
  grep -q '^DOKENE_TEST_USER_PASSWORD=' "$ENV_FILE" \
    && warn "DOKENE_TEST_USER_PASSWORD in .env is no longer used; each test user has its own DOKENE_TEST_*_PASSWORD. You can delete it."
  return 0
}

check_env() {
  if [ ! -f "$ENV_FILE" ]; then
    [ -f "$ENV_EXAMPLE" ] || { check_fail ".env.example is missing; run the script from a complete checkout."; return; }
    if confirm ".env does not exist. Create it from .env.example with generated local secrets?"; then
      if postgres_volume_exists; then
        check_fail "A PostgreSQL data volume from a previous run exists, but its passwords belong to the old .env. Run './scripts/dev-env.sh reset' first (this deletes the local data), then retry."
        return
      fi
      generate_env_file
    else
      check_fail ".env is missing. Copy .env.example to .env and fill every blank value (or rerun with --yes to generate it)."
      return
    fi
  else
    ok ".env found"
  fi

  fill_test_passwords

  local key missing=""
  for key in DOKENE_DB_NAME DOKENE_DB_BOOTSTRAP_USERNAME DOKENE_DB_BOOTSTRAP_PASSWORD \
             DOKENE_DB_PASSWORD DOKENE_DB_RUNTIME_PASSWORD DOKENE_DB_MIGRATION_PASSWORD \
             DOKENE_TENANT_CONTEXT_SIGNING_KEY KC_BOOTSTRAP_ADMIN_USERNAME KC_BOOTSTRAP_ADMIN_PASSWORD \
             SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_DOKENE_CLIENT_SECRET "${TEST_PASSWORD_KEYS[@]}"; do
    [ -n "$(env_get "$key")" ] || missing="$missing $key"
  done
  if [ -n "$missing" ]; then
    check_fail ".env has blank required values:$missing"
    return
  fi

  check_test_passwords

  if [ "$(env_get DOKENE_DB_PASSWORD)" != "$(env_get DOKENE_DB_RUNTIME_PASSWORD)" ]; then
    check_fail "DOKENE_DB_PASSWORD and DOKENE_DB_RUNTIME_PASSWORD must contain the same value."
  fi
  if ! printf '%s' "$(env_get DOKENE_TENANT_CONTEXT_SIGNING_KEY)" | grep -Eq '^[0-9a-fA-F]{64}$'; then
    check_fail "DOKENE_TENANT_CONTEXT_SIGNING_KEY must be 64 hexadecimal characters (openssl rand -hex 32)."
  fi

  local provider
  provider="$(env_get DOKENE_AI_PROVIDER)"
  case "$provider" in
    ""|fake) ;;
    openai)
      [ -n "$(env_get DOKENE_AI_OPENAI_API_KEY)" ] \
        || check_fail "DOKENE_AI_PROVIDER=openai requires DOKENE_AI_OPENAI_API_KEY in .env (or use fake / leave blank)." ;;
    *) check_fail "DOKENE_AI_PROVIDER='$provider' is not supported. Use fake, openai, or leave it blank." ;;
  esac
}

check_ports() {
  local ports port listener
  ports="$FRONTEND_PORT $BACKEND_PORT $(env_get KEYCLOAK_PORT) $(env_get DOKENE_DB_HOST_PORT)"
  [ -z "$(env_get KEYCLOAK_PORT)" ] && ports="$FRONTEND_PORT $BACKEND_PORT 8081 $(env_get DOKENE_DB_HOST_PORT)"
  [ -z "$(env_get DOKENE_DB_HOST_PORT)" ] && ports="$ports 5432"
  if [ "${INFRA_ONLY:-false}" = "true" ]; then
    ports="$(env_get KEYCLOAK_PORT) $(env_get DOKENE_DB_HOST_PORT)"
    [ -z "$(env_get KEYCLOAK_PORT)" ] && ports="8081 $ports"
    [ -z "$(env_get DOKENE_DB_HOST_PORT)" ] && ports="$ports 5432"
  fi
  for port in $ports; do
    port_in_use "$port" || continue
    if port_owned_by_compose "$port"; then
      continue
    fi
    listener="$(port_listener "$port")"
    check_fail "Host port $port is already in use by: $listener"
    case "$listener" in
      "Docker container "*)
        info "      Stop it with 'docker stop <name>' (or 'docker compose -p <project> down' if it belongs to another Compose project)." ;;
      *)
        if [ "$port" = "5432" ]; then
          info "      A PostgreSQL installed on the host is running. Stop its service (e.g. 'sudo systemctl stop postgresql')."
        else
          info "      Stop that process, or free the port before starting Dokene (ports 5173, 8080 and KEYCLOAK_PORT are registered in Keycloak and cannot be remapped freely)."
        fi ;;
    esac
    [ "$port" = "5432" ] \
      && info "      Or set DOKENE_DB_HOST_PORT=5433 in .env (host-run tools only; the containers always use the internal network)."
  done
  [ "$CHECK_FAILURES" -eq 0 ] && ok "Required host ports are free"
}

preflight() {
  CHECK_FAILURES=0
  check_docker
  check_tools
  # Compose-dependent checks only make sense once Docker works.
  if [ "$CHECK_FAILURES" -eq 0 ]; then
    check_env
    [ "$CHECK_FAILURES" -eq 0 ] && check_ports
  fi
  if [ "$CHECK_FAILURES" -gt 0 ]; then
    fail "$CHECK_FAILURES preflight check(s) failed; nothing was started."
    return 1
  fi
  return 0
}

# Compose mounts these .env values as files under /run/secrets/dokene (issue 308): only the migrate job gets the
# migration password, and neither it nor the signing key is in any container's environment. Compose refuses
# environment-sourced secrets for read-only services, so they are written to a directory only the owner can list. The
# files are world-readable because the containers run as uid 10001; the directory mode keeps other host users out.
# Values and the directory come from Compose's own interpolation environment (shell over .env, quotes resolved), so the
# files always match what PostgreSQL and compose.yaml (DOKENE_SECRETS_DIR, default ./.secrets) see.
#
# resolve_secret_files: sets SECRETS_DIR and SECRETS_ENVIRONMENT; creates the directory (mode 0700) when missing and
# refuses an existing one that is not the user's own or that grants group or other users any access (for example /tmp,
# or a 0755 directory, through which others could read the 0444 files), never chmod-ing it.
resolve_secret_files() {
  local mode owner
  SECRETS_ENVIRONMENT="$(compose config --environment)" \
    || { fail "docker compose could not resolve .env for the secret files."; return 1; }
  SECRETS_DIR="$(compose_variable "$SECRETS_ENVIRONMENT" DOKENE_SECRETS_DIR)"
  SECRETS_DIR="${SECRETS_DIR:-./.secrets}"
  case "$SECRETS_DIR" in /*) ;; *) SECRETS_DIR="$ROOT_DIR/${SECRETS_DIR#./}" ;; esac
  if [ ! -e "$SECRETS_DIR" ]; then
    ( umask 077 && mkdir -p "$SECRETS_DIR" ) || { fail "Cannot create $SECRETS_DIR."; return 1; }
    return 0
  fi
  mode="$(stat -c %a "$SECRETS_DIR" 2>/dev/null || stat -f %Lp "$SECRETS_DIR" 2>/dev/null)"
  owner="$(stat -c %u "$SECRETS_DIR" 2>/dev/null || stat -f %u "$SECRETS_DIR" 2>/dev/null)"
  if [ ! -d "$SECRETS_DIR" ] || [ "$owner" != "$(id -u)" ] || [ $(( 8#${mode:-777} & 8#077 )) -ne 0 ]; then
    fail "DOKENE_SECRETS_DIR ($SECRETS_DIR) must be a directory of your own with mode 0700 (no group or other access); use a dedicated one."
    return 1
  fi
}

# secret_files_changed: true when a value differs from its existing file. Compose does not recreate a container for that
# (secret contents are not part of its configuration hash). A missing file is not a change (first start). Values are
# compared through stdin, never on the command line.
secret_files_changed() {
  local name
  for name in DOKENE_DB_MIGRATION_PASSWORD DOKENE_TENANT_CONTEXT_SIGNING_KEY; do
    [ -f "$SECRETS_DIR/$name" ] || continue
    printf '%s' "$(compose_variable "$SECRETS_ENVIRONMENT" "$name")" | cmp -s - "$SECRETS_DIR/$name" || return 0
  done
  return 1
}

write_secret_files() {
  local name
  for name in DOKENE_DB_MIGRATION_PASSWORD DOKENE_TENANT_CONTEXT_SIGNING_KEY; do
    rm -f "$SECRETS_DIR/$name"
    printf '%s' "$(compose_variable "$SECRETS_ENVIRONMENT" "$name")" > "$SECRETS_DIR/$name"
    chmod 444 "$SECRETS_DIR/$name"
  done
}

# refresh_secret_files: resolves, and when a secret changed removes migrate and backend first so the next `up` recreates
# both with the new files; otherwise the job would re-provision a signing key the running backend no longer uses. The
# files are rewritten only after that removal succeeded, so a failed removal is detected again on the next run.
refresh_secret_files() {
  resolve_secret_files || return 1
  if secret_files_changed; then
    warn "A secret changed in .env since the last start; recreating migrate and backend."
    compose rm -f -s migrate backend >/dev/null \
      || { fail "Could not remove the migrate and backend containers; the secret files were left unchanged."; return 1; }
  fi
  write_secret_files
}

# compose_variable ENVIRONMENT NAME: the value of NAME in `docker compose config --environment` output (KEY=VALUE lines).
compose_variable() {
  printf '%s\n' "$1" | grep -m 1 "^$2=" | cut -d= -f2-
}

# ------------------------------------------------------------------------------
# Commands
# ------------------------------------------------------------------------------
print_summary() {
  local kc_port
  kc_port="$(env_get KEYCLOAK_PORT)"; kc_port="${kc_port:-8081}"
  info ""
  info "${BOLD}Dokene local environment${RESET}"
  if [ "${INFRA_ONLY:-false}" = "true" ]; then
    local db_port
    db_port="$(env_get DOKENE_DB_HOST_PORT)"
    info "  PostgreSQL:  localhost:${db_port:-5432}"
    info "  Keycloak:    http://localhost:$kc_port  (admin console; realm: dokene)"
    info "  Backend and frontend were NOT started (--infra-only); run them from the host:"
    info "    set -a; . ./.env; set +a; (cd backend && ./gradlew migrateDb bootRun)    # JDK 26"
    info "    (cd frontend && npm ci && npm run dev)                         # Node 24"
  else
    info "  Application: http://localhost:$FRONTEND_PORT"
    info "  Backend API: http://localhost:$BACKEND_PORT   (BFF; OIDC callback)"
    info "  Keycloak:    http://localhost:$kc_port  (admin console; realm: dokene)"
    info "  Test users:  testuser (OWNER), testoperator (OPERATOR), testviewer (VIEWER)"
    info "               passwords: DOKENE_TEST_OWNER_PASSWORD, DOKENE_TEST_OPERATOR_PASSWORD and"
    info "               DOKENE_TEST_VIEWER_PASSWORD in .env (generated, no default)"
    [ "${SEEDED:-false}" = "true" ] \
      || info "               (run './scripts/dev-env.sh seed' once to create the QA workspace and memberships)"
  fi
  info "  Stop:        ./scripts/dev-env.sh down"
}

report_failure() {
  if [ "${START_FAILED:-false}" = "true" ]; then
    fail "Containers failed to start (see the Docker error above); this is not a health-check timeout."
  else
    fail "The environment did not become healthy within ${WAIT_TIMEOUT}s."
  fi
  compose ps || true
  local svc
  for svc in $(compose ps --services --filter status=exited 2>/dev/null) \
             $(compose ps --services --filter health=unhealthy 2>/dev/null); do
    warn "Last log lines of '$svc':"
    compose logs --no-color --tail 25 "$svc" 2>&1 | sed 's/^/    /'
  done
  if compose logs --no-color --tail 200 migrate backend 2>/dev/null | grep -q 'password authentication failed'; then
    warn "The backend or migrate job cannot authenticate against PostgreSQL: the data volume was created with different passwords"
    warn "than the current .env. Restore the original .env, or run './scripts/dev-env.sh reset' (deletes local data)."
  fi
  if compose logs --no-color --tail 200 backend 2>/dev/null | grep -q 'Run the migration job'; then
    warn "The backend refused an outdated schema; check './scripts/dev-env.sh logs migrate' (issue 308)."
  fi
  info "Full logs: ./scripts/dev-env.sh logs <service>"
  info "Fix the cause and rerun './scripts/dev-env.sh up'; if the stack still misbehaves, run 'down' and then 'up'."
}

# drop_services SERVICE...: removes the (stopped or broken) containers so the next full startup recreates them with
# their networks. Running healthy containers and volumes are untouched.
drop_services() {
  warn "Removing containers so they are recreated: $*"
  compose rm -f -s "$@" >/dev/null 2>&1 || true
}

# A start failure (e.g. a host port bind error) leaves the container created but not attached to any network, and
# a later `up` would start it as is.
discard_unstarted_containers() {
  local stale
  stale="$(compose ps --all --services --filter status=created 2>/dev/null)"
  [ -n "$stale" ] || return 0
  # shellcheck disable=SC2086
  drop_services $stale
}

# is_start_failure_log FILE: true when the `compose up` output shows a container start/network error (as opposed to
# a pull, build or health-check failure).
is_start_failure_log() {
  grep -Eqi 'port is already allocated|Bind for|failed to set up container networking|driver failed programming|failed to (start|create)' "$1"
}

# networkless_containers: reads "name<TAB>status<TAB>network count" lines on stdin and prints the names that are
# created or running with no network. A stopped container keeps its network entries, so it is never reported.
networkless_containers() {
  awk -F'\t' '($2 == "created" || $2 == "running") && $3 == 0 { print $1 }'
}

# repair_networkless_bridge: an oidc-bridge left without a network by an earlier failed `up` (before the cleanup
# above existed, or after a crash) never recovers on its own. Remove it with its backend and let the full startup that
# follows recreate both: that startup also starts postgres and keycloak, which may be stopped (e.g. after a reboot).
repair_networkless_bridge() {
  local id
  id="$(compose ps --all -q oidc-bridge 2>/dev/null | head -n 1)"
  [ -n "$id" ] || return 0
  [ -n "$(docker inspect --format '{{.Name}}	{{.State.Status}}	{{len .NetworkSettings.Networks}}' "$id" 2>/dev/null | networkless_containers)" ] \
    || return 0
  warn "oidc-bridge is attached to no network (left over from a failed 'up')."
  drop_services oidc-bridge backend
}

# compose_up_services NO_BUILD EXTRA_FLAGS [service...]: starts (and, unless NO_BUILD=true, builds) the given
# services, or all of them when none are named; waits for health and prints diagnostics on failure.
compose_up_services() {
  local no_build="$1" extra="$2" build_flag="--build"
  shift 2
  [ "$no_build" = "true" ] && build_flag=""
  local log status
  log="$(mktemp)"
  # $build_flag and $extra are intentionally word-split: either may be empty or hold several flags.
  # shellcheck disable=SC2086
  compose up -d $build_flag $extra --wait --wait-timeout "$WAIT_TIMEOUT" "$@" 2>&1 | tee "$log"
  status="${PIPESTATUS[0]}"
  if [ "$status" -ne 0 ]; then
    # Bind/create errors fail immediately; only a real timeout deserves the "did not become healthy" wording.
    if is_start_failure_log "$log"; then
      START_FAILED=true
      discard_unstarted_containers
    fi
    rm -f "$log"
    report_failure
    exit 1
  fi
  rm -f "$log"
}

cmd_up() {
  WANT_SEED=false; INFRA_ONLY=false; NO_BUILD=false; ASSUME_YES=false
  while [ $# -gt 0 ]; do
    case "$1" in
      --seed) WANT_SEED=true ;;
      --infra-only) INFRA_ONLY=true ;;
      --no-build) NO_BUILD=true ;;
      --yes|-y) ASSUME_YES=true ;;
      *) die "Unknown option for 'up': $1" ;;
    esac
    shift
  done

  preflight || exit 1
  # Written only by the commands that start containers, so `doctor` cannot consume a change before `up` acts on it.
  refresh_secret_files || exit 1

  [ "$INFRA_ONLY" = "true" ] || repair_networkless_bridge

  local services=""
  [ "$INFRA_ONLY" = "true" ] && services="postgres keycloak"

  info "Starting containers (the first build downloads images and dependencies and can take several minutes)..."
  # shellcheck disable=SC2086
  compose_up_services "$NO_BUILD" "" $services
  ok "All requested containers are healthy"
  compose ps

  # --import-realm skips a realm Keycloak already holds; bring its settings (issues 307 and 314) up to date. The
  # script exits 3 whenever it did not set every test user password, a failure before the password step included,
  # and that fails `up`: old passwords left in place would surface later as a vaguer seed or login failure. Any other
  # status only warns.
  local apply_status=0
  "$ROOT_DIR/scripts/keycloak-apply-realm-settings.sh" || apply_status=$?
  case "$apply_status" in
    0) ;;
    3) die "Keycloak test user passwords were not set from .env (see the error above); fix it and rerun './scripts/dev-env.sh up'." ;;
    *) warn "Keycloak realm settings not applied; run scripts/keycloak-apply-realm-settings.sh." ;;
  esac

  if [ "$WANT_SEED" = "true" ]; then
    if [ "$INFRA_ONLY" = "true" ]; then
      warn "--seed needs the backend; skipping because --infra-only was given."
    else
      cmd_seed && SEEDED=true
    fi
  fi
  print_summary
}

# Recreates the backend together with its oidc-bridge sidecar, whose network namespace the backend joins. The
# backend is recreated routinely; --no-deps leaves postgres and keycloak untouched. Recreating both also repairs a
# backend left attached to a replaced bridge. Use it after changing settings read only by the backend (e.g.
# DOKENE_AI_*); values shared with Keycloak need a full `up`.
cmd_restart_backend() {
  NO_BUILD=false; ASSUME_YES=false
  while [ $# -gt 0 ]; do
    case "$1" in
      --no-build) NO_BUILD=true ;;
      --yes|-y) ASSUME_YES=true ;;
      *) die "Unknown option for 'restart-backend': $1" ;;
    esac
    shift
  done

  preflight || exit 1
  # --force-recreate below already picks up changed secret files.
  { resolve_secret_files && write_secret_files; } || exit 1

  # The one-shot migrate job (issue 308) runs first so a rebuilt backend never starts against an older schema.
  info "Running migrations and recreating backend and oidc-bridge..."
  compose_up_services "$NO_BUILD" "--force-recreate --no-deps" migrate oidc-bridge backend
  ok "Migrations applied; backend and oidc-bridge are healthy"
  compose ps backend oidc-bridge
}

cmd_down() {
  have docker || die "Docker is not installed."
  docker info >/dev/null 2>&1 || die "Docker daemon is not reachable."
  compose_teardown down --remove-orphans
  ok "Containers stopped and removed. Database and Keycloak data are kept (use '$0 reset' to wipe them)."
}

cmd_reset() {
  ASSUME_YES=false
  while [ $# -gt 0 ]; do
    case "$1" in --yes|-y) ASSUME_YES=true ;; *) die "Unknown option for 'reset': $1" ;; esac
    shift
  done
  have docker || die "Docker is not installed."
  docker info >/dev/null 2>&1 || die "Docker daemon is not reachable."
  warn "This deletes the local PostgreSQL and Keycloak data volumes of this checkout."
  confirm "Continue?" || die "Aborted."
  compose_teardown down --volumes --remove-orphans
  ok "Environment reset. Run '$0 up --seed' to recreate it."
}

cmd_status() {
  have docker || die "Docker is not installed."
  docker info >/dev/null 2>&1 || die "Docker daemon is not reachable."
  compose_teardown ps
  local kc_port
  kc_port="$(env_get KEYCLOAK_PORT)"; kc_port="${kc_port:-8081}"
  info ""
  http_status "Keycloak discovery" "http://localhost:$kc_port/realms/dokene/.well-known/openid-configuration" 200
  http_status "Backend (BFF, 401 without session)" "http://localhost:$BACKEND_PORT/api/session" 401
  http_status "Frontend" "http://localhost:$FRONTEND_PORT/" 200
}

http_status() {
  local label="$1" url="$2" expected="$3" code
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$url" 2>/dev/null || true)"
  if [ "$code" = "$expected" ]; then ok "$label -> $code"; else warn "$label -> ${code:-no response} (expected $expected)"; fi
}

cmd_logs() {
  have docker || die "Docker is not installed."
  compose logs --tail 100 -f "$@"
}

cmd_seed() {
  have jq || die "jq is required by scripts/seed-local-qa.sh (macOS: brew install jq)."
  have curl || die "curl is required."
  local args=""
  [ "${1:-}" = "--verify" ] && args="--verify"
  # shellcheck disable=SC2086
  "$ROOT_DIR/scripts/seed-local-qa.sh" $args
}

cmd_doctor() {
  ASSUME_YES=false
  preflight && ok "Preflight passed: the environment can be started." || exit 1
}

# ------------------------------------------------------------------------------
# Entry point
# ------------------------------------------------------------------------------
# Sourcing the file (smoke tests) defines the functions without running a command.
# `return` only succeeds when sourced; `|| true` keeps a direct run going, which ShellCheck reads as unreachable.
# shellcheck disable=SC2317
if [ "${BASH_SOURCE[0]}" != "$0" ]; then return 0 2>/dev/null || true; fi
command="${1:-help}"
[ $# -gt 0 ] && shift
case "$command" in
  up|start)     cmd_up "$@" ;;
  down|stop)    cmd_down ;;
  restart)      cmd_down && cmd_up "$@" ;;
  restart-backend) cmd_restart_backend "$@" ;;
  status)       cmd_status ;;
  logs)         cmd_logs "$@" ;;
  seed)         cmd_seed "$@" ;;
  doctor)       cmd_doctor ;;
  reset)        cmd_reset "$@" ;;
  help|-h|--help) usage ;;
  *) fail "Unknown command: $command"; usage; exit 2 ;;
esac
