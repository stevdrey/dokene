# shellcheck shell=bash
# Private temporary storage for local verification scripts (issue 328). Source it, then:
#
#   private_temp_dir VAR   creates a new directory only the current user can open (mode 700, under $TMPDIR or /tmp),
#                          stores its path in VAR and removes it, with everything in it, when the shell exits: at the
#                          end of the script, on `exit` or an error, and on HUP, INT or TERM (the signal is then raised
#                          again, so the script still ends the way that signal ends it).
#   private_temp_write F   writes standard input to F with mode 600 (umask 077), replacing F. Use it for files inside
#                          the private directory, so a file is never readable by others even for an instant.
#
# The helper owns the shell's EXIT, HUP, INT and TERM traps. It refuses (status 2, nothing created) when another EXIT
# trap is already set, rather than replace it silently. Call private_temp_dir in the script's own shell, not inside
# `$(...)` or a pipeline: a subshell would remove the directory as soon as it ends.
#
# Prefer not writing a credential at all; this is for what must be written (a Compose override, a cookie jar, a
# captured response).

_PRIVATE_TEMP_DIRS=()

_private_temp_cleanup() {
    if [ "${#_PRIVATE_TEMP_DIRS[@]}" -gt 0 ]; then
        rm -rf -- "${_PRIVATE_TEMP_DIRS[@]}"
    fi
    _PRIVATE_TEMP_DIRS=()
}

_private_temp_signal() {
    local sig="$1"
    _private_temp_cleanup
    trap - "$sig" EXIT
    kill -s "$sig" "$$"
}

private_temp_dir() {
    local var="${1:-}" dir existing
    if ! [[ "$var" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]]; then
        printf 'private_temp_dir: a variable name is required\n' >&2
        return 2
    fi
    existing="$(trap -p EXIT)"
    if [ -n "$existing" ] && [ "$existing" != "trap -- '_private_temp_cleanup' EXIT" ]; then
        printf 'private_temp_dir: refusing to replace an existing EXIT trap; nothing created\n' >&2
        return 2
    fi
    dir="$(umask 077 && mktemp -d "${TMPDIR:-/tmp}/dokene-private.XXXXXXXX")" || {
        printf 'private_temp_dir: could not create a temporary directory\n' >&2
        return 2
    }
    if ! chmod 700 -- "$dir"; then
        rm -rf -- "$dir"
        printf 'private_temp_dir: could not restrict the temporary directory\n' >&2
        return 2
    fi
    _PRIVATE_TEMP_DIRS+=("$dir")
    trap '_private_temp_cleanup' EXIT
    trap '_private_temp_signal HUP' HUP
    trap '_private_temp_signal INT' INT
    trap '_private_temp_signal TERM' TERM
    printf -v "$var" '%s' "$dir"
}

private_temp_write() {
    local file="${1:-}"
    [ -n "$file" ] || { printf 'private_temp_write: a file is required\n' >&2; return 2; }
    (
        umask 077
        rm -f -- "$file" && cat > "$file"
    )
}
