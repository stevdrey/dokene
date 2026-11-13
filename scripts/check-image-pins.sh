#!/usr/bin/env bash
# ABOUTME: Fails when a container image is referenced by anything but its tag plus a sha256 digest (issue #323, audit L13).
# ABOUTME: Line based scan of Dockerfiles, compose files, Java and shell; needs only bash, no network, no Docker.
#
# Usage: ./scripts/check-image-pins.sh [path...]     (default path: .)
# Each path is a file or a directory searched recursively. Exit 0 when every reference is pinned, 1 with one
# `<file>:<line>: <reference>` line per violation, 2 when a path does not exist or no checked file was found.
# Rules and known limits: docs/security/build-input-pinning.md.
set -euo pipefail

[ "$#" -gt 0 ] || set -- .

# Directory names that are never searched: dependencies, build output, and other checkouts of this repository.
SKIPPED_DIRS=(.git node_modules build dist .gradle .claude .worktrees)

# An image built from this tree: no `/`, so no registry host can hide behind the prefix.
LOCAL_REF='^dokene-[a-z0-9._-]+(:[A-Za-z0-9._-]+)?$'
FULL_PIN='^[a-z0-9][a-z0-9._/-]*(:[0-9]+)?(/[a-z0-9._/-]+)*:[A-Za-z0-9_][A-Za-z0-9._-]*@sha256:[0-9a-f]{64}$'

FROM_LINE='^[[:space:]]*[Ff][Rr][Oo][Mm][[:space:]]'
COPY_OR_RUN_LINE='^[[:space:]]*([Cc][Oo][Pp][Yy]|[Rr][Uu][Nn])[[:space:]]'
SYNTAX_LINE='^#[[:space:]]*syntax[[:space:]]*=[[:space:]]*(.*)$'
HASH_COMMENT='^[[:space:]]*#'
JAVA_COMMENT='^[[:space:]]*(//|\*|/\*)'
COMPOSE_IMAGE_LINE='^[[:space:]]*(-[[:space:]]+)?image:[[:space:]]*(.*)$'
JAVA_CONTAINER='new[[:space:]]+[A-Za-z0-9_]*Container(<[^>]*>)?\([[:space:]]*"([^"]*)'
JAVA_IMAGE_NAME='DockerImageName\.parse\([[:space:]]*"([^"]*)'
SHELL_ASSIGNMENT='^[[:space:]]*((local|export|readonly)[[:space:]]+)?[A-Za-z0-9_]*IMAGE=(.*)$'
SHELL_DEFAULT='^\$\{[A-Za-z0-9_]+:-(.*)\}$'

# Plain indexed arrays instead of associative ones, so the script also runs on the bash 3.2 that macOS ships.
violations=()        # "file:line: reference", in the order read
drift=()             # the same, for a second digest of a name and tag already pinned; printed last
pinned_names=()      # name:tag, parallel to pinned_digests
pinned_digests=()

report() { violations+=("$1:$2: $3"); }

# record_pin FILE LINE REFERENCE: one name and tag maps to one digest across every file read. The first digest seen
# is the reference; every later line carrying another digest for the same name and tag is a violation.
record_pin() {
    local name="${3%%@*}" digest="${3##*@}" i
    for i in ${pinned_names[@]+"${!pinned_names[@]}"}; do
        if [ "${pinned_names[$i]}" = "$name" ]; then
            [ "${pinned_digests[$i]}" = "$digest" ] || drift+=("$1:$2: $3")
            return 0
        fi
    done
    pinned_names+=("$name"); pinned_digests+=("$digest")
}

# require_pin FILE LINE REFERENCE
require_pin() {
    if [[ $3 =~ $FULL_PIN ]]; then record_pin "$1" "$2" "$3"; else report "$1" "$2" "$3"; fi
}

