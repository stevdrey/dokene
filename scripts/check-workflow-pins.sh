#!/usr/bin/env bash
# ABOUTME: Fails when a GitHub Actions workflow references an action by anything but a full commit SHA plus its tag (issue #309, audit M5).
# ABOUTME: Line based scan over `uses:` lines; needs only bash, no network, no Docker. Rules: docs/security/github-actions-pinning.md.
#
# Usage: ./scripts/check-workflow-pins.sh [dir]     (default dir: .github/workflows)
# Exit 0 when every reference is pinned, 1 with one `<dir>/<file>:<line>: <reference>` line per violation,
# 2 when the directory holds no *.yml or *.yaml file, or when a directory cannot be read or `find` fails while
# collecting the files (issue #370). Local composite actions (action.yml, action.yaml) are scanned too: at any depth
# under the sibling `actions` directory of dir when it exists, and in any subdirectory of dir.
set -euo pipefail

DIR="${1:-.github/workflows}"
DIR="${DIR%/}"

USES_LINE='^[[:space:]]*(-[[:space:]]+)?("uses"|'\''uses'\''|uses)[[:space:]]*:[[:space:]]*(.*)$'
COMMENT_LINE='^[[:space:]]*#'
PINNED_VALUE='^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(/[^@[:space:]]+)?@[0-9a-f]{40}$'
TAG_COMMENT='^#[[:space:]]+[^[:space:]]+[[:space:]]*$'
# A local reference must name something this script reads: an action at any depth under .github/actions, or one
# workflow file directly under .github/workflows. No segment may start with a dot, which rules out `..`.
LOCAL_VALUE='^\./\.github/(actions(/[A-Za-z0-9_-][A-Za-z0-9_.-]*)+|workflows/[A-Za-z0-9_-][A-Za-z0-9_.-]*\.ya?ml)$'
DOCKER_DIGEST='@sha256:[0-9a-f]{64}$'

# Plain indexed arrays instead of associative ones, so the script also runs on the bash 3.2 that macOS ships.
violations=()      # "file<TAB>line<TAB>reference"
pinned_repos=()    # owner/repo in lowercase (GitHub resolves names case insensitively), parallel to pinned_shas
pinned_shas=()

sha_of_repo() {
    local i
    for i in ${pinned_repos[@]+"${!pinned_repos[@]}"}; do
        if [ "${pinned_repos[$i]}" = "$1" ]; then printf '%s' "${pinned_shas[$i]}"; return; fi
    done
}

