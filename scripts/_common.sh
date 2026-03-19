#!/usr/bin/env bash

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"

cd "$PROJECT_ROOT"

print_step() {
  printf '\n[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"
}

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "Missing required command: $1"
}

read_env_value() {
  local key="$1"
  local default_value="${2:-}"
  local env_file="${PROJECT_ROOT}/.env"
  local line
  local value

  if [ -f "$env_file" ]; then
    line="$(grep -E "^${key}=" "$env_file" | tail -n 1 || true)"
    if [ -n "$line" ]; then
      value="${line#*=}"
      value="${value%$'\r'}"
      printf '%s\n' "$value"
      return 0
    fi
  fi

  printf '%s\n' "$default_value"
}

compose_project_name() {
  read_env_value "COMPOSE_PROJECT_NAME" "ai_literature"
}

deploy_branch() {
  read_env_value "DEPLOY_BRANCH" "main"
}

web_port() {
  read_env_value "WEB_PORT" "8088"
}

postgres_db() {
  read_env_value "POSTGRES_DB" "demo_01"
}

postgres_user() {
  read_env_value "POSTGRES_USER" "demo_01"
}

vector_table() {
  read_env_value "APP_AI_RAG_VECTOR_TABLE" "embedding_store"
}

api_base_url() {
  printf 'http://127.0.0.1:%s/api' "$(web_port)"
}

compose_prod() {
  docker compose -p "$(compose_project_name)" -f "$COMPOSE_FILE" "$@"
}

ensure_env_file() {
  [ -f "${PROJECT_ROOT}/.env" ] || fail "Missing .env. Copy .env.example to .env on the server and fill in the real values."
}

ensure_git_remote() {
  git config --get remote.origin.url >/dev/null 2>&1 || fail "Git remote origin is not configured. Push this project to a remote repository first."
}

ensure_clean_worktree() {
  local status

  status="$(git status --porcelain)"
  [ -z "$status" ] || fail "Git worktree is not clean. Commit, stash, or discard server-side changes before deploying."
}

wait_for_http() {
  local url="$1"
  local attempts="${2:-30}"
  local delay_seconds="${3:-2}"
  local attempt=1
  local response

  while [ "$attempt" -le "$attempts" ]; do
    if response="$(curl --silent --show-error --fail "$url" 2>/dev/null)"; then
      printf '%s\n' "$response"
      return 0
    fi
    sleep "$delay_seconds"
    attempt=$((attempt + 1))
  done

  return 1
}

docs_changed_between() {
  local old_ref="$1"
  local new_ref="$2"

  ! git diff --quiet "$old_ref" "$new_ref" -- src/main/resources/docs
}
