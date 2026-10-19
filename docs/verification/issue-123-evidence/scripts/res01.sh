#!/usr/bin/env bash
# QA suite #123, RES-01 configuration matrix. Each case recreates the backend with a compose override (env only); real OpenAI for cases 5-7 (genuine provider responses), no provider for the others.
cd /home/stevdrey/Projects/Java/dokene-qa-run; . /home/stevdrey/qa123/s/base.sh; B1=$(cat /home/stevdrey/qa123/out/baseline.id); OV=/home/stevdrey/qa123/ov.yaml
waitup() { for i in $(seq 1 60); do st=$(docker compose ps backend --format '{{.Status}}' 2>/dev/null); case "$st" in *healthy*) return 0;; esac; sleep 2; done; return 1; }
recreate() { printf 'services:\n  backend:\n    environment:\n%s\n' "$1" > $OV; COMPOSE_FILE=compose.yaml:$OV docker compose up -d --force-recreate --no-deps backend >/dev/null 2>&1; }
probe() { # label
  for u in testoperator; do qa_login $u; done; TENANT=$A; local t0=$(date +%s.%N)
  rec $B1; local t1=$(date +%s.%N); echo "  recommendation: http=$REC_CODE status=$(echo "$REC_BODY"|jq -r '.status // "-"') reason=$(echo "$REC_BODY"|jq -r '.unavailableReason // "-"') retryable=$(echo "$REC_BODY"|jq -r '.retryable // "-"') in $(printf '%.1f' $(echo "$t1 - $t0" | bc))s audit=$(ai_last)"
  sleep 3.2; draft $B1 REPEAT_PURCHASE_FOLLOW_UP REPEAT_PURCHASE; echo "  draft: http=$CODE status=$(echo "$BODY"|jq -r '.status // "-"') reason=$(echo "$BODY"|jq -r '.unavailableReason // "-"') retryable=$(echo "$BODY"|jq -r '.retryable // "-"')"
  echo "  deterministic queue still works: $(getj testoperator '/api/follow-up-queue?limit=5' | jq -r '.items|length') items; customers: $(getj testoperator /api/customers | jq '.customers|length') listed"
  echo "  log lines (AI/ERROR/WARN, sanitized view):"; docker compose logs --no-log-prefix backend 2>&1 | grep -Ei "AI invocation|ERROR|WARN|provider" | tail -6 | cut -c1-260 | sed 's/^/    /'; }
case_run() { local label=$1 env=$2; echo "== $label"; recreate "$env"; if waitup; then echo "  backend: started healthy"; probe "$label"; else echo "  backend: DID NOT BECOME HEALTHY (status: $(docker compose ps backend --format '{{.Status}}'))"; docker compose logs --no-log-prefix backend 2>&1 | grep -E "Unsupported|APPLICATION FAILED|Caused by|ERROR" | head -5 | cut -c1-300 | sed 's/^/    /'; fi; }
KEY=$(grep '^DOKENE_AI_OPENAI_API_KEY=' .env | cut -d= -f2-)
case_run "1 provider unset (empty)" '      DOKENE_AI_PROVIDER: ""'
case_run "2 provider blank (spaces)" '      DOKENE_AI_PROVIDER: "   "'
case_run "3 provider fake (explicit)" '      DOKENE_AI_PROVIDER: "fake"'
case_run "4 provider unknown value (opneai) -> expected fail-fast (#109 / PR #117)" '      DOKENE_AI_PROVIDER: "opneai"'
case_run "4b provider upper-case OPENAI (case-insensitive match)" "      DOKENE_AI_PROVIDER: \"OPENAI\"
      DOKENE_AI_OPENAI_API_KEY: \"$KEY\""
case_run "5 openai with absent key" '      DOKENE_AI_PROVIDER: "openai"
      DOKENE_AI_OPENAI_API_KEY: ""'
case_run "6 openai with an invalid key (genuine OpenAI 401)" '      DOKENE_AI_PROVIDER: "openai"
      DOKENE_AI_OPENAI_API_KEY: "invalid-key-qa-0000"'
case_run "7 openai with an unavailable model (genuine OpenAI 404)" "      DOKENE_AI_PROVIDER: \"openai\"
      DOKENE_AI_OPENAI_API_KEY: \"$KEY\"
      DOKENE_AI_OPENAI_MODEL: \"gpt-qa-nonexistent-model\""
rm -f $OV; echo "== restore (compose without override)"; docker compose up -d --force-recreate --no-deps backend >/dev/null 2>&1; waitup && echo "  backend healthy with the original .env configuration"