# split_value REST: sets `value` (one pair of surrounding quotes removed) and `comment` (what follows the value).
split_value() {
    local rest="$1"
    if [[ $rest =~ ^\"([^\"]*)\"(.*)$ ]] || [[ $rest =~ ^\'([^\']*)\'(.*)$ ]]; then
        value="${BASH_REMATCH[1]}"
        comment="${BASH_REMATCH[2]}"
    elif [[ $rest =~ ^([^[:space:]]*)(.*)$ ]]; then
        value="${BASH_REMATCH[1]}"
        comment="${BASH_REMATCH[2]}"
    fi
    comment="${comment#"${comment%%[![:space:]]*}"}"   # drop leading whitespace
}

check_file() {
    local file="$1" lineno=0 line value comment repo sha first_sha
    while IFS= read -r line || [ -n "$line" ]; do
        lineno=$((lineno + 1))
        line="${line%$'\r'}"
        [[ $line =~ $COMMENT_LINE ]] && continue
        [[ $line =~ $USES_LINE ]] || continue
        value=''; comment=''
        split_value "${BASH_REMATCH[3]}"
        case "$value" in
            ./*)
                [[ $value =~ $LOCAL_VALUE ]] || violations+=("$file"$'\t'"$lineno"$'\t'"$value")
                continue ;;
            docker://*)
                [[ $value =~ $DOCKER_DIGEST ]] || violations+=("$file"$'\t'"$lineno"$'\t'"$value")
                continue ;;
        esac
        if [[ $value =~ $PINNED_VALUE ]] && [[ $comment =~ $TAG_COMMENT ]]; then
            # Rule 6: one SHA per repository across every file read. The first SHA seen (in file, then line order) is
            # the reference; every line carrying another SHA for the same repository is a violation.
            repo="$(printf '%s' "${value%%@*}" | tr '[:upper:]' '[:lower:]' | cut -d/ -f1,2)"
            sha="${value##*@}"
            first_sha="$(sha_of_repo "$repo")"
            if [ -z "$first_sha" ]; then
                pinned_repos+=("$repo"); pinned_shas+=("$sha")
            elif [ "$sha" != "$first_sha" ]; then
                violations+=("$file"$'\t'"$lineno"$'\t'"$value")
            fi
        else
            violations+=("$file"$'\t'"$lineno"$'\t'"$value")
        fi
    done < "$file"
}

[ -d "$DIR" ] || { echo "check-workflow-pins: $DIR is not a directory" >&2; exit 2; }

# Each `find` writes to files in a private directory instead of a process substitution, so that its exit status is
# seen: a directory find cannot read would otherwise leave files unchecked and the run green (issue #370).
# scripts/lib/private-temp.sh is not sourced: it needs bash 4 ($BASHPID, negative indexes) and this runs on 3.2.
WORK="$(mktemp -d "${TMPDIR:-/tmp}/check-workflow-pins.XXXXXX")" \
    || { echo "check-workflow-pins: cannot create a temporary directory" >&2; exit 2; }
trap 'rm -rf -- "$WORK"' EXIT

# With -L, find reports a symbolic link loop as an error that hides no file. These are the loop messages of GNU find
# (both wordings), bfs and BSD find under LC_ALL=C, after the program name as invoked (`find`, `/usr/bin/find`,
# `bfs: error:`). Any other line is fatal. An unreadable directory never depends on this list: collect tests it.
FIND_PREFIX='^[^[:space:]:]+: (error: )?'
DIR_LOOP="${FIND_PREFIX}(File system loop detected; .*|.*: Filesystem loop back to .*|.*: directory causes a cycle)\$"
# Too many levels of symbolic links (ELOOP) is also what a chain longer than the system limit gives, so it passes only
# for a link that link_hides_nothing accepts. GNU quotes the path; BSD and bfs do not.
ELOOP_QUOTED="${FIND_PREFIX}'([^']*)': Too many levels of symbolic links\\.?\$"
ELOOP_PLAIN="${FIND_PREFIX}(.*): Too many levels of symbolic links\\.?\$"

# link_hides_nothing PATH: follows the link PATH one hop at a time with readlink, up to 64 hops. True when the chain
# comes back to a link already met (a loop) or ends at nothing (a dangling link): no file is behind it. False when it
# ends at something that exists, which the system refused to reach only because the chain is too long, and for
# anything else (not a link, a directory that cannot be resolved, more hops): fail closed.
link_hides_nothing() {
    local link="$1" seen=$'\n' hops=0 dir target
    while [ "$hops" -lt 64 ]; do
        if [ ! -L "$link" ]; then
            [ "$hops" -gt 0 ] && [ ! -e "$link" ]
            return
        fi
        # CDPATH is cleared: with it, cd could pick another tree's directory of the same relative name and print it.
        dir="$(CDPATH='' cd -P -- "$(dirname -- "$link")" 2>/dev/null && pwd -P)" || return 1
        link="$dir/${link##*/}"
        case "$seen" in *$'\n'"$link"$'\n'*) return 0 ;; esac
        seen="$seen$link"$'\n'
        target="$(readlink -- "$link")" || return 1
        case "$target" in /*) link="$target" ;; *) link="$dir/$target" ;; esac
        hops=$((hops + 1))
    done
    return 1
}

# only_loop_errors FILE: true when FILE holds at least one line and every line is a link loop error.
only_loop_errors() {
    local line seen=0
    while IFS= read -r line || [ -n "$line" ]; do
        if [[ $line =~ $DIR_LOOP ]]; then
            :
        elif [[ $line =~ $ELOOP_QUOTED ]] || [[ $line =~ $ELOOP_PLAIN ]]; then
            link_hides_nothing "${BASH_REMATCH[2]}" || return 1
        else
            return 1
        fi
        seen=1
    done < "$1"
    [ "$seen" -eq 1 ]
}

# collect ROOT [GLOBAL-OPTIONS...] -- TESTS...: one `find -L` pass that prints every directory, every link it cannot
# follow and every file TESTS match. Files are appended to `files`, sorted. Exits 2 naming ROOT when a printed
# directory cannot be read or searched, or a link it cannot follow hides something (both whatever find's exit status
# and wording: BSD find reports a link chain that is too long as a broken link, silently), or when find fails for any
# reason other than link loops.
collect() {
    local root="$1" status=0 entry
    local opts=()
    shift
    while [ "$1" != "--" ]; do opts+=("$1"); shift; done
    shift
    LC_ALL=C find -L "$root" ${opts[@]+"${opts[@]}"} \( -type d -o -type l -o \( "$@" \) \) -print0 \
        >"$WORK/found" 2>"$WORK/errors" || status=$?
    if [ -s "$WORK/errors" ]; then cat "$WORK/errors" >&2; fi
    : >"$WORK/files"
    while IFS= read -r -d '' entry; do
        if [ -d "$entry" ]; then
            if ! { [ -r "$entry" ] && [ -x "$entry" ]; }; then
                echo "check-workflow-pins: cannot read $entry under $root, so not every file under it was checked" >&2
                exit 2
            fi
        elif [ -f "$entry" ]; then
            printf '%s\n' "$entry" >>"$WORK/files"
        elif ! link_hides_nothing "$entry"; then
            # With -L, only a link find cannot follow matches -type l.
            echo "check-workflow-pins: cannot follow $entry under $root, so not every file under it was checked" >&2
            exit 2
        fi
    done <"$WORK/found"
    if [ "$status" -ne 0 ] && ! only_loop_errors "$WORK/errors"; then
        echo "check-workflow-pins: find failed (exit $status) while reading $root," \
            "so not every file under it was checked" >&2
        exit 2
    fi
    while IFS= read -r entry; do files+=("$entry"); done < <(LC_ALL=C sort "$WORK/files")
}

files=()
collect "$DIR" -maxdepth 1 -- -type f \( -name '*.yml' -o -name '*.yaml' \)
[ "${#files[@]}" -gt 0 ] || { echo "check-workflow-pins: no *.yml or *.yaml file in $DIR" >&2; exit 2; }

# A `./.github/actions/...` reference runs a composite action from the tree, and that action can itself name outside
# actions. Exit 2 above depends on workflow files only. -L: a symlinked action directory or file runs like a real one.
# Subdirectories of the workflows directory are read as well, so that whatever a name LOCAL_VALUE accepts turns out
# to be on disk (a directory called `x.yml` holding an action, for one), its `uses` lines are still checked.
# -mindepth 2 on the workflows directory only: a file directly in it is already in the list as a workflow.
collect "$DIR" -mindepth 2 -- -type f \( -name 'action.yml' -o -name 'action.yaml' \)
ACTIONS_DIR="$(dirname "$DIR")/actions"
if [ -d "$ACTIONS_DIR" ]; then
    collect "$ACTIONS_DIR" -- -type f \( -name 'action.yml' -o -name 'action.yaml' \)
fi

for f in "${files[@]}"; do check_file "$f"; done

[ "${#violations[@]}" -gt 0 ] || exit 0
printf '%s\n' "${violations[@]}" | LC_ALL=C sort -t$'\t' -k1,1 -k2,2n -u | awk -F'\t' '{ printf "%s:%s: %s\n", $1, $2, $3 }'
exit 1
