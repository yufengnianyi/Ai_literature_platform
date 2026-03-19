#!/usr/bin/env bash

set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

require_command docker
require_command curl

project_name="$(compose_project_name)"
health_url="$(api_base_url)/actuator/health"
rag_status_url="$(api_base_url)/rag/ingestions/status"
legacy_containers="$(docker ps --format '{{.Names}}' | grep '^demo_01-' || true)"

print_step "Current containers for ${project_name}"
compose_prod ps

if [ -n "$legacy_containers" ]; then
  printf 'Legacy containers are still running:\n%s\n' "$legacy_containers" >&2
  cat <<'EOF' >&2
Stop them with:
  docker compose -p demo_01 -f docker-compose.prod.yml down --remove-orphans
EOF
  exit 1
fi

print_step "Waiting for the backend health endpoint"
health_response="$(wait_for_http "$health_url" 30 2)" || fail "Backend health endpoint is not reachable at ${health_url}"
printf '%s\n' "$health_response"
case "$health_response" in
  *'"status":"UP"'*) ;;
  *) fail "Health endpoint did not report status UP." ;;
esac

print_step "Fetching RAG ingestion status"
rag_response="$(curl --silent --show-error --fail "$rag_status_url")"
printf '%s\n' "$rag_response"

print_step "Checking embedding row count"
row_count="$(compose_prod exec -T postgres psql -U "$(postgres_user)" -d "$(postgres_db)" -t -A -c "select count(*) from $(vector_table);")"
printf '%s\n' "$row_count"
