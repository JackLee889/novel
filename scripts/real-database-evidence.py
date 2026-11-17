"""Read-only production DB inventory/fingerprints and a streaming pre-test backup."""
from pathlib import Path
import argparse
import gzip
import hashlib
import json
import re
import shutil
import subprocess
from datetime import datetime, timezone

root = Path(__file__).resolve().parent.parent
run = (root / 'doc/frontend-realtest-current.txt').read_text(encoding='utf-8-sig').strip()
assert re.fullmatch(r'frontend-realtest-\d{8}-\d{6}', run)
evidence = root / 'doc' / run
runtime = json.loads((evidence / 'test-runtime.json').read_text(encoding='utf-8-sig'))
parser = argparse.ArgumentParser()
parser.add_argument('--phase', choices=['before', 'after'], required=True)
parser.add_argument('--backup', action='store_true')
parser.add_argument('--evidence-directory', type=Path, help='Save a follow-up snapshot separately from the original acceptance run.')
args = parser.parse_args()
if args.evidence_directory:
    evidence = (root / args.evidence_directory).resolve()
    assert evidence.is_relative_to(root / 'doc') and evidence.is_dir(), 'Evidence directory must already exist under doc.'
compose = ['docker', 'compose', '--project-directory', runtime['deploymentRoot'], '-f', runtime['composeFile']]
mysql = compose + ['exec', '-T', 'mysql', 'sh', '-c',
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --user=root --default-character-set=utf8mb4 --batch --raw --skip-column-names novel_plus']


def query(sql):
    result = subprocess.run(mysql, input=sql.encode('utf-8'), capture_output=True, check=True, timeout=120)
    return [line.split('\t') for line in result.stdout.decode('utf-8').splitlines()]


tables = [row[0] for row in query("SELECT table_name FROM information_schema.tables WHERE table_schema='novel_plus' AND table_type='BASE TABLE' ORDER BY table_name;")]
assert tables and 'book' in tables and 'book_index' in tables, 'Original novel schema unavailable.'
assert all(re.fullmatch('[a-zA-Z0-9_]+', name) for name in tables)
counts = {}
for table in tables:
    counts[table] = int(query(f'SELECT COUNT(*) FROM `{table}`;')[0][0])
checksums = {row[0].split('.')[-1]: row[1] for row in query('CHECKSUM TABLE ' + ','.join('`'+t+'`' for t in tables) + ' EXTENDED;')}
books = query('SELECT CAST(id AS CHAR),book_name,author_name,word_count,CAST(last_index_id AS CHAR),book_status FROM book ORDER BY word_count DESC LIMIT 20;')
state = {'time': datetime.now(timezone.utc).isoformat(), 'phase': args.phase, 'tables': counts,
         'checksums': checksums, 'books': [dict(zip(['id','name','author','wordCount','lastChapterId','status'], row)) for row in books]}
(evidence / ('database-'+args.phase+'.json')).write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding='utf-8')
if args.backup:
    assert args.phase == 'before'
    destination = evidence / 'backup/novel-plus-before.sql.gz'
    dump = compose + ['exec', '-T', 'mysql', 'sh', '-c',
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump --user=root --default-character-set=utf8mb4 --single-transaction --routines --events --triggers --hex-blob --set-gtid-purged=OFF --no-tablespaces novel_plus']
    error_path = evidence / 'backup/mysqldump.log'
    with destination.open('xb') as target, error_path.open('wb') as error:
        proc = subprocess.Popen(dump, stdout=subprocess.PIPE, stderr=error)
        with gzip.GzipFile(fileobj=target, mode='wb') as compressed:
            shutil.copyfileobj(proc.stdout, compressed, length=1024*1024)
        proc.stdout.close()
        assert proc.wait(timeout=120) == 0, 'Database dump failed; inspect mysqldump.log.'
    with gzip.open(destination, 'rb') as stream:
        length = 0
        tail = b''
        while chunk := stream.read(1024*1024):
            length += len(chunk)
            tail = (tail + chunk)[-1024:]
        assert b'Dump completed on' in tail, 'Dump completion marker missing.'
    with destination.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    metadata = {'path': str(destination), 'bytes': destination.stat().st_size, 'uncompressedBytes': length,
                'sha256': digest}
    (evidence / 'backup/database-backup-manifest.json').write_text(json.dumps(metadata, indent=2), encoding='utf-8')
if args.phase == 'after':
    before = json.loads((evidence / 'database-before.json').read_text(encoding='utf-8-sig'))
    changes = {t: {'before': before['tables'].get(t), 'after': counts.get(t),
                   'checksumBefore': before['checksums'].get(t), 'checksumAfter': checksums.get(t)}
               for t in set(tables) | set(before['tables'])
               if before['tables'].get(t) != counts.get(t) or before['checksums'].get(t) != checksums.get(t)}
    (evidence / 'database-comparison.json').write_text(json.dumps({'unchanged': not changes, 'changes': changes}, indent=2), encoding='utf-8')
    print(json.dumps({'databaseUnchanged': not changes, 'changedTables': list(changes)}))
print(json.dumps({'phase': args.phase, 'tables': len(tables), 'books': counts['book'],
                  'chapters': counts['book_index'], 'contentRows': sum(n for t,n in counts.items() if re.fullmatch(r'book_content\d*',t))}))
