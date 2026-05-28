#!/usr/bin/env bash

set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

output="${OUTPUT:-ai-literature-images.tar}"
business_only="${BUSINESS_ONLY:-false}"
pull="${PULL:-true}"

require_command docker

build_args=(build)
if [ "$pull" = "true" ]; then
  if [ "$business_only" != "true" ]; then
    compose_prod pull postgres grobid neo4j
  fi
  build_args+=(--pull)
fi
build_args+=(backend web)

print_step "Building application images"
compose_prod "${build_args[@]}"

if [ "$business_only" = "true" ]; then
  images=(
    "${BACKEND_IMAGE:-$(read_env_value "BACKEND_IMAGE" "ai_literature-backend:latest")}"
    "${WEB_IMAGE:-$(read_env_value "WEB_IMAGE" "ai_literature-web:latest")}"
  )
else
  mapfile -t images < <(compose_prod config --images | awk 'NF' | sort -u)
fi

print_step "Saving images to ${output}"
docker image inspect "${images[@]}" >/dev/null
docker save -o "$output" "${images[@]}"

printf 'Saved images:\n'
printf ' - %s\n' "${images[@]}"
