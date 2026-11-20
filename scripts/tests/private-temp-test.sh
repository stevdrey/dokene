#!/usr/bin/env bash
# Offline tests for scripts/lib/private-temp.sh and its use by the RES-01 evidence script (issue 328): temporary files
# are private (directory 700, files 600) and removed on a normal exit, an error and an interrupt, and the RES-01
# Compose override never holds the provider key. Only synthetic values are used; a stub docker on PATH stands in for
# the stack, so no network, Docker or real provider key is needed.
# The single-quoted $ below is meant for the child shells and the generated harness, not this one.
# shellcheck disable=SC2016
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LIB="$ROOT_DIR/scripts/lib/private-temp.sh"
RES01="$ROOT_DIR/docs/verification/issue-123-evidence/scripts/res01.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

FAILURES=0
pass() { printf 'ok   %s\n' "$1"; }
fail() { printf 'FAIL %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
assert() { local name="$1"; shift; if "$@"; then pass "$name"; else fail "$name"; fi; }
refute() { local name="$1"; shift; if "$@"; then fail "$name"; else pass "$name"; fi; }
mode_is() { [ "$(stat -c '%a' -- "$2")" = "$1" ]; }
absent() { [ ! -e "$1" ]; }
empty_dir() { [ -z "$(ls -A -- "$1")" ]; }

# A value shaped like a real key, generated for this run only. It must never reach an override file or any output.
SYNTHETIC_KEY="sk-proj-$(od -An -N12 -tx1 /dev/urandom | tr -d ' \n')"

# Each child shell gets its own TMPDIR, so what it leaves behind is easy to see.
child_tmp() { local d="$WORK/tmp-$1"; mkdir -p "$d"; printf '%s' "$d"; }

# --- directory and file permissions
T="$(child_tmp perms)"
OUT="$(TMPDIR="$T" bash -c '
    umask 000; . "$1"
    private_temp_dir D || exit 9
    printf "%s\n" "$2" | private_temp_write "$D/secret"
    printf "old\n" > "$D/existing"; chmod 644 "$D/existing"
    printf "%s\n" "$2" | private_temp_write "$D/existing"
    stat -c "%a %n" "$D" "$D/secret" "$D/existing"
    printf "content=%s\n" "$(cat "$D/existing")"
' _ "$LIB" "$SYNTHETIC_KEY")"
DIR_LINE="$(sed -n 1p <<< "$OUT")"
assert "the private directory is created under TMPDIR" test "${DIR_LINE#* }" != "${DIR_LINE#* "$T"/dokene-private.}"
assert "the private directory has mode 700 even with umask 000" test "${DIR_LINE%% *}" = 700
assert "a written file has mode 600 even with umask 000" test "$(sed -n 2p <<< "$OUT" | cut -d' ' -f1)" = 600
assert "replacing a readable file leaves mode 600" test "$(sed -n 3p <<< "$OUT" | cut -d' ' -f1)" = 600
assert "the replaced file holds the new content" test "$(sed -n 4p <<< "$OUT")" = "content=$SYNTHETIC_KEY"
assert "the directory is removed when the shell ends" empty_dir "$T"

# --- cleanup on normal and error exits
for case in "normal end|0|:" "explicit exit|3|exit 3" "set -e failure|1|set -e; false"; do
    IFS='|' read -r name want body <<< "$case"
    T="$(child_tmp "exit-$want")"
    TMPDIR="$T" bash -c '
        . "$1"; private_temp_dir D || exit 9
        printf "%s\n" "$2" | private_temp_write "$D/ov.yaml"
        eval "$3"
    ' _ "$LIB" "$SYNTHETIC_KEY" "$body"
    got=$?
    assert "$name keeps exit status $want" test "$got" = "$want"
    assert "$name removes the private directory" empty_dir "$T"
done

# --- cleanup on interrupts: the directory is removed and the signal still ends the script.
# Job control is on so the background child does not start with SIGINT ignored (a non-interactive shell ignores it
# for background jobs, and an ignored signal cannot be trapped).
set -m
for sig in HUP INT TERM; do
    T="$(child_tmp "sig-$sig")"
    READY="$WORK/ready-$sig"
    TMPDIR="$T" bash -c '
        . "$1"; private_temp_dir D || exit 9
        printf "%s\n" "$2" | private_temp_write "$D/ov.yaml"
        printf "%s\n" "$D" > "$3"
        while :; do sleep 0.05; done
    ' _ "$LIB" "$SYNTHETIC_KEY" "$READY" &
    pid=$!
    for _ in $(seq 1 100); do [ -s "$READY" ] && break; sleep 0.05; done
    assert "$sig: the directory exists before the signal" test -d "$(cat "$READY" 2>/dev/null)"
    kill -s "$sig" "$pid"
    # A bounded wait: a script that ignores the signal fails here instead of hanging the test.
    for _ in $(seq 1 100); do kill -0 "$pid" 2>/dev/null || break; sleep 0.05; done
    if kill -0 "$pid" 2>/dev/null; then
        kill -s KILL "$pid" 2>/dev/null
        fail "$sig: the script ends within 5 s"
    else
        pass "$sig: the script ends within 5 s"
    fi
    { wait "$pid"; } 2>/dev/null
    got=$?
    assert "$sig: the script still ends by the signal" test "$got" = "$((128 + $(kill -l "$sig")))"
    assert "$sig: the private directory is removed" empty_dir "$T"
done
set +m

# --- refusals create nothing
T="$(child_tmp refuse-trap)"
OUT="$(TMPDIR="$T" bash -c '. "$1"; trap "echo other" EXIT; private_temp_dir D; echo "status=$?"' _ "$LIB" 2>&1)"
assert "an existing EXIT trap is not replaced" grep -q 'refusing to replace an existing EXIT trap' <<< "$OUT"
assert "the refusal returns status 2" grep -q 'status=2' <<< "$OUT"
assert "the refusal creates no directory" empty_dir "$T"
T="$(child_tmp refuse-name)"
OUT="$(TMPDIR="$T" bash -c '. "$1"; private_temp_dir "x;y"; echo "status=$?"' _ "$LIB" 2>&1)"
assert "an invalid variable name is refused" grep -q 'status=2' <<< "$OUT"
assert "an invalid variable name creates no directory" empty_dir "$T"
T="$(child_tmp refuse-int)"
OUT="$(TMPDIR="$T" bash -c '. "$1"; trap "echo own" INT; private_temp_dir D; echo "status=$?"; trap -p INT' _ "$LIB" 2>&1)"
assert "an existing INT trap is not replaced" grep -q 'refusing to replace an existing INT trap' <<< "$OUT"
assert "the INT refusal returns status 2 and keeps the trap" test "$(grep -c 'status=2\|echo own' <<< "$OUT")" = 2
assert "the INT refusal creates no directory" empty_dir "$T"
for sig in HUP INT; do
    T="$(child_tmp "ignored-$sig")"
    # The outer shell ignores the signal, so the script starts with it ignored, as under nohup or as a background job.
    OUT="$(TMPDIR="$T" bash -c 'trap "" "$2"; bash -c ". \"\$1\"; private_temp_dir D; echo \"status=\$?\"; trap -p \"\$2\"" _ "$1" "$2"' _ "$LIB" "$sig" 2>&1)"
    assert "a script started with $sig ignored still gets a private directory" grep -q 'status=0' <<< "$OUT"
    assert "a script started with $sig ignored keeps it ignored" grep -qx "trap -- '' SIG$sig" <<< "$OUT"
    assert "a script started with $sig ignored removes the directory on exit" empty_dir "$T"
done
T="$(child_tmp refuse-own-prefix)"
OUT="$(TMPDIR="$T" bash -c '. "$1"; private_temp_dir _private_temp_x; echo "status=$?"' _ "$LIB" 2>&1)"
assert "a name in the helper's own prefix is refused" grep -q 'status=2' <<< "$OUT"
assert "a refused own-prefix name creates no directory" empty_dir "$T"
for name in dir var existing new name; do
    T="$(child_tmp "name-$name")"
    OUT="$(TMPDIR="$T" bash -c '. "$1"; private_temp_dir "$2" && eval "printf \"%s\n\" \"\$$2\""' _ "$LIB" "$name" 2>&1)"
    assert "a caller variable named $name receives the private directory" grep -q "^$T/dokene-private\." <<< "$OUT"
done
T="$(child_tmp symlink)"
VICTIM="$WORK/victim"; printf 'untouched\n' > "$VICTIM"
TMPDIR="$T" bash -c '. "$1"; private_temp_dir D; ln -s "$2" "$D/ov.yaml"; printf "%s\n" "$3" | private_temp_write "$D/ov.yaml"
    [ -f "$D/ov.yaml" ] && [ ! -L "$D/ov.yaml" ] && echo replaced' _ "$LIB" "$VICTIM" "$SYNTHETIC_KEY" > "$WORK/symlink.out" 2>&1
assert "a link at the target is replaced by a regular file" grep -q '^replaced$' "$WORK/symlink.out"
assert "the link's target is not written" test "$(cat "$VICTIM")" = untouched
T="$(child_tmp twice)"
OUT="$(TMPDIR="$T" bash -c '. "$1"; private_temp_dir A && private_temp_dir B && echo both' _ "$LIB" 2>&1)"
assert "a second private directory may be created" grep -q '^both$' <<< "$OUT"
assert "both private directories are removed" empty_dir "$T"

# --- the RES-01 script as written: its override is private, never holds the key, and is removed afterwards.
# Its recreate() and every line between case_run() and the final restore run with a stub docker, in a folder whose
# .env holds the synthetic key; probe and waitup are not needed for the override.
LOADER_LINE="$(grep -n 'scripts/lib/private-temp.sh' "$RES01" | head -1 | cut -d: -f1)"
FIRST_CD="$(grep -n '^cd ' "$RES01" | head -1 | cut -d: -f1)"
assert "res01.sh loads the helper before its first cd" test -n "$LOADER_LINE" -a -n "$FIRST_CD" -a "${LOADER_LINE:-0}" -lt "${FIRST_CD:-0}"
assert "res01.sh's loader resolves from its own location" test -f "$(dirname "$RES01")/../../../../scripts/lib/private-temp.sh"
refute "res01.sh does not read .env" grep -q '\.env' <(grep -v '^#' "$RES01" | sed 's/original \.env configuration//')
assert "res01.sh keeps its override in the private directory" grep -q 'private_temp_dir QA_PRIVATE || exit 2; OV=\$QA_PRIVATE/ov.yaml' "$RES01"
RES04B="$(dirname "$RES01")/res04b.sh"
LOADER_LINE="$(grep -n 'scripts/lib/private-temp.sh' "$RES04B" | head -1 | cut -d: -f1)"
FIRST_CD="$(grep -n '^cd ' "$RES04B" | head -1 | cut -d: -f1)"
assert "res04b.sh loads the helper before its first cd" test -n "$LOADER_LINE" -a -n "$FIRST_CD" -a "${LOADER_LINE:-0}" -lt "${FIRST_CD:-0}"
assert "res04b.sh keeps its override in the private directory" grep -q 'private_temp_dir QA_PRIVATE || exit 2; OV=\$QA_PRIVATE/ov2.yaml' "$RES04B"
assert "res04b.sh parses" bash -n "$RES04B"

STUB="$WORK/stub"; mkdir -p "$STUB"
cat > "$STUB/docker" <<'EOF'
#!/usr/bin/env bash
# Records each `docker compose up` with the override it was given, its mode and its directory's mode.
ov="${COMPOSE_FILE#compose.yaml:}"
{
    printf '=== up dir=%s file=%s\n' "$(stat -c '%a' -- "$(dirname -- "$ov")")" "$(stat -c '%a' -- "$ov")"
    cat -- "$ov"
} >> "$RES01_LOG"
EOF
chmod +x "$STUB/docker"

RUN="$WORK/res01-run"; mkdir -p "$RUN"
printf 'DOKENE_AI_OPENAI_API_KEY=%s\n' "$SYNTHETIC_KEY" > "$RUN/.env"
HARNESS="$WORK/res01-harness.sh"
{
    printf '. %q || exit 9\n' "$LIB"
    printf 'umask 022\n'
    printf 'private_temp_dir QA_PRIVATE || exit 2; OV=$QA_PRIVATE/ov.yaml; printf "%%s\\n" "$QA_PRIVATE" > %q\n' "$WORK/res01-dir"
    grep '^recreate()' "$RES01"
    printf 'case_run() { recreate "$2"; }\n'
    awk 'on && /^echo "== restore/{on=0} on; /^case_run\(\)/{on=1}' "$RES01"
} > "$HARNESS"
LOG="$WORK/res01.log"; : > "$LOG"
( cd "$RUN" && PATH="$STUB:$PATH" RES01_LOG="$LOG" TMPDIR="$(child_tmp res01)" DOKENE_AI_OPENAI_API_KEY="$SYNTHETIC_KEY" bash "$HARNESS" )
assert "the RES-01 harness ran" test "$?" = 0
assert "every RES-01 case recreated the backend (8 cases)" test "$(grep -c '^=== up ' "$LOG")" = 8
assert "every RES-01 override was in a mode 700 directory with mode 600" test "$(grep -c '^=== up dir=700 file=600$' "$LOG")" = 8
refute "no RES-01 override holds the synthetic key" grep -qF "$SYNTHETIC_KEY" "$LOG"
refute "no RES-01 override asks Compose to interpolate a value" grep -q '\$' "$LOG"
assert "cases 4b and 7 still select the OpenAI provider" test "$(grep -ciE 'DOKENE_AI_PROVIDER: "openai"' "$LOG")" = 4
assert "only cases 5 and 6 set a key, both synthetic" test "$(grep -c 'DOKENE_AI_OPENAI_API_KEY' "$LOG")" = 2
assert "the RES-01 private directory is removed" absent "$(cat "$WORK/res01-dir")"

# --- static checks
assert "the helper parses" bash -n "$LIB"
assert "res01.sh parses" bash -n "$RES01"

if [ "$FAILURES" -gt 0 ]; then
    printf '%d test(s) failed\n' "$FAILURES"
    exit 1
fi
printf 'all tests passed\n'
