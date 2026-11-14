# shellcheck shell=bash
# ABOUTME: A curl wrapper that keeps request headers, cookie values and URLs off curl's command line (issue 327).
# ABOUTME: Source it; every later `curl` call in that shell (and its subshells) runs through it.
#
# The local QA scripts pass session values to curl: the X-CSRF-TOKEN header, a JSESSIONID cookie given by value, and
# the OAuth callback URL with its authorization code. On a command line any local user can read them in the process
# table. Once this file is sourced, `curl` is a shell function that moves
#   - every -H/--header value, except an @file reference,
#   - every -b/--cookie value holding '=' (a cookie string; a value without '=' is a cookie jar file and stays),
#   - every URL: a --url value, an argument with a scheme (`http://…`) that is not the value of an option, or an
#     argument after `--`,
# into a curl config that the real curl reads from a file descriptor (`-K <(…)`), and passes the rest unchanged.
# A function call starts no process, so the values never reach an argument list. A value holding a CR or LF is refused
# with status 2, since it could add a line to the config or a header to the request. Calls with nothing to move run
# the real curl directly.
#
# The URL rule needs to know which options take a value, so an `http://…` value given to one of them (for example
# `-d http://…`) is not mistaken for a URL; _CURL_VALUE_OPTIONS lists the ones the scripts use and the common others.
# Tested offline by scripts/tests/bff-login-test.sh.

_CURL_VALUE_OPTIONS=" -d --data --data-raw --data-binary --data-ascii --data-urlencode --json -F --form --form-string
 -o --output -D --dump-header -c --cookie-jar -w --write-out -X --request -e --referer -x --proxy -U --proxy-user
 -u --user -A --user-agent -m --max-time --connect-timeout -K --config -T --upload-file --retry --retry-delay
 --retry-max-time --resolve --connect-to --cacert --capath --cert --key -E -r --range -z --time-cond --limit-rate
 --parallel-max -Y --speed-limit -y --speed-time --max-filesize --max-redirs --interface --dns-servers -C
 --continue-at -t --telnet-option --trace --trace-ascii --stderr --unix-socket --abstract-unix-socket "

# _curl_config_line NAME VALUE: one config line, NAME = "VALUE" with \ and " escaped; status 2 for a CR or LF.
_curl_config_line() {
    local value="$2"
    case "$value" in
        *$'\n'* | *$'\r'*)
            printf 'curl-protected: refusing a %s that holds a line break\n' "$1" >&2
            return 2 ;;
    esac
    value="${value//\\/\\\\}"
    value="${value//\"/\\\"}"
    printf '%s = "%s"\n' "$1" "$value"
}

curl() {
    local -a args=()
    local arg config="" line
    while [ "$#" -gt 0 ]; do
        arg="$1"
        shift
        case "$arg" in
            -H | --header | -b | --cookie | --url)
                if [ "$#" -eq 0 ]; then
                    args+=("$arg")
                    continue
                fi
                case "$arg:$1" in
                    -H:@* | --header:@*) args+=("$arg" "$1") ;;
                    -H:* | --header:*) line="$(_curl_config_line header "$1")" || return 2; config+="$line"$'\n' ;;
                    -b:*=* | --cookie:*=*) line="$(_curl_config_line cookie "$1")" || return 2; config+="$line"$'\n' ;;
                    -b:* | --cookie:*) args+=("$arg" "$1") ;;
                    --url:*) line="$(_curl_config_line url "$1")" || return 2; config+="$line"$'\n' ;;
                esac
                shift
                continue ;;
            --)
                while [ "$#" -gt 0 ]; do
                    line="$(_curl_config_line url "$1")" || return 2
                    config+="$line"$'\n'
                    shift
                done
                continue ;;
        esac
        if [ "$#" -gt 0 ] && [[ "$_CURL_VALUE_OPTIONS" == *[[:space:]]"$arg"[[:space:]]* ]]; then
            args+=("$arg" "$1")
            shift
            continue
        fi
        if [[ "$arg" =~ ^[A-Za-z][A-Za-z0-9+.-]*:// ]]; then
            line="$(_curl_config_line url "$arg")" || return 2
            config+="$line"$'\n'
            continue
        fi
        args+=("$arg")
    done
    if [ -z "$config" ]; then
        command curl ${args[@]+"${args[@]}"}
    else
        command curl -K <(printf '%s' "$config") ${args[@]+"${args[@]}"}
    fi
}
