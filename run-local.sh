#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

source scripts/java-tools.sh
source scripts/local-postgres.sh

if [[ ! -d frontend/node_modules ]]; then
    if [[ "${BLOG_OFFLINE:-0}" == 1 ]]; then
        npm --prefix frontend ci --offline
    else
        npm --prefix frontend ci --prefer-offline
    fi
fi
npm --prefix frontend run build
export BLOG_EDITOR_DIRECTORY="${BLOG_EDITOR_DIRECTORY:-$PWD/editor}"
export BLOG_RATINGS_LIVE="${BLOG_RATINGS_LIVE:-false}"
start_local_postgres
if [[ "${BLOG_OFFLINE:-0}" == 1 ]]; then
    exec "$maven" -o spring-boot:run
fi
exec "$maven" spring-boot:run
