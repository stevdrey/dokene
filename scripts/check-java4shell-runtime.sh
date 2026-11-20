#!/usr/bin/env bash
# Reports whether a JVM is exposed to Java4Shell (CVE-2026-114938, OpenJDK Base64 decoder intrinsic), issue 429.
#
# Usage: scripts/check-java4shell-runtime.sh [--require-patched] [--java <path>] [-- <extra JVM options>]
#
#   --java <path>       java launcher to inspect (default: $JAVA_HOME/bin/java, else `java` on PATH).
#   --require-patched   accept only a vendor-fixed build; the -XX:-UseBASE64Intrinsics mitigation is not enough.
#   -- <options>        JVM options the real process starts with (for example -XX:-UseBASE64Intrinsics), so the
#                       check sees the flag values that process gets. JAVA_TOOL_OPTIONS and JDK_JAVA_OPTIONS in the
#                       environment are honored by the JVM itself.
#
# Prints one `key: value` line per finding, then `status: PATCHED|NOT_AFFECTED|MITIGATED|VULNERABLE`.
# Exit 0: PATCHED or NOT_AFFECTED, or MITIGATED without --require-patched. Exit 1: anything else. Exit 2: usage
# error or the JVM could not be inspected.
#
# Fixed builds are those published in the Adoptium and Red Hat advisories of 2026-11-17/18: 17.0.21.1, 21.0.13.1,
# 25.0.5.1 and 27.0.1.1. Feature releases 8 and 11 are not affected. 18-20, 22-24 and 26 receive no fix. A feature
# release above 27 is treated as fixed (it branches after the fix); revisit this when it ships.
# The CPU line is informational: exploitation needs x86-64 with AVX-512 VBMI, but the status never depends on the
# host the check happens to run on, because an image or a toolchain moves between hosts.
set -Eeuo pipefail

require_patched=false
java_bin=""
jvm_opts=()

usage() {
  sed -n '4,10p' "$0" | sed 's/^# \{0,1\}//' >&2
  exit 2
}

while (($#)); do
  case "$1" in
    --require-patched) require_patched=true; shift ;;
    --java) [[ $# -ge 2 ]] || usage; java_bin="$2"; shift 2 ;;
    --) shift; jvm_opts=("$@"); break ;;
    -h|--help) usage ;;
    *) echo "Error: unknown argument: $1" >&2; usage ;;
  esac
done

if [[ -z "$java_bin" ]]; then
  if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
    java_bin="${JAVA_HOME}/bin/java"
  else
    java_bin="$(command -v java || true)"
  fi
fi
if [[ -z "$java_bin" ]]; then
  echo "Error: no java launcher found (set --java or JAVA_HOME)." >&2
  exit 2
fi

if ! output="$("$java_bin" ${jvm_opts[@]+"${jvm_opts[@]}"} -XshowSettings:properties -XX:+PrintFlagsFinal -version 2>&1)"; then
  echo "Error: ${java_bin} could not be started with the given options." >&2
  printf '%s\n' "$output" | tail -n 5 >&2
  exit 2
fi

# Pure bash, so the check also runs inside minimal images without awk or sed (the Keycloak image has neither awk).
runtime_version="" vendor="" vendor_version="" arch="" intrinsic=""
flag_pattern='^[[:space:]]*bool[[:space:]]+UseBASE64Intrinsics[[:space:]]+=[[:space:]]+([a-z]+)'
while IFS= read -r line; do
  trimmed="${line#"${line%%[![:space:]]*}"}"
  case "$trimmed" in
    "java.runtime.version = "*) [[ -n "$runtime_version" ]] || runtime_version="${trimmed#* = }" ;;
    "java.vendor = "*) [[ -n "$vendor" ]] || vendor="${trimmed#* = }" ;;
    "java.vendor.version = "*) [[ -n "$vendor_version" ]] || vendor_version="${trimmed#* = }" ;;
    "os.arch = "*) [[ -n "$arch" ]] || arch="${trimmed#* = }" ;;
  esac
  if [[ -z "$intrinsic" && "$line" =~ $flag_pattern ]]; then
    intrinsic="${BASH_REMATCH[1]}"
  fi
