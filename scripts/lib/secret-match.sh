# shellcheck shell=bash
# ABOUTME: Literal searches for secrets (passwords, keys, client secrets) in logs and responses (issue 327).
# ABOUTME: Sourced by the verify scripts; the values never reach grep's command line.
#
#   grep_literals FILE VALUE...     prints the lines of FILE ("-" for stdin) that contain any VALUE literally
#   contains_literal FILE VALUE...  true when FILE ("-" for stdin) contains any VALUE literally
#
# Each value reaches grep as a line of a pattern file (process substitution), never as an argument, which any local
# user could read in the process table. Empty values are skipped, since an empty pattern matches every line; with no
# value left both functions return 1. contains_literal counts matches instead of using -q or discarding the output
# (GNU grep stops at the first match when its output is /dev/null), so grep reads its whole input and a writer piping
# into it under `set -o pipefail` never gets SIGPIPE and turns a match into a failed pipeline.

# _secret_match GREP_OPTION FILE VALUE...: runs grep -F GREP_OPTION over FILE with the non-empty VALUEs as patterns.
_secret_match() {
    local option="$1" file="$2" value
    local -a values=()
    shift 2
    for value in "$@"; do
        [ -n "$value" ] && values+=("$value")
    done
    [ "${#values[@]}" -gt 0 ] || return 1
    grep -F "$option" -f <(printf '%s\n' "${values[@]}") -- "$file"
}

grep_literals() {
    _secret_match -h "$@"  # -h: no file name prefix; the only option the printing form needs
}

contains_literal() {
    local count
    count="$(_secret_match -c "$@")"
    [ "${count:-0}" -gt 0 ] 2>/dev/null
}
