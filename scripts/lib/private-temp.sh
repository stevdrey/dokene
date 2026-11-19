# shellcheck shell=bash
# Private temporary storage for local verification scripts (issue 328). Source it, then:
#
#   private_temp_dir VAR   creates a new directory only the current user can open (mode 700, under $TMPDIR or /tmp),
#                          stores its path in VAR and removes it, with everything in it, when the shell exits: at the
#                          end of the script, on `exit` or an error, and on HUP, INT or TERM (the signal is then raised
#                          again, so the script still ends the way that signal ends it).
#   private_temp_write F   writes standard input to F with mode 600 (umask 077), replacing F. Use it for files inside
#                          the private directory, so a file is never readable by others even for an instant. It never
#                          writes through a file or link that appears at F while it runs (noclobber).
#
# The helper owns the shell's EXIT, HUP, INT and TERM traps. It refuses (status 2, nothing created) when another trap is
# already set on any of them, rather than replace it silently, and when VAR starts with `_private_temp_`. A signal the
# shell ignores (started with nohup, or as a background job) stays ignored and is not a refusal. Call
# private_temp_dir in the script's own shell, not inside `$(...)` or a pipeline: a subshell would remove the directory
# as soon as it ends.
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
    kill -s "$sig" "$BASHPID"
}

private_temp_dir() {
    local _private_temp_name="${1:-}" _private_temp_new _private_temp_trap _private_temp_sig
    if ! [[ "$_private_temp_name" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || [[ "$_private_temp_name" == _private_temp_* ]]; then
        printf 'private_temp_dir: a variable name is required (not one starting with _private_temp_)\n' >&2
        return 2
    fi
    _private_temp_trap="$(trap -p EXIT)"
    if [ -n "$_private_temp_trap" ] && [ "$_private_temp_trap" != "trap -- '_private_temp_cleanup' EXIT" ]; then
        printf 'private_temp_dir: refusing to replace an existing EXIT trap; nothing created\n' >&2
        return 2
    fi
    for _private_temp_sig in HUP INT TERM; do
        _private_temp_trap="$(trap -p "$_private_temp_sig")"
        # An ignored signal (nohup, a background job) is left ignored: it ends nothing, so there is nothing to clean.
        if [ -n "$_private_temp_trap" ] && [ "$_private_temp_trap" != "trap -- '' SIG$_private_temp_sig" ] \
            && [ "$_private_temp_trap" != "trap -- '_private_temp_signal $_private_temp_sig' SIG$_private_temp_sig" ]; then
            printf 'private_temp_dir: refusing to replace an existing %s trap; nothing created\n' "$_private_temp_sig" >&2
            return 2
        fi
    done
    # The traps come first, so a signal that arrives once the directory exists always removes it.
    trap '_private_temp_cleanup' EXIT
    for _private_temp_sig in HUP INT TERM; do
        if [ "$(trap -p "$_private_temp_sig")" != "trap -- '' SIG$_private_temp_sig" ]; then
            # shellcheck disable=SC2064 # the signal name is fixed when the trap is set
            trap "_private_temp_signal $_private_temp_sig" "$_private_temp_sig"
        fi
    done
    # One command creates and records the directory, so no trap can run between the two.
    if ! _PRIVATE_TEMP_DIRS+=("$(umask 077 && mktemp -d "${TMPDIR:-/tmp}/dokene-private.XXXXXXXX")"); then
        unset '_PRIVATE_TEMP_DIRS[-1]'
        printf 'private_temp_dir: could not create a temporary directory\n' >&2
        return 2
    fi
    _private_temp_new="${_PRIVATE_TEMP_DIRS[-1]}"
    printf -v "$_private_temp_name" '%s' "$_private_temp_new"
}

private_temp_write() {
    local file="${1:-}"
    [ -n "$file" ] || { printf 'private_temp_write: a file is required\n' >&2; return 2; }
    (
        umask 077
        rm -f -- "$file" || exit
        set -o noclobber
        cat > "$file"
    )
}
