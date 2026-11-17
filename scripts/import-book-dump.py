"""Import only inspected book tables into a fresh schema; keep the live DB intact."""
import argparse
import gzip
import json
import re
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('evidence', type=Path)
args = parser.parse_args()
evidence = args.evidence.resolve()
inventory = json.loads((evidence / 'source-dump-inventory.json').read_text(encoding='utf-8'))
ddl = json.loads((evidence / 'book-table-definitions.json').read_text(encoding='utf-8'))
backup = json.loads((evidence / 'backup/database-backup-manifest.json').read_text(encoding='utf-8'))
assert Path(backup['path']).is_file(), 'Verified current-library backup is required.'
run = evidence.name
assert re.fullmatch(r'books-import-\d{8}-\d{6}', run)
schema = 'novel_books_stage_' + run.removeprefix('books-import-').replace('-', '_')
allowed = {'book', 'book_author', 'book_category', 'book_comment',
           'book_comment_reply', 'book_index', 'book_screen_bullet', 'book_setting',
           'book_content', *(f'book_content{i}' for i in range(10))}
assert set(ddl) == allowed
assert set(ddl) == set(inventory['bookTables'])
mysql = ['docker', 'exec', '-i', 'novel-plus-local-mysql-1', 'sh', '-c',
         'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --user=root --default-character-set=utf8mb4 --binary-mode --batch --raw --skip-column-names']


def mysql_command(database=None):
    assert database is None or re.fullmatch(r'[a-zA-Z0-9_]+', database)
    return mysql[:-1] + [mysql[-1] + (' ' + database if database else '')]


def query(sql, database=None):
    command = mysql_command(database)
    result = subprocess.run(command, input=sql.encode('utf-8'), capture_output=True, timeout=120)
    if result.returncode:
        (evidence / 'backup/last-sql-error.log').write_bytes(result.stderr)
        raise RuntimeError('SQL operation failed; see the private last-sql-error.log.')
    return [line.split('\t') for line in result.stdout.decode('utf-8').splitlines()]


assert query(f"SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='{schema}';")[0][0] == '0', 'Fresh staging schema required.'
original_pool = int(query('SELECT @@innodb_buffer_pool_size;')[0][0])
query(f'CREATE DATABASE `{schema}` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;')
for table, definition in ddl.items():
    assert definition.startswith(f'CREATE TABLE `{table}` (')
    assert not re.search(r'\b(?:TRIGGER|DEFINER|REFERENCES|DATABASE)\b', definition, re.I), 'Unexpected schema object in book DDL.'
    query(definition, schema)
state = {'stageSchema': schema, 'sourceDump': inventory['path'], 'sourceSha256': inventory['sha256'],
         'bookTables': sorted(ddl), 'phase': 'importing', 'startedAt': datetime.now(timezone.utc).isoformat(),
         'originalBufferPoolBytes': original_pool, 'liveDatabaseModified': False}
(evidence / 'import-state.json').write_text(json.dumps(state, indent=2), encoding='utf-8')
query('SET GLOBAL innodb_buffer_pool_size=1073741824;')
started = last_progress = time.monotonic()
insert_counts = {table: 0 for table in ddl}
insert_bytes = {table: 0 for table in ddl}
uncompressed = 0
pending = 0
status = 'failed'
try:
    with (evidence / 'backup/import-mysql-error.log').open('xb') as errors, \
         (evidence / 'backup/import-mysql-output.log').open('xb') as output:
        proc = subprocess.Popen(mysql_command(schema), stdin=subprocess.PIPE, stdout=output, stderr=errors)
        try:
            proc.stdin.write(b"SET SESSION sql_log_bin=0; SET NAMES utf8mb4; SET TIME_ZONE='+00:00'; SET SESSION sql_mode='NO_AUTO_VALUE_ON_ZERO'; SET SESSION unique_checks=0; SET SESSION foreign_key_checks=0; SET autocommit=0;\n")
            with Path(inventory['path']).open('rb') as raw, gzip.GzipFile(fileobj=raw) as stream:
                while line := stream.readline(64 * 1024 * 1024):
                    assert line.endswith(b'\n') or len(line) < 64 * 1024 * 1024, 'Oversized dump line.'
                    uncompressed += len(line)
                    insert = re.match(rb'INSERT INTO `([a-zA-Z0-9_]+)` ', line[:200])
                    if insert and (table := insert[1].decode('ascii')) in ddl:
                        assert line.rstrip().endswith(b';'), 'Incomplete INSERT.'
                        proc.stdin.write(line)
                        insert_counts[table] += 1
                        insert_bytes[table] += len(line)
                        pending += len(line)
                        if pending >= 32 * 1024 * 1024:
                            proc.stdin.write(b'COMMIT;\n')
                            proc.stdin.flush()
                            pending = 0
                    now = time.monotonic()
                    if now - last_progress >= 25:
                        progress = {'phase': 'staging-import', 'table': table,
                                    'compressedBytesRead': raw.tell(), 'compressedBytes': inventory['compressedBytes'],
                                    'uncompressedBytesRead': uncompressed, 'insertStatements': sum(insert_counts.values()),
                                    'elapsedSeconds': round(now-started)}
                        (evidence / 'import-progress.json').write_text(json.dumps(progress, indent=2), encoding='utf-8')
                        print(json.dumps(progress), flush=True)
                        last_progress = now
            for table in ddl:
                assert insert_counts[table] == inventory['tables'][table]['insertStatements'], f'INSERT count mismatch for {table}'
                assert insert_bytes[table] == inventory['tables'][table]['insertBytes'], f'INSERT byte mismatch for {table}'
            assert uncompressed == inventory['uncompressedBytes'], 'Incomplete gzip read.'
            proc.stdin.write(b'COMMIT; SET autocommit=1;\n')
            proc.stdin.close()
            assert proc.wait(timeout=300) == 0, 'MySQL import failed; see private import-mysql-error.log.'
            status = 'imported'
        finally:
            if proc.poll() is None:
                proc.terminate()
                proc.wait(timeout=30)
finally:
    query(f'SET GLOBAL innodb_buffer_pool_size={original_pool};')
    state['phase'] = status
    state['elapsedSeconds'] = round(time.monotonic()-started, 2)
    state['insertCounts'] = insert_counts
    state['insertBytes'] = insert_bytes
    state['bufferPoolRestored'] = int(query('SELECT @@innodb_buffer_pool_size;')[0][0]) == original_pool
    (evidence / 'import-state.json').write_text(json.dumps(state, indent=2), encoding='utf-8')
print(json.dumps({'phase': status, 'stageSchema': schema, 'insertStatements': sum(insert_counts.values()),
                  'elapsedSeconds': state['elapsedSeconds'], 'liveDatabaseModified': False}), flush=True)
