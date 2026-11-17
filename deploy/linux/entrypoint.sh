#!/bin/sh
set -eu

fail() { printf '%s\n' "novel-plus: $*" >&2; exit 1; }
required() {
    # Read the named variable without evaluating its contents as shell code.
    value=$(printenv "$1") || fail "$1 is required"
    case "$value" in ''|CHANGE_ME*|REPLACE_ME*|__REQUIRED__*) fail "$1 must be configured" ;; esac
}

init_data() {
    [ "$(id -u)" = 0 ] || fail 'init-data requires its dedicated root initializer'
    case "${APP_UID:-}" in ''|*[!0-9]*|0) fail 'APP_UID must be a nonzero numeric uid' ;; esac
    case "${APP_GID:-}" in ''|*[!0-9]*|0) fail 'APP_GID must be a nonzero numeric gid' ;; esac
    for directory in /data/pic /data/books /data/seo; do
        [ -d "$directory" ] || fail "missing volume at $directory"
        [ ! -L "$directory" ] || fail "volume root must not be a symlink: $directory"
        if [ "${INIT_EXISTING_DATA_OWNERSHIP:-false}" = true ]; then
            # Opt in only after checking the selected volume names. Never remove data.
            chown -R "$APP_UID:$APP_GID" "$directory"
        else
            chown "$APP_UID:$APP_GID" "$directory"
        fi
        chmod u+rwx,g+rwx "$directory"
    done
    printf '%s\n' 'Data directory ownership prepared; all existing files retained.'
}