# unquote TEXT: sets `value` to the first value in TEXT, with one pair of surrounding quotes removed. An unquoted
# value ends at the first whitespace.
unquote() {
    local text="$1"
    if [[ $text =~ ^\"([^\"]*)\" ]] || [[ $text =~ ^\'([^\']*)\' ]]; then
        value="${BASH_REMATCH[1]}"
    else
        # A quote that closes on a later line (a command substitution over several lines) still starts the value.
        text="${text#[\"\']}"
        value="${text%%[[:space:]]*}"
    fi
}

check_dockerfile() {
    local file="$1" lineno=0 line lower reference stage i token stages=" "
    local -a tokens
    while IFS= read -r line || [ -n "$line" ]; do
        lineno=$((lineno + 1))
        line="${line%$'\r'}"
        if [[ $line =~ $SYNTAX_LINE ]]; then
            unquote "${BASH_REMATCH[1]}"
            require_pin "$file" "$lineno" "$value"
            continue
        fi
        [[ $line =~ $HASH_COMMENT ]] && continue
        if [[ $line =~ $FROM_LINE ]]; then
            read -r -a tokens <<<"$line"
            i=1
            while [ "$i" -lt "${#tokens[@]}" ] && [[ ${tokens[$i]} == --* ]]; do i=$((i + 1)); done
            reference="${tokens[$i]:-}"
            lower="$(printf '%s' "$reference" | tr '[:upper:]' '[:lower:]')"
            if [ "$lower" != scratch ] && [[ $stages != *" $lower "* ]]; then
                require_pin "$file" "$lineno" "$reference"
            fi
            stage="$(printf '%s' "${tokens[$((i + 1))]:-}" | tr '[:upper:]' '[:lower:]')"
            if [ "$stage" = as ] && [ -n "${tokens[$((i + 2))]:-}" ]; then
                stages="$stages$(printf '%s' "${tokens[$((i + 2))]}" | tr '[:upper:]' '[:lower:]') "
            fi
        elif [[ $line =~ $COPY_OR_RUN_LINE ]]; then
            read -r -a tokens <<<"$line"
            for token in "${tokens[@]}"; do
                [[ $token == --from=* ]] || continue
                reference="${token#--from=}"
                # No `/`, `:` or `@`: an earlier stage or a named build context, not an image reference.
                [[ $reference == *[/:@]* ]] || continue
                require_pin "$file" "$lineno" "$reference"
            done
        fi
    done <"$file"
}

check_compose() {
    local file="$1" lineno=0 line rest
    while IFS= read -r line || [ -n "$line" ]; do
        lineno=$((lineno + 1))
        line="${line%$'\r'}"
        [[ $line =~ $HASH_COMMENT ]] && continue
        [[ $line =~ $COMPOSE_IMAGE_LINE ]] || continue
        rest="${BASH_REMATCH[2]}"
        unquote "$rest"
        [[ $value =~ $LOCAL_REF ]] && continue
        require_pin "$file" "$lineno" "$value"
    done <"$file"
}

check_java() {
    local file="$1" lineno=0 line
    case "$(basename "$file")" in
        # The one place that reads the pinned reference out of compose.yaml, and its test.
        ComposeImages.java|ComposeImagesTest.java) return 0 ;;
    esac
    while IFS= read -r line || [ -n "$line" ]; do
        lineno=$((lineno + 1))
        line="${line%$'\r'}"
        [[ $line =~ $JAVA_COMMENT ]] && continue
        if [[ $line =~ $JAVA_CONTAINER ]]; then
            report "$file" "$lineno" "${BASH_REMATCH[2]}"
        elif [[ $line =~ $JAVA_IMAGE_NAME ]]; then
            report "$file" "$lineno" "${BASH_REMATCH[1]}"
        fi
    done <"$file"
}

check_shell() {
    local file="$1" lineno=0 line
    while IFS= read -r line || [ -n "$line" ]; do
        lineno=$((lineno + 1))
        line="${line%$'\r'}"
        [[ $line =~ $HASH_COMMENT ]] && continue
        [[ $line =~ $SHELL_ASSIGNMENT ]] || continue
        unquote "${BASH_REMATCH[3]}"
        if [[ $value =~ $SHELL_DEFAULT ]]; then value="${BASH_REMATCH[1]}"; fi
        [ -z "$value" ] && continue
        [[ $value == \$* ]] && continue
        [[ $value =~ $LOCAL_REF ]] && continue
        require_pin "$file" "$lineno" "$value"
    done <"$file"
}

checked=0
check_file() {
    local file="${1#./}"
    case "$(basename "$file")" in
        Dockerfile|Dockerfile.*|*.Dockerfile) check_dockerfile "$file" ;;
        compose*.yml|compose*.yaml|docker-compose*.yml|docker-compose*.yaml) check_compose "$file" ;;
        *.java) check_java "$file" ;;
        *.sh) check_shell "$file" ;;
        *) return 0 ;;
    esac
    checked=$((checked + 1))
}

prune=()
for name in "${SKIPPED_DIRS[@]}"; do
    [ "${#prune[@]}" -eq 0 ] || prune+=(-o)
    prune+=(-name "$name")
done

for path in "$@"; do
    if [ -f "$path" ]; then
        check_file "$path"
    elif [ -d "$path" ]; then
        while IFS= read -r f; do check_file "$f"; done < <(
            find "${path%/}" -type d \( "${prune[@]}" \) -prune -o -type f \( \
                -name 'Dockerfile' -o -name 'Dockerfile.*' -o -name '*.Dockerfile' \
                -o -name 'compose*.yml' -o -name 'compose*.yaml' \
                -o -name 'docker-compose*.yml' -o -name 'docker-compose*.yaml' \
                -o -name '*.java' -o -name '*.sh' \) -print | LC_ALL=C sort)
    else
        echo "check-image-pins: $path does not exist" >&2
        exit 2
    fi
done

[ "$checked" -gt 0 ] || { echo "check-image-pins: no Dockerfile, compose, Java or shell file under: $*" >&2; exit 2; }

[ "${#violations[@]}" -gt 0 ] && printf '%s\n' "${violations[@]}"
[ "${#drift[@]}" -gt 0 ] && printf '%s\n' "${drift[@]}"
[ "${#violations[@]}" -eq 0 ] && [ "${#drift[@]}" -eq 0 ]
