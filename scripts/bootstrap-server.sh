#!/usr/bin/env bash

set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

require_command git
require_command docker
require_command curl

if [ ! -f "${PROJECT_ROOT}/.env" ]; then
  cp "${PROJECT_ROOT}/.env.example" "${PROJECT_ROOT}/.env"
  cat <<'EOF'
Created .env from .env.example.
Edit .env with the server-only values, then rerun:
  bash scripts/bootstrap-server.sh
EOF
  exit 1
fi

print_step "Validating repository remote"
ensure_git_remote

print_step "Building and starting the production stack"
compose_prod up -d --build

print_step "Running deployment checks"
bash scripts/check-prod.sh
