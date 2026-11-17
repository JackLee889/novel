#!/bin/bash
set -euo pipefail
export LC_ALL=C

fail() { printf 'novel-plus migration: %s\n' "$*" >&2; exit 1; }
required() {
    local content
    content=$(printenv "$1") || fail "$1 is required"
    case "$content" in ''|CHANGE_ME*|REPLACE_ME*|__REQUIRED__*) fail "$1 must be configured" ;; esac
}

tables=(book_category book book_index book_content book_content{0..9} book_setting crawl_source crawl_batch_task crawl_single_task)
data_root=${MIGRATION_DATA_ROOT:-/migration-data}
tool_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
validator="$tool_root/validate-table.awk"
required MYSQL_HOST
required MYSQL_DATABASE
required MYSQL_USERNAME
required MYSQL_PWD
required MIGRATION_DATA_SHA256
required ADMIN_INITIAL_PASSWORD
required WEBSITE_DOMAIN
[[ "$MYSQL_HOST" == mysql ]] || fail 'refusing external database host'
[[ "$MYSQL_DATABASE" =~ ^[A-Za-z0-9_]+$ ]] || fail 'invalid database name'
[[ "${MYSQL_PORT:-3306}" =~ ^[0-9]+$ ]] || fail 'invalid database port'
[[ "${DATA_MIGRATION_ACK:-}" == "initialize-empty:$MYSQL_DATABASE" ]] || fail 'set DATA_MIGRATION_ACK=initialize-empty:<database> after checking NEW volume names'
[[ "$MIGRATION_DATA_SHA256" =~ ^[0-9a-f]{64}$ ]] || fail 'invalid reviewed SHA256SUMS digest'
[[ -d "$data_root" && ! -L "$data_root" && -r "$validator" ]] || fail 'missing data or validator'
mysql_client() {
    mysql --no-defaults --protocol=TCP --host="$MYSQL_HOST" --port="${MYSQL_PORT:-3306}" \
        --user="$MYSQL_USERNAME" --database="$MYSQL_DATABASE" --default-character-set=utf8mb4 \
        --binary-mode=1 --local-infile=0 --max-allowed-packet=1G --batch "$@"
}
# Fast readonly refusal avoids rescanning multi-GB exports on accidental retry.
# It is only a probe; the authoritative locked gate is repeated in the ONE
# importing connection, so this early check cannot authorize a racing import.
probe_objects=$(mysql_client --skip-column-names --execute="SELECT
    (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE())
    + (SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema=DATABASE())
    + (SELECT COUNT(*) FROM information_schema.events WHERE event_schema=DATABASE())") \
    || fail 'cannot inspect the NEW target schema'
[[ "$probe_objects" == 0 ]] || fail 'target schema already contains objects; refusing restore'
links=$(find "$data_root" -type l -print -quit) || fail 'cannot inspect data paths'
[[ -z "$links" ]] || fail 'migration data must not contain symlinks'
[[ -f "$data_root/SHA256SUMS" && -r "$data_root/SHA256SUMS" ]] || fail 'SHA256SUMS is missing'
reviewed_hash=$(sha256sum "$data_root/SHA256SUMS") || fail 'cannot hash SHA256SUMS'
[[ "${reviewed_hash%% *}" == "$MIGRATION_DATA_SHA256" ]] || fail 'SHA256SUMS differs from reviewed digest'
trusted_dump=0
review_marker="$tool_root/reviewed-data.sha256"
if [[ -f "$review_marker" && ! -L "$review_marker" ]] \
    && reviewed_dump_digest=$(awk 'NR==1 && length($0)==64 && /^[0-9a-f]+$/ {v=$0} END {if(NR!=1 || v=="") exit 1; print v}' "$review_marker") \
    && [[ "$reviewed_dump_digest" == "$MIGRATION_DATA_SHA256" ]]; then
    trusted_dump=1
    printf '%s\n' 'Using the privately reviewed export format; checksum seal remains mandatory.'
fi
awk '
BEGIN {
    must["schema.sql"]=1; must["manifest.json"]=1
    n=split("book_category book book_index book_content book_setting crawl_source crawl_batch_task crawl_single_task", t, " ")
    for(i=1;i<=n;i++) must["tables/" t[i] ".sql.gz"]=1
    for(i=0;i<10;i++) must["tables/book_content" i ".sql.gz"]=1
}
{
    if(length($1)!=64 || $1 !~ /^[0-9a-f]+$/ || substr($0,65,2)!="  ") exit 1
    p=substr($0,67)
    if(p !~ /^[A-Za-z0-9_.\/-]+$/ || p ~ /(^|\/)\.\.?($|\/)/ || p ~ /^\// || seen[p]++) exit 1
    if(!(p in must) && p!="cover-manifest.json" && p!~ /^pic\/[A-Za-z0-9_.\/-]+$/) exit 1
    found[p]=1
}
END { for(p in must) if(!(p in found)) exit 1 }
' "$data_root/SHA256SUMS" || fail 'checksum list has missing, duplicate or disallowed paths'
(cd -- "$data_root" && sha256sum --check --strict SHA256SUMS >/dev/null) || fail 'data checksum verification failed'
if grep -Eiq '^[[:space:]]*(USE[[:space:]]|CREATE[[:space:]]+DATABASE|DROP[[:space:]]+DATABASE)' "$data_root/schema.sql"; then
    fail 'reviewed schema must not switch, create or drop databases'
fi
for table in "${tables[@]}"; do
    gzip -t -- "$data_root/tables/$table.sql.gz" || fail "corrupt gzip: $table"
    gzip -dc -- "$data_root/tables/$table.sql.gz" | awk -v table="$table" -v emit=0 -v trusted_dump="$trusted_dump" -f "$validator" \
        || fail "SQL preflight failed: $table"
done
printf '%s\n' 'Verified checksums, 18 gzip streams and INSERT-only table data.'

umask 077
work_dir=$(mktemp -d /tmp/novel-migration.XXXXXX) || fail 'cannot create private digest workspace'
cleanup() { rm -f -- "$work_dir/admin-first.md5"; rmdir -- "$work_dir"; }
trap cleanup EXIT
trap 'exit 1' HUP INT TERM
# Exactly MD5Utils.encrypt("admin", password): second MD5 of the first RAW digest.
printf '%s' 'admin1qazxsw2' "$ADMIN_INITIAL_PASSWORD" | openssl dgst -md5 -binary > "$work_dir/admin-first.md5" \
    || fail 'admin digest failed'
[[ $(wc -c < "$work_dir/admin-first.md5") -eq 16 ]] || fail 'invalid raw admin digest'
digest_output=$(openssl dgst -md5 "$work_dir/admin-first.md5") || fail 'second admin digest failed'
admin_hash=${digest_output##* }
[[ "$admin_hash" =~ ^[0-9a-f]{32}$ ]] || fail 'invalid final admin hash'
unset ADMIN_INITIAL_PASSWORD digest_output
site_name_hex=$(printf '%s' "${WEBSITE_NAME:-小说精品屋}" | od -An -v -tx1 | tr -d ' \n') || fail 'cannot encode site name'
site_domain_hex=$(printf '%s' "$WEBSITE_DOMAIN" | od -An -v -tx1 | tr -d ' \n') || fail 'cannot encode site domain'

produce_sql() {
    # Every producer command handles failure explicitly: Bash errexit is disabled
    # inside a conditional pipeline. Never send COMMIT after an upstream failure.
    cat <<'SQL_GATE' || return 1
SET NAMES utf8mb4;
SET SESSION sql_mode='NO_AUTO_VALUE_ON_ZERO';
SET SESSION time_zone='+00:00';
SET @novel_migration_lock_name = CONCAT('np-init-', LEFT(SHA2(DATABASE(), 256), 48));
SET @novel_migration_lock = GET_LOCK(@novel_migration_lock_name, 15);
SET @novel_migration_objects = (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE())
    + (SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema=DATABASE())
    + (SELECT COUNT(*) FROM information_schema.events WHERE event_schema=DATABASE());
SET @novel_migration_gate = IF(DATABASE() IS NOT NULL AND @novel_migration_lock=1 AND @novel_migration_objects=0,
    'SELECT ''Restoring verified empty schema'' AS status',
    'SELECT * FROM information_schema.novel_plus_migration_refused_existing_objects_or_lock');
PREPARE novel_migration_guard FROM @novel_migration_gate;
EXECUTE novel_migration_guard;
DEALLOCATE PREPARE novel_migration_guard;
SQL_GATE
    printf "SET @novel_initial_admin_hash = '%s';\nSET @novel_initial_site_name = CONVERT(UNHEX('%s') USING utf8mb4);\nSET @novel_initial_site_domain = CONVERT(UNHEX('%s') USING utf8mb4);\n" \
        "$admin_hash" "$site_name_hex" "$site_domain_hex" || return 1
    cat -- "$data_root/schema.sql" || return 1
    printf '\nSET @novel_migration_business_rows = 0' || return 1
    for table in "${tables[@]}"; do
        printf ' + (SELECT COUNT(*) FROM `%s`)' "$table" || return 1
    done
    cat <<'SQL_DATA_GATE' || return 1
;
SET @novel_migration_gate = IF(@novel_migration_business_rows=0,
    'SELECT ''Importing 18 empty business tables'' AS status',
    'SELECT * FROM information_schema.novel_plus_migration_refused_nonempty_business_tables');
PREPARE novel_migration_guard FROM @novel_migration_gate;
EXECUTE novel_migration_guard;
DEALLOCATE PREPARE novel_migration_guard;
SET SESSION sql_mode='NO_AUTO_VALUE_ON_ZERO';
SET SESSION time_zone='+00:00';
START TRANSACTION;
SQL_DATA_GATE
    for table in "${tables[@]}"; do
        gzip -dc -- "$data_root/tables/$table.sql.gz" | awk -v table="$table" -v emit=1 -v trusted_dump="$trusted_dump" -f "$validator" || return 1
    done
    # The readonly mount is also sealed against host-side changes before commit.
    (cd -- "$data_root" && sha256sum --check --strict SHA256SUMS >/dev/null) || return 1
    printf '\nCOMMIT;\nSELECT RELEASE_LOCK(@novel_migration_lock_name);\n' || return 1
}

# One connection handles lock, BOTH empty guards, schema and transaction. No
# password is placed in argv; MYSQL_PWD remains the Compose environment contract.
if ! produce_sql | mysql_client; then
    fail 'restore failed; preserve the new target for diagnosis, do not retry on its existing schema'
fi
printf '%s\n' 'Novel/crawler restore committed. No old users, history or credentials were imported.'