init_db() {
    required MYSQL_HOST
    required MYSQL_DATABASE
    required MYSQL_USERNAME
    required MYSQL_PWD
    # This path only bootstraps the NEW MySQL service in this Compose project.
    [ "$MYSQL_HOST" = mysql ] || fail 'db-init refuses external database hosts'
    [ "${DB_INIT_ACK:-}" = "initialize-empty:$MYSQL_DATABASE" ] || fail 'set DB_INIT_ACK=initialize-empty:<database> for this one-time operation'
    case "$MYSQL_DATABASE" in ''|*[!A-Za-z0-9_]*) fail 'database name must contain only letters, digits and underscores' ;; esac
    case "${DB_INIT_SQL:-}" in ''|*[!A-Za-z0-9._-]*|.*) fail 'DB_INIT_SQL must name a reviewed .sql file in /sql' ;; esac
    case "$DB_INIT_SQL" in *.sql) ;; *) fail 'DB_INIT_SQL must end in .sql' ;; esac
    sql_file="/sql/$DB_INIT_SQL"
    [ -f "$sql_file" ] && [ -r "$sql_file" ] || fail 'selected SQL file is missing or unreadable'
    required DB_INIT_SQL_SHA256
    required ADMIN_INITIAL_PASSWORD
    required WEBSITE_DOMAIN
    initial_site_name=${WEBSITE_NAME:-小说精品屋}
    initial_site_name_hex=$(printf '%s' "$initial_site_name" | od -An -v -tx1 | tr -d ' \n') || fail 'cannot encode initial site name'
    initial_site_domain_hex=$(printf '%s' "$WEBSITE_DOMAIN" | od -An -v -tx1 | tr -d ' \n') || fail 'cannot encode initial site domain'
    actual_hash=$(sha256sum "$sql_file")
    actual_hash=${actual_hash%% *}
    [ "$actual_hash" = "$DB_INIT_SQL_SHA256" ] || fail 'SQL SHA256 does not match the reviewed file'
    # The import user is scoped to this schema. Also reject schema-switch directives.
    if grep -Eiq '^[[:space:]]*(USE[[:space:]]|CREATE[[:space:]]+DATABASE|DROP[[:space:]]+DATABASE)' "$sql_file"; then
        fail 'initial SQL must not switch, create or drop a database'
    fi
    umask 077
    init_work_dir=$(mktemp -d /tmp/novel-db-init.XXXXXX) || fail 'cannot create private initialization workspace'
    initial_md5_file="$init_work_dir/admin-first.md5"
    reviewed_sql_file="$init_work_dir/reviewed.sql"
    prepared_sql_file="$init_work_dir/init.sql"
    cleanup_init_db() {
        # Only the three files created by this invocation are removed.
        rm -f -- "$initial_md5_file" "$reviewed_sql_file" "$prepared_sql_file"
        rmdir -- "$init_work_dir"
    }
    trap cleanup_init_db 0
    trap 'exit 1' HUP INT TERM
    # Match MD5Utils.encrypt("admin", password): salted UTF-8 input followed
    # by a second digest of the first 16 RAW bytes. No reversible password
    # representation enters SQL or a temporary file.
    if ! printf '%s' 'admin1qazxsw2' "$ADMIN_INITIAL_PASSWORD" | openssl dgst -md5 -binary > "$initial_md5_file"; then
        fail 'initial admin password hashing failed'
    fi
    initial_md5_size=$(wc -c < "$initial_md5_file") || fail 'cannot verify first admin digest'
    [ "$initial_md5_size" -eq 16 ] || fail 'initial admin digest must contain exactly 16 bytes'
    initial_admin_hash_output=$(openssl dgst -md5 "$initial_md5_file") || fail 'second admin digest failed'
    initial_admin_hash=${initial_admin_hash_output##* }
    case "$initial_admin_hash" in ''|*[!0-9a-f]*) fail 'initial admin hash must be lowercase hex' ;; esac
    [ "${#initial_admin_hash}" -eq 32 ] || fail 'initial admin hash must contain exactly 32 hex digits'
    unset ADMIN_INITIAL_PASSWORD value initial_admin_hash_output
    # Snapshot the reviewed SQL and recheck it to close the host-file-change
    # window. Prepare a complete input BEFORE invoking the database client.
    cp "$sql_file" "$reviewed_sql_file" || fail 'cannot snapshot reviewed initialization SQL'
    reviewed_hash=$(sha256sum "$reviewed_sql_file") || fail 'cannot verify reviewed initialization SQL'
    reviewed_hash=${reviewed_hash%% *}
    [ "$reviewed_hash" = "$DB_INIT_SQL_SHA256" ] || fail 'reviewed SQL changed during preparation'
    # Lock, empty-schema gate and import share ONE client connection. The batch
    # client stops on the first error; never use --force. A refused gate prepares
    # an intentionally invalid query BEFORE any initialization DDL is read.
    if ! cat > "$prepared_sql_file" <<'SQL_GATE'
SET @novel_init_lock_name = CONCAT('np-init-', LEFT(SHA2(DATABASE(), 256), 48));
SET @novel_init_lock = GET_LOCK(@novel_init_lock_name, 15);
SET @novel_init_objects = (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE())
    + (SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema = DATABASE())
    + (SELECT COUNT(*) FROM information_schema.events WHERE event_schema = DATABASE());
SET @novel_init_gate = IF(@novel_init_lock = 1 AND @novel_init_objects = 0,
    'SELECT ''Initializing verified empty schema'' AS status',
    'SELECT * FROM information_schema.novel_plus_db_init_refused_existing_objects_or_lock');
