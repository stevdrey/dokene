#!/usr/bin/env bash
# Retest of #253 on main @ 6689d27: aiEvalLive against the fault-injection stub (NO real provider, NO cost). Usage: run.sh <warn|fail> <report-name>
source ~/.sdkman/bin/sdkman-init.sh >/dev/null 2>&1; sdk use java 26.0.2+1.1-tem >/dev/null 2>&1
cd /home/stevdrey/Projects/Java/dokene-qa-run/backend
echo "503:30" > /home/stevdrey/qa121/stub/fail; echo 2 > /home/stevdrey/qa121/stub/skip
export DOKENE_AI_OPENAI_API_KEY=sk-qa-stub-not-a-real-key DOKENE_AI_OPENAI_BASE_URL=http://127.0.0.1:18080/v1
export DOKENE_AI_EVAL_LIVE=true DOKENE_AI_OPENAI_MODEL=gpt-6-luna DOKENE_AI_EVAL_PROMPT_POLICY=phase2-default DOKENE_AI_EVAL_MIN_SUCCESS_RATIO=0.01 DOKENE_AI_EVAL_FAMILY_COVERAGE=$1 DOKENE_AI_EVAL_REPORT_NAME=$2
./gradlew aiEvalLive --console=plain > /home/stevdrey/qa253/out/$2-gradle.log 2>&1; echo "exit=$?" >> /home/stevdrey/qa253/out/$2-gradle.log
