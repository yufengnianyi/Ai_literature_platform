#!/usr/bin/env bash

set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

require_command git
require_command docker
require_command curl

ensure_env_file
ensure_git_remote
ensure_clean_worktree

branch="$(deploy_branch)"
current_branch="$(git rev-parse --abbrev-ref HEAD)"
previous_head="$(git rev-parse HEAD)"

[ "$current_branch" = "$branch" ] || fail "Checked out branch is ${current_branch}. Switch to ${branch} before deploying."

print_step "Fetching ${branch} from origin"
git fetch --prune origin "$branch"

print_step "Pulling the latest code"
git pull --ff-only origin "$branch"

new_head="$(git rev-parse HEAD)"

print_step "Rebuilding the production stack"
compose_prod up -d --build

print_step "Running post-deploy checks"
bash scripts/check-prod.sh

if [ "$previous_head" != "$new_head" ] && docs_changed_between "$previous_head" "$new_head"; then
  if [ "${AUTO_REBUILD_RAG:-0}" = "1" ]; then
    print_step "Docs changed; rebuilding RAG automatically"
    bash scripts/rebuild-rag.sh
  else
    cat <<EOF

Docs changed between ${previous_head} and ${new_head}.
Rebuild RAG with:
  bash scripts/rebuild-rag.sh

Set AUTO_REBUILD_RAG=1 before running this script if you want the rebuild to happen automatically.
EOF
  fi
fi
