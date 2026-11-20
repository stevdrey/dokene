#!/usr/bin/env bash
# Offline tests for scripts/check-java4shell-runtime.sh (issue 429, CVE-2026-114938). A stub `java` prints the
# property and flag lines a real JVM prints for `-XshowSettings:properties -XX:+PrintFlagsFinal -version`, so no JDK,
# network or Docker is needed. The stub reports UseBASE64Intrinsics=false only when it is started with
# -XX:-UseBASE64Intrinsics, like a real JVM.
# The single-quoted $ below is meant for the generated stub, not this shell.
# shellcheck disable=SC2016
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CHECK="$ROOT_DIR/scripts/check-java4shell-runtime.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

FAILURES=0
pass() { printf 'ok   %s\n' "$1"; }
fail() { printf 'FAIL %s\n' "$1"; FAILURES=$((FAILURES + 1)); }

# stub <name> <java.runtime.version> [os.arch]: writes an executable fake launcher and prints its path.
stub() {
  local path="$WORK/$1/java"
  mkdir -p "$WORK/$1"
  cat >"$path" <<EOF
#!/usr/bin/env bash
intrinsic=true
for arg in "\$@" \${JAVA_TOOL_OPTIONS:-}; do
  [ "\$arg" = "-XX:-UseBASE64Intrinsics" ] && intrinsic=false
  [ "\$arg" = "-XX:+Broken" ] && { echo "Unrecognized VM option 'Broken'" >&2; exit 1; }
done
cat >&2 <<PROPS
Property settings:
    java.runtime.version = $2
    java.vendor = Stub Vendor
    java.vendor.version = Stub-$2
    os.arch = ${3:-amd64}
PROPS
printf '     bool UseBASE64Intrinsics                      = %-38s {product} {default}\n' "\$intrinsic"
printf '     bool UseAES                                   = true                                   {product} {default}\n'
echo 'openjdk version "x"' >&2
EOF
  chmod +x "$path"
  printf '%s' "$path"
}

# expect <name> <exit code> <status or -> <args...>
expect() {
  local name="$1" want_exit="$2" want_status="$3"; shift 3
  local out rc
  out="$(env -u JAVA_TOOL_OPTIONS -u JDK_JAVA_OPTIONS "$CHECK" "$@" 2>&1)"; rc=$?
  if [ "$rc" != "$want_exit" ]; then
    fail "$name (exit $rc, want $want_exit)"; printf '%s\n' "$out" | sed 's/^/     /'; return
  fi
  if [ "$want_status" != "-" ] && ! grep -qx "status: $want_status" <<<"$out"; then
    fail "$name (status, want $want_status)"; printf '%s\n' "$out" | sed 's/^/     /'; return
  fi
  pass "$name"
}

MIT=(-- -XX:-UseBASE64Intrinsics)

# Fixed builds from the vendor advisories and their predecessors.
expect "27.0.1.1+4 is patched"               0 PATCHED      --require-patched --java "$(stub t27p 27.0.1.1+4)"
expect "27.0.2+5 is patched"                 0 PATCHED      --require-patched --java "$(stub t27n 27.0.2+5)"
expect "27.0.1+8 is vulnerable"              1 VULNERABLE   --java "$(stub t27v 27.0.1+8)"
expect "27+35 is vulnerable"                 1 VULNERABLE   --java "$(stub t27g 27+35)"
expect "25.0.5.1+2-LTS is patched"           0 PATCHED      --java "$(stub t25p 25.0.5.1+2-LTS)"
expect "25.0.5+8-LTS is vulnerable"          1 VULNERABLE   --java "$(stub t25v 25.0.5+8-LTS)"
expect "21.0.13.1+2-LTS is patched"          0 PATCHED      --java "$(stub t21p 21.0.13.1+2-LTS)"
expect "21.0.13+9-LTS is vulnerable"         1 VULNERABLE   --java "$(stub t21v 21.0.13+9-LTS)"
expect "21.0.14+3 is patched"                0 PATCHED      --java "$(stub t21n 21.0.14+3)"
expect "17.0.21.1+2 is patched"              0 PATCHED      --java "$(stub t17p 17.0.21.1+2)"
expect "17.0.6+10 is vulnerable"             1 VULNERABLE   --java "$(stub t17v 17.0.6+10)"
expect "17.0.5+8 predates the bug"           0 NOT_AFFECTED --require-patched --java "$(stub t17o 17.0.5+8)"
expect "11.0.25+9 is not affected"           0 NOT_AFFECTED --require-patched --java "$(stub t11 11.0.25+9)"
expect "1.8.0_402-b06 is not affected"       0 NOT_AFFECTED --java "$(stub t8 1.8.0_402-b06)"
expect "26.0.2.1+1 has no fix"               1 VULNERABLE   --java "$(stub t26 26.0.2.1+1)"
expect "28-ea is treated as fixed"           0 PATCHED      --java "$(stub t28 28-ea+3)"

# The interim mitigation.
expect "26 with the flag is mitigated"       0 MITIGATED    --java "$(stub t26m 26.0.2.1+1)" "${MIT[@]}"
expect "27.0.1 with the flag is mitigated"   0 MITIGATED    --java "$(stub t27m 27.0.1+8)" "${MIT[@]}"
expect "mitigation is not a patch"           1 MITIGATED    --require-patched --java "$(stub t27r 27.0.1+8)" "${MIT[@]}"
expect "aarch64 still needs the patch"       1 VULNERABLE   --java "$(stub tarm 27.0.1+8 aarch64)"

# JAVA_TOOL_OPTIONS is honored by the JVM, so the check sees it.
out="$(JAVA_TOOL_OPTIONS=-XX:-UseBASE64Intrinsics "$CHECK" --java "$(stub tenv 27.0.1+8)" 2>&1)"; rc=$?
if [ "$rc" = 0 ] && grep -qx 'status: MITIGATED' <<<"$out"; then pass "JAVA_TOOL_OPTIONS mitigation is seen"; else fail "JAVA_TOOL_OPTIONS mitigation is seen"; fi

# Usage and inspection errors.
expect "unknown argument"                    2 -            --bogus
expect "missing launcher"                    2 -            --java "$WORK/does-not-exist/java"
expect "JVM rejects an option"               2 -            --java "$(stub tbad 27.0.1+8)" -- -XX:+Broken
expect "unparseable version"                 2 -            --java "$(stub tjunk banana)"

if [ "$FAILURES" -ne 0 ]; then
  printf '%d check(s) failed\n' "$FAILURES"
  exit 1
fi
echo "all checks passed"
