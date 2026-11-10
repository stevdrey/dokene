#!/usr/bin/env bash
# ABOUTME: Fails when a GitHub Actions workflow references an action by anything but a full commit SHA plus its tag (issue #309, audit M5).
# ABOUTME: Line based scan over `uses:` lines; needs only bash, no network, no Docker. Rules: docs/security/github-actions-pinning.md.
#
# Usage: ./scripts/check-workflow-pins.sh [dir]     (default dir: .github/workflows)
# Exit 0 when every reference is pinned, 1 with one `<dir>/<file>:<line>: <reference>` line per violation,
# 2 when the directory holds no *.yml or *.yaml file. Local composite actions (action.yml, action.yaml) under the
# sibling `actions` directory of dir are scanned too, when that directory exists.
set -euo pipefail

DIR="${1:-.github/workflows}"
DIR="${DIR%/}"

USES_LINE='^[[:space:]]*(-[[:space:]]+)?("uses"|'\''uses'\''|uses)[[:space:]]*:[[:space:]]*(.*)$'
COMMENT_LINE='^[[:space:]]*#'
PINNED_VALUE='^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(/[^@[:space:]]+)?@[0-9a-f]{40}$'
TAG_COMMENT='^#[[:space:]]+[^[:space:]]+[[:space:]]*$'
# A local reference must stay inside the two trees this script reads; no segment may start with a dot, which rules out `..`.
LOCAL_VALUE='^\./\.github/(actions|workflows)(/[A-Za-z0-9_-][A-Za-z0-9_.-]*)+$'
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

files=()
while IFS= read -r f; do files+=("$f"); done < <(find "$DIR" -maxdepth 1 -type f \( -name '*.yml' -o -name '*.yaml' \) | LC_ALL=C sort)
[ "${#files[@]}" -gt 0 ] || { echo "check-workflow-pins: no *.yml or *.yaml file in $DIR" >&2; exit 2; }

# A `./.github/actions/...` reference runs a composite action from the tree, and that action can itself name outside
# actions. Exit 2 above depends on workflow files only.
ACTIONS_DIR="$(dirname "$DIR")/actions"
if [ -d "$ACTIONS_DIR" ]; then
    while IFS= read -r f; do files+=("$f"); done < <(find "$ACTIONS_DIR" -type f \( -name 'action.yml' -o -name 'action.yaml' \) | LC_ALL=C sort)
fi

for f in "${files[@]}"; do check_file "$f"; done

[ "${#violations[@]}" -gt 0 ] || exit 0
printf '%s\n' "${violations[@]}" | LC_ALL=C sort -t$'\t' -k1,1 -k2,2n -u | awk -F'\t' '{ printf "%s:%s: %s\n", $1, $2, $3 }'
exit 1
