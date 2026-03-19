#!/usr/bin/env bash

set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

require_command curl

health_url="$(api_base_url)/actuator/health"
status_url="$(api_base_url)/rag/ingestions/status"
rebuild_url="$(api_base_url)/rag/ingestions?mode=rebuild"

print_step "Checking backend health"
health_response="$(wait_for_http "$health_url" 30 2)" || fail "Backend health endpoint is not reachable at ${health_url}"
printf '%s\n' "$health_response"

print_step "RAG status before rebuild"
before_response="$(curl --silent --show-error --fail "$status_url")"
printf '%s\n' "$before_response"

print_step "Triggering RAG rebuild"
rebuild_response="$(curl --silent --show-error --fail -X POST "$rebuild_url")"
printf '%s\n' "$rebuild_response"

print_step "RAG status after rebuild"
after_response="$(curl --silent --show-error --fail "$status_url")"
printf '%s\n' "$after_response"
