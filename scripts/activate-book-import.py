"""Atomically exchange verified book tables while retaining the previous library."""
import argparse
import json
import re
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('evidence', type=Path)
parser.add_argument('--rollback', action='store_true')
parser.add_argument('--retry-after-rollback', action='store_true', help='Reuse this run\'s verified empty rollback schema after an interrupted health-check recovery.')
args = parser.parse_args()
evidence = args.evidence.resolve()
import_state = json.loads((evidence / 'import-state.json').read_text(encoding='utf-8'))
assert import_state['phase'] == 'imported'
stage = import_state['stageSchema']
suffix = evidence.name.removeprefix('books-import-').replace('-', '_')
old = 'novel_books_before_' + suffix
assert all(re.fullmatch(r'[a-zA-Z0-9_]+', n) for n in (stage, old))
book_tables = import_state['bookTables']
related = ['user_bookshelf', 'user_read_history']
tables = sorted(book_tables + related)
apps = ['novel-plus-local-front-realtest-1', 'novel-plus-local-front-1',
        'novel-plus-local-admin-1', 'novel-plus-local-crawl-1',
        'novel-plus-auth-history-fix-20261007']
cache_keys = ['indexBookSettingsKey:v2', 'indexClickBankBookKey',
              'indexNewBookKey', 'indexUpdateBookKey']
mysql = ['docker', 'exec', '-i', 'novel-plus-local-mysql-1', 'sh', '-c',
         'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --user=root --default-character-set=utf8mb4 --batch --raw --skip-column-names']


def query(sql, timeout=120):
    result = subprocess.run(mysql, input=sql.encode('utf-8'), capture_output=True, timeout=timeout)
    if result.returncode:
        (evidence / 'backup/activation-sql-error.log').write_bytes(result.stderr)
        raise RuntimeError('SQL exchange failed; see the private error log.')
    return [line.split('\t') for line in result.stdout.decode('utf-8').splitlines()]


def containers():
    data = json.loads(subprocess.run(['docker', 'inspect', *apps], capture_output=True, check=True).stdout)
    return {i['Name'].lstrip('/'): {'id': i['Id'], 'image': i['Config']['Image'],
                                  'running': i['State']['Running'],
                                  'health': i['State'].get('Health', {}).get('Status')} for i in data}


def clear_book_cache():
    subprocess.run(['docker', 'exec', 'novel-plus-local-redis-1', 'redis-cli', 'DEL', *cache_keys],
                   capture_output=True, check=True)


def exchange(reverse=False):
    moves = []
    for table in tables:
        if reverse:
            moves += [f'`novel_plus`.`{table}` TO `{stage}`.`{table}`',
                      f'`{old}`.`{table}` TO `novel_plus`.`{table}`']
        else:
            moves += [f'`novel_plus`.`{table}` TO `{old}`.`{table}`',
                      f'`{stage}`.`{table}` TO `novel_plus`.`{table}`']
    return 'RENAME TABLE ' + ',\n'.join(moves) + ';\n'


def start_and_wait(names):
    subprocess.run(['docker', 'start', *names], capture_output=True, check=True, timeout=120)
    deadline = time.monotonic() + 150
    while time.monotonic() < deadline:
        states = containers()
        healthy = True
        for name in names:
            item = states[name]
            if not item['running']:
                healthy = False
            elif item['health'] != 'healthy':
                if item['health'] is None and name == 'novel-plus-auth-history-fix-20261007':
                    probe = subprocess.run(['docker', 'exec', name, 'curl', '-fsS', '--max-time', '5',
                                            '-o', '/dev/null', 'http://127.0.0.1:8080/'],
                                           capture_output=True, timeout=15)
                    healthy = healthy and probe.returncode == 0
                else:
                    healthy = False
        if healthy:
            return states
        time.sleep(3)
    raise RuntimeError('An application did not become healthy after the database exchange.')


before_apps = containers()
running = [name for name, value in before_apps.items() if value['running']]
assert running, 'No running application was found.'
if args.rollback:
    active = json.loads((evidence / 'activation-state.json').read_text(encoding='utf-8'))
    assert active['phase'] == 'activated'
    subprocess.run(['docker', 'stop', '--time', '20', *running], capture_output=True, check=True, timeout=150)
    query(exchange(True))
    clear_book_cache()
    restored = start_and_wait(running)
    active.update(phase='rolled_back', rollbackTime=datetime.now(timezone.utc).isoformat(), containers=restored)
    (evidence / 'activation-state.json').write_text(json.dumps(active, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'phase': 'rolled_back', 'previousSchema': old, 'containersHealthy': True}), flush=True)
    raise SystemExit(0)

