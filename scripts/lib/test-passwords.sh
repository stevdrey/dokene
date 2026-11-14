# shellcheck shell=bash
# ABOUTME: Loads the local realm test users' passwords from DOKENE_TEST_<ROLE>_PASSWORD (issue 314).
# ABOUTME: Sourced by the seed and verify scripts after they load .env; stops the caller when a variable is missing.

# The callers read these three variables.
# shellcheck disable=SC2034
{
    test_password_hint="is not set; run ./scripts/dev-env.sh up, which generates it in .env"
    OWNER_PASSWORD="${DOKENE_TEST_OWNER_PASSWORD:?DOKENE_TEST_OWNER_PASSWORD $test_password_hint}"
    OPERATOR_PASSWORD="${DOKENE_TEST_OPERATOR_PASSWORD:?DOKENE_TEST_OPERATOR_PASSWORD $test_password_hint}"
    VIEWER_PASSWORD="${DOKENE_TEST_VIEWER_PASSWORD:?DOKENE_TEST_VIEWER_PASSWORD $test_password_hint}"
    unset test_password_hint
}

# test_password_for USERNAME: prints that realm test user's own password. Fails for any other name; assign the result
# to a variable and check the status, because errexit does not see a failure inside an argument.
test_password_for() {
    case "$1" in
        testuser) printf '%s' "$OWNER_PASSWORD" ;;
        testoperator) printf '%s' "$OPERATOR_PASSWORD" ;;
        testviewer) printf '%s' "$VIEWER_PASSWORD" ;;
        *) echo "Error: no test password for $1" >&2; return 1 ;;
    esac
}