done <<<"$output"

if [[ -z "$runtime_version" ]]; then
  echo "Error: java.runtime.version not reported by ${java_bin}." >&2
  exit 2
fi

# 27.0.1.1+4 -> 27 0 1 1; 21.0.13+9-LTS -> 21 0 13 0; 26 -> 26 0 0 0; 1.8.0_402-b06 -> 8 0 402 0.
numeric="${runtime_version%%[+-]*}"
if [[ "$numeric" == 1.8.0_* ]]; then
  numeric="8.0.${numeric#1.8.0_}"
  numeric="${numeric%%[!0-9.]*}"
fi
IFS=. read -r feature interim update patch _ <<<"${numeric}.0.0.0.0"
for part in feature interim update patch; do
  if ! [[ "${!part}" =~ ^[0-9]+$ ]]; then
    echo "Error: cannot parse java.runtime.version '${runtime_version}'." >&2
    exit 2
  fi
done

# Compares interim.update.patch with a minimum; returns 0 when the version is at least the minimum.
at_least() {
  local min_interim=$1 min_update=$2 min_patch=$3
  ((10#$interim != min_interim)) && { ((10#$interim > min_interim)); return; }
  ((10#$update != min_update)) && { ((10#$update > min_update)); return; }
  ((10#$patch >= min_patch))
}

fixed=""
case "$feature" in
  8|9|10|11|12|13|14|15|16) fixed=not-affected ;;
  17)
    if ! at_least 0 6 0; then fixed=not-affected
    elif at_least 0 21 1; then fixed=yes
    else fixed=no; fi ;;
  21) if at_least 0 13 1; then fixed=yes; else fixed=no; fi ;;
  25) if at_least 0 5 1; then fixed=yes; else fixed=no; fi ;;
  27) if at_least 0 1 1; then fixed=yes; else fixed=no; fi ;;
  18|19|20|22|23|24|26) fixed=no-fix-available ;;
  *)
    if ((10#$feature > 27)); then fixed=yes; else fixed=no; fi ;;
esac

cpu="unknown"
if [[ -r /proc/cpuinfo ]]; then
  cpu="avx512vbmi absent"
  while IFS= read -r line; do
    if [[ "$line" == flags* && " ${line#*:} " == *" avx512vbmi "* ]]; then
      cpu="avx512vbmi present"
      break
    fi
  done </proc/cpuinfo
fi

echo "java: ${java_bin}"
echo "runtime-version: ${runtime_version}"
echo "vendor: ${vendor:-unknown}${vendor_version:+ (${vendor_version})}"
echo "arch: ${arch:-unknown}"
echo "UseBASE64Intrinsics: ${intrinsic:-unreported}"
echo "fixed-build: ${fixed}"
echo "host-cpu (informational): ${cpu}"

if [[ "$fixed" == not-affected ]]; then
  status=NOT_AFFECTED
elif [[ "$fixed" == yes ]]; then
  status=PATCHED
elif [[ "$intrinsic" == false ]]; then
  status=MITIGATED
else
  status=VULNERABLE
fi
echo "status: ${status}"

case "$status" in
  PATCHED|NOT_AFFECTED) exit 0 ;;
  MITIGATED)
    if [[ "$require_patched" == true ]]; then
      echo "Error: a vendor-fixed runtime is required; the mitigation flag alone is not accepted here." >&2
      exit 1
    fi
    exit 0 ;;
  *)
    echo "Error: this JVM is exposed to CVE-2026-114938. Upgrade it, or start it with -XX:-UseBASE64Intrinsics." >&2
    exit 1 ;;
esac
