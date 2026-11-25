# 从项目或解包后的目录运行，数据库和凭据均保存在该目录的 data/ 下。
start_local_postgres() {
    if [[ -n "${BLOG_DATABASE_URL:-}" ]]; then
        return
    fi
    local runtime="${BLOG_LOCAL_DATA_DIRECTORY:-$PWD/data}"
    local port="${BLOG_LOCAL_DATABASE_PORT:-55432}"
    local cluster="$runtime/postgres" socket="$runtime/pg-socket"
    local secret="$runtime/postgres-password" socket_arg password tool
    for tool in initdb pg_ctl psql openssl; do
        if ! command -v "$tool" >/dev/null 2>&1; then
            printf '请安装 PostgreSQL 服务端工具和 OpenSSL，或配置 BLOG_DATABASE_URL 使用已有数据库。\n' >&2
            return 1
        fi
    done
    if [[ ! "$port" =~ ^[1-9][0-9]{0,4}$ ]] || (( port > 65535 )); then
        printf 'BLOG_LOCAL_DATABASE_PORT 需要是有效的 TCP 端口。\n' >&2
        return 1
    fi

    install -d -m 700 "$runtime" "$socket" || return
    if [[ ! -s "$secret" ]]; then
        (umask 077 && openssl rand -hex 32 > "$secret") || return
    fi
    password="$(cat "$secret")" || return
    if [[ ! "$password" =~ ^[0-9a-f]{64}$ ]]; then
        printf '本地 PostgreSQL 密码文件格式无效。\n' >&2
        return 1
    fi
    if [[ ! -f "$cluster/PG_VERSION" ]]; then
        initdb -D "$cluster" --username=postgres --auth-local=trust --auth-host=scram-sha-256 \
            --encoding=UTF8 --locale=C > "$runtime/initdb.log" || return
    fi
    if ! pg_ctl -D "$cluster" status >/dev/null 2>&1; then
        printf -v socket_arg '%q' "$socket"
        pg_ctl -D "$cluster" -l "$runtime/postgres.log" -o "-p $port -h 127.0.0.1 -k $socket_arg" \
            -w start >/dev/null || return
    fi
    # 只有保存在 0700 目录内的 Unix socket 可免密码访问本地管理角色。
    env -u PGHOSTADDR -u PGSERVICE psql -X -q -w -v ON_ERROR_STOP=1 \
        -h "$socket" -p "$port" -U postgres -d postgres <<SQL || return
SELECT 'CREATE ROLE ease2k_blog_local LOGIN PASSWORD ''$password'''
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ease2k_blog_local') \gexec
SELECT 'CREATE DATABASE ease2k_blog_local OWNER ease2k_blog_local'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'ease2k_blog_local') \gexec
SQL
    export BLOG_DATABASE_URL="jdbc:postgresql://127.0.0.1:$port/ease2k_blog_local"
    export BLOG_DATABASE_USERNAME=ease2k_blog_local BLOG_DATABASE_PASSWORD="$password"
    export PGHOST=127.0.0.1 PGHOSTADDR=127.0.0.1 PGPORT="$port" PGDATABASE=ease2k_blog_local
    export PGUSER="$BLOG_DATABASE_USERNAME" PGPASSWORD="$password"
    unset PGSERVICE
}

stop_local_postgres() {
    local cluster="${BLOG_LOCAL_DATA_DIRECTORY:-$PWD/data}/postgres"
    if [[ -f "$cluster/PG_VERSION" ]] && pg_ctl -D "$cluster" status >/dev/null 2>&1; then
        pg_ctl -D "$cluster" -m fast -w stop
    fi
}