validation = json.loads((evidence / 'library-stage-validation.json').read_text(encoding='utf-8'))
assert validation['ready'], 'Verified staging data is required.'
old_exists = query(f"SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='{old}';")[0][0] == '1'
if args.retry_after_rollback:
    recovery = json.loads((evidence / 'activation-recovery.json').read_text(encoding='utf-8'))
    assert recovery['phase'] == 'verified_automatic_rollback' and old_exists
    assert query(f"SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='{old}';")[0][0] == '0'
    assert int(query('SELECT COUNT(*) FROM novel_plus.book;')[0][0]) == recovery['liveBooks']
    assert int(query(f'SELECT COUNT(*) FROM `{stage}`.book;')[0][0]) == validation['books']
else:
    assert not old_exists
assert not query("SELECT EVENT_OBJECT_TABLE FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA='novel_plus';")
assert not query("SELECT TABLE_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA='novel_plus' AND REFERENCED_TABLE_NAME IS NOT NULL;")
cache_backup = {}
for key in cache_keys:
    prefix = ['docker', 'exec', 'novel-plus-local-redis-1', 'redis-cli', '--raw']
    present = subprocess.run(prefix + ['EXISTS', key], capture_output=True, check=True).stdout.strip() == b'1'
    value = subprocess.run(prefix + ['GET', key], capture_output=True, check=True).stdout
    ttl = int(subprocess.run(prefix + ['TTL', key], capture_output=True, check=True).stdout)
    cache_backup[key] = {'present': present, 'value': value.decode('utf-8').removesuffix('\n'), 'ttl': ttl}
(evidence / 'backup/book-cache-before.json').write_text(json.dumps(cache_backup, ensure_ascii=False, indent=2), encoding='utf-8')
(evidence / 'containers-before-exchange.json').write_text(json.dumps(before_apps, indent=2), encoding='utf-8')
(evidence / 'rollback-tables.sql').write_text(exchange(True), encoding='utf-8')
state = {'phase': 'preparing', 'stageSchema': stage, 'previousSchema': old, 'tables': tables,
         'startedAt': datetime.now(timezone.utc).isoformat(), 'originalDataVolumePreserved': True}
swapped = False
stopped = False
try:
    print(json.dumps({'phase': 'pausing-applications', 'applications': running}), flush=True)
    stopped = True
    subprocess.run(['docker', 'stop', '--time', '20', *running], capture_output=True, check=True, timeout=150)
    before = json.loads((evidence / 'database-before.json').read_text(encoding='utf-8'))
    preserved = sorted(set(before['tables']) - set(tables))
    current_checksums = {r[0].split('.')[-1]: r[1] for r in query('CHECKSUM TABLE ' + ','.join('`novel_plus`.`'+t+'`' for t in preserved) + ' EXTENDED;')}
    assert all(current_checksums[t] == before['checksums'][t] for t in preserved), 'An account or configuration changed while staging; inspect before exchanging.'
    history_counts = {}
    for table in related:
        table_exists = query(f"SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='{stage}' AND TABLE_NAME='{table}';")[0][0] == '1'
        if table_exists:
            assert args.retry_after_rollback and query(f'SELECT COUNT(*) FROM `{stage}`.`{table}`;')[0][0] == '0'
        else:
            query(f'CREATE TABLE `{stage}`.`{table}` LIKE `novel_plus`.`{table}`;')
        history_counts[table] = {'before': int(query(f'SELECT COUNT(*) FROM `novel_plus`.`{table}`;')[0][0])}
        query(f'INSERT INTO `{stage}`.`{table}` SELECT h.* FROM `novel_plus`.`{table}` h '
              f'WHERE EXISTS (SELECT 1 FROM `{stage}`.book b WHERE b.id=h.book_id) '
              f'AND (h.pre_content_id IS NULL OR EXISTS (SELECT 1 FROM `{stage}`.book_index i WHERE i.id=h.pre_content_id AND i.book_id=h.book_id));')
        history_counts[table]['retained'] = int(query(f'SELECT COUNT(*) FROM `{stage}`.`{table}`;')[0][0])
    if not old_exists:
        query(f'CREATE DATABASE `{old}` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;')
    query(exchange())
    swapped = True
    clear_book_cache()
    print(json.dumps({'phase': 'tables-exchanged', 'previousLibraryRetainedIn': old, 'relatedRecords': history_counts}), flush=True)
    after_apps = start_and_wait(running)
    assert all(before_apps[n]['id'] == after_apps[n]['id'] and before_apps[n]['image'] == after_apps[n]['image'] for n in apps)
    state.update(phase='activated', finishedAt=datetime.now(timezone.utc).isoformat(),
                 relatedRecords=history_counts, preservedTableChecksums=current_checksums,
                 containers=after_apps, containersHealthy=True, invalidatedCacheKeys=cache_keys)
except Exception:
    if swapped:
        query(exchange(True))
        clear_book_cache()
        state['phase'] = 'automatically_rolled_back'
    else:
        state['phase'] = 'failed_before_exchange'
    if stopped:
        start_and_wait(running)
    raise
finally:
    (evidence / 'activation-state.json').write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps({'phase': state['phase'], 'books': validation['books'], 'chapters': validation['chapters'],
                  'contentRows': validation['contentRows'], 'previousSchema': old, 'containersHealthy': True}), flush=True)
