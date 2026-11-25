#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"
source scripts/local-postgres.sh

export BLOG_EDITOR_DIRECTORY="${BLOG_EDITOR_DIRECTORY:-$PWD/editor}"
export BLOG_RATINGS_LIVE="${BLOG_RATINGS_LIVE:-false}"

case "${1:-}" in
    db)
        shift
        if [[ $# -eq 0 ]]; then
            set -- psql -X
        fi
        start_local_postgres
        exec "$@"
        ;;
    stop-db)
        stop_local_postgres
        exit
        ;;
esac

if [[ "$(uname -s)" == Darwin ]] && jdk27="$(/usr/libexec/java_home -F -v 27 2>/dev/null)"; then
    export JAVA_HOME="$jdk27"
fi
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
jar=blog.jar
if [[ ! -f "$jar" ]]; then
    jar=target/release/blog.jar
fi
if [[ ! -f "$jar" ]]; then
    printf '缺少 blog.jar，请先运行 ./build-release.sh。\n' >&2
    exit 1
fi
if [[ "${1:-}" == setup-owner ]]; then
    exec "$java_bin" -jar "$jar" setup-owner "$BLOG_EDITOR_DIRECTORY"
fi
start_local_postgres
exec "$java_bin" -jar "$jar" "$@"
