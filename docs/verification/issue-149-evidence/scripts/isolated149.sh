#!/usr/bin/env bash
# ABOUTME: Suite #149 prelude for an isolated run: selects the dokene-qa149 compose project with shifted host ports and
# ABOUTME: maps localhost:8080/8081/5173 to them for every curl through a generated .curlrc. Source it, then run as usual.
#
#   . docs/verification/issue-149-evidence/scripts/isolated149.sh
#   docker compose up -d --build        # builds the :qa149 images, starts the second stack
#   scripts/seed-local-qa.sh            # QA users and tenant A, which fixtures149.sh expects
#   bash docs/verification/issue-149-evidence/scripts/fixtures149.sh
#   bash docs/verification/issue-149-evidence/scripts/run-all.sh
_iso_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
export COMPOSE_PROJECT_NAME=dokene-qa149
export COMPOSE_FILE=compose.yaml:$_iso_dir/compose.isolated149.yaml
# stack149.sh adds its own override after these files
export QA149_COMPOSE_BASE="-f compose.yaml -f $_iso_dir/compose.isolated149.yaml"
export QA149_STUB_NAME=qa149-iso-stub
export CURL_HOME=$_iso_dir/../out/curlhome
mkdir -p "$CURL_HOME"
cat > "$CURL_HOME/.curlrc" <<'EOF'
connect-to = localhost:8080:127.0.0.1:28080
connect-to = localhost:8081:127.0.0.1:28081
connect-to = localhost:5173:127.0.0.1:25173
EOF
# The browser cases route Chromium through a mapping proxy instead (sec149-browser.mjs reads this).
export QA149_PORT_MAP=localhost:8080=127.0.0.1:28080,localhost:8081=127.0.0.1:28081,localhost:5173=127.0.0.1:25173
