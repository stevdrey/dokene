# JDK 26 must be active
cd <repo root>; export DOKENE_AI_OPENAI_API_KEY="$(grep ^DOKENE_AI_OPENAI_API_KEY= .env | cut -d= -f2-)"   # only the key is exported: sourcing the whole .env makes the Spring test context try OIDC discovery
export DOKENE_AI_EVAL_LIVE=true DOKENE_AI_OPENAI_MODEL=gpt-6-luna DOKENE_AI_EVAL_PROMPT_POLICY=phase2-default DOKENE_AI_EVAL_REPORT_NAME=live-baseline-9e1d039
cd backend; ./gradlew aiEvalLive --console=plain > live-gradle.log 2>&1; echo "exit=$?" >> live-gradle.log
# Deterministic run (no key, no network): cd backend && ./gradlew test --tests '*ai.eval*'
# Repeat runs used the same command with DOKENE_AI_EVAL_REPORT_NAME=live-repeat1-9e1d039 and live-repeat2-9e1d039.
# Comparison: ./gradlew aiEvalCompare -Pbaseline=build/reports/ai-eval/<a>.json -Pcandidate=build/reports/ai-eval/<b>.json
# Grading: python3 -I scripts/grade.py build/reports/ai-eval/live-repeat1-9e1d039.json live-baseline-graded.json
