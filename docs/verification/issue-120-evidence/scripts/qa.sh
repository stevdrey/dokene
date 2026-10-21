# QA helper (synthetic data only). Source it from the repository root after exporting .env.
# qa_login <user>; api <user> <METHOD> <path> <json> [curl args] -> BODY CODE HDRS; getj <user> <path>
BFF=${BFF:-http://localhost:8080}; PW=${DOKENE_TEST_USER_PASSWORD:-testpassword}
QA_TMP=${QA_TMP:-$(mktemp -d)}
qa_login(){ local u=$1 j=$QA_TMP/jar-$1.txt h=$QA_TMP/h.tmp b=$QA_TMP/b.tmp; rm -f $j
 curl -s -c $j -D $h -o $b $BFF/oauth2/authorization/dokene; local l=$(grep -i '^location:' $h|tr -d '\r\n'|awk '{print $2}')
 curl -s -c $j -b $j -o $b "$l"; local a=$(grep -o 'action="[^"]*"' $b|head -1|cut -d'"' -f2|sed 's/&amp;/\&/g')
 curl -s -c $j -b $j -D $h -o $b --data-urlencode username=$u --data-urlencode password=$PW "$a"
 l=$(grep -i '^location:' $h|tr -d '\r\n'|awk '{print $2}'); curl -s -c $j -b $j -o $b "$l"; }
api() { local u=$1 m=$2 p=$3 d=${4:-}; shift 4; local j=$QA_TMP/jar-$u.txt tok
  tok=$(curl -s -b $j -c $j $BFF/api/session | jq -r .csrfToken)
  local out; out=$(curl -s -i -b $j -c $j -X $m "$BFF$p" -H "X-Tenant-Id: $TENANT" -H "X-CSRF-TOKEN: $tok" ${d:+-H 'Content-Type: application/json' -d "$d"} "$@")
  CODE=$(echo "$out" | head -1 | awk '{print $2}'); HDRS=$(echo "$out" | sed '/^\r\{0,1\}$/q'); BODY=$(echo "$out" | sed '1,/^\r\{0,1\}$/d'); }
etag() { echo "$HDRS" | grep -i '^etag' | tr -d '\r"' | awk '{print $2}'; }
getj() { curl -s -b $QA_TMP/jar-$1.txt -H "X-Tenant-Id: $TENANT" "$BFF$2"; }
psqlq() { docker compose exec -T postgres sh -c "psql -U \$POSTGRES_USER -d \$POSTGRES_DB -Atc \"$1\"" | tr -d '\r'; }
PASSN=0; FAILN=0
chk() { if [ "$3" = "$4" ]; then PASSN=$((PASSN+1)); echo "PASS  $1  $2"; else FAILN=$((FAILN+1)); echo "FAIL  $1  $2  (expected '$3', got '$4')"; fi; }
# random valid Chilean mobile number (+569[2-9]xxxxxxx) so fixtures can be created on every run
rnd_phone() { printf '+569%d%07d' $(( RANDOM % 8 + 2 )) $(( (RANDOM * 32768 + RANDOM) % 10000000 )); }
RUN=$(date +%s%N | cut -c8-16)