PREPARE novel_init_guard FROM @novel_init_gate;
EXECUTE novel_init_guard;
DEALLOCATE PREPARE novel_init_guard;
SQL_GATE
    then
        fail 'cannot prepare initialization gate'
    fi
    printf "SET @novel_initial_admin_hash = '%s';\n" "$initial_admin_hash" >> "$prepared_sql_file" || fail 'cannot prepare initial admin hash'
    # Public site strings are UTF-8 hex-encoded to preserve literals without SQL injection.
    printf "SET @novel_initial_site_name = CONVERT(UNHEX('%s') USING utf8mb4);\n" "$initial_site_name_hex" >> "$prepared_sql_file" || fail 'cannot prepare initial site name'
    printf "SET @novel_initial_site_domain = CONVERT(UNHEX('%s') USING utf8mb4);\n" "$initial_site_domain_hex" >> "$prepared_sql_file" || fail 'cannot prepare initial site domain'
    cat "$reviewed_sql_file" >> "$prepared_sql_file" || fail 'cannot append reviewed initialization SQL'
    printf '\nSELECT RELEASE_LOCK(@novel_init_lock_name);\n' >> "$prepared_sql_file" || fail 'cannot complete initialization SQL'
    [ -s "$prepared_sql_file" ] || fail 'prepared initialization SQL is empty'
    mysql --protocol=TCP --host="$MYSQL_HOST" --port="${MYSQL_PORT:-3306}" \
        --user="$MYSQL_USERNAME" --database="$MYSQL_DATABASE" --default-character-set=utf8mb4 --batch < "$prepared_sql_file" \
        || fail 'one-time initialization failed'
    printf '%s\n' 'One-time empty-schema initialization completed. No old SQL is run on normal startup.'
}

healthcheck() {
    case "${APP_MODULE:-}" in
        front) path=/user/login.html ;;
        admin) path=/login ;;
        crawl) path=/login.html ;;
        *) fail 'unknown APP_MODULE' ;;
    esac
    exec curl --fail --silent --show-error --max-time 8 --output /dev/null \
        "http://127.0.0.1:${SERVER_PORT:-8080}$path"
}

run_app() {
    [ "$(id -u)" != 0 ] || fail 'applications must run as a nonroot user'
    required MYSQL_PASSWORD
    required REDIS_PASSWORD
    [ -r /app/app.jar ] || fail 'module JAR is missing'
    [ -r /app/config/application.yaml ] || fail 'runtime config bind mount is missing'
    if [ "${SPRING_DATASOURCE_DRIVER_CLASS_NAME:-}" = org.apache.shardingsphere.driver.ShardingSphereDriver ]; then
        [ -r /app/config/shardingsphere-jdbc.yml ] || fail 'private shard config is missing or unreadable; preserve topology and grant the application uid read access'
    fi
    for directory in /data/pic /data/books; do
        [ -d "$directory" ] && [ -w "$directory" ] || fail "volume is not writable: $directory; run init-data after checking volume names"
    done
    case "${APP_MODULE:-}" in
        front)
            if [ "${SEO_ENABLED:-false}" = true ]; then
                [ -d /data/seo ] && [ -w /data/seo ] || fail 'SEO volume is not writable: /data/seo'
            fi
            required JWT_SECRET
            required CACHE_MANAGER_PASSWORD
            case "${TEMPLATE_NAME:-green}" in green|orange) ;; *) fail 'release supports green and orange templates' ;; esac
            [ -r "/app/templates/${TEMPLATE_NAME:-green}/html/user/login.html" ] || fail 'selected frontend template is missing'
            if [ "${READER_ENABLED:-true}" = true ] && [ "${READER_TTS_ENABLED:-true}" = true ]; then
                resources=${READER_TTS_RESOURCE_DIRECTORY:-/opt/reader-resources}
                [ -d "$resources" ] || fail 'reader resources bind mount is missing'
                find "$resources" -type f -name '*.wasm' -print -quit | grep -q . || fail 'reader WASM resource is missing'
                find "$resources" -type f -name '*.data' -print -quit | grep -q . || fail 'reader model resource is missing'
            fi
            ;;
        admin) ;;
        crawl) required CRAWL_ADMIN_PASSWORD ;;
        *) fail 'APP_MODULE must be front, admin or crawl' ;;
    esac
    # JAVA_TOOL_OPTIONS is interpreted by the JVM, so shell word splitting and
    # command substitution are never used for user-provided JVM options.
    exec java -jar /app/app.jar "$@"
}

case "${1:-run}" in
    init-data) shift; init_data "$@" ;;
    init-db) shift; init_db "$@" ;;
    healthcheck) shift; healthcheck "$@" ;;
    run) [ "$#" = 0 ] || shift; run_app "$@" ;;
    *) run_app "$@" ;;
esac
