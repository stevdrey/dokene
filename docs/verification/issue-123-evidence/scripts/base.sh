# Suite #123 prelude (sourced from the QA worktree root)
. /home/stevdrey/qa121/s/env121.sh
OUT=/home/stevdrey/qa123/out
backend_log() { docker compose logs --no-log-prefix backend 2>&1; }
