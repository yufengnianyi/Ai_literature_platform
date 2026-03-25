；立刻00# !/usr/bin/env bash

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
