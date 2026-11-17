"""Import verified local covers by exact book ID, preserving original data and volumes."""
import argparse
import gzip
import hashlib
import json
import re
import subprocess
import urllib.request
import zipfile
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCE = Path(r'D:\tmp\novel_book_covers_export.zip')
WRITER = 'novel-plus-local-front-1'
REDIS = 'novel-plus-local-redis-1'
MYSQL = ['docker', 'exec', '-i', 'novel-plus-local-mysql-1', 'sh', '-c',
         'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --user=root --default-character-set=utf8mb4 --batch --raw --skip-column-names novel_plus']
CACHE_KEYS = ['indexBookSettingsKey:v2', 'indexBookSettingsKey', 'indexClickBankBookKey', 'indexNewBookKey', 'indexUpdateBookKey']
parser = argparse.ArgumentParser()
parser.add_argument('phase', choices=('backup', 'stage', 'import', 'verify', 'rollback'))
parser.add_argument('--evidence-directory', type=Path, required=True)
args = parser.parse_args()
OUT = (ROOT / args.evidence_directory).resolve()
assert OUT.is_relative_to(ROOT / 'doc') and re.fullmatch(r'covers-import-\d{8}-\d{6}', OUT.name)
VALIDATION = json.loads((OUT / 'archive-validation.json').read_text(encoding='utf-8'))
assert VALIDATION['ready'] and VALIDATION['verifiedImages'] == VALIDATION['libraryBooks']
IMAGES = {v['id']: v for v in VALIDATION['images']}
TARGET = '/data/pic/localPic/cover'
STAGE = '/data/pic/localPic/cover-stage-' + OUT.name

def save(name, data):
    (OUT / name).write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding='utf-8')

def run(command):
    return subprocess.run(command, capture_output=True, check=True).stdout

def sql(query, log='sql-error.log'):
    result = subprocess.run(MYSQL, input=query.encode('utf-8'), capture_output=True)
    if result.returncode:
        (OUT / 'backup' / log).write_bytes(result.stderr)
        raise RuntimeError('SQL operation failed; inspect the private ' + log)
    return [line.split('\t') for line in result.stdout.decode('utf-8').splitlines()]

def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()

def containers():
    names = run(['docker', 'ps', '-a', '--format', '{{.Names}}']).decode().splitlines()
    return {v['Name'][1:]: dict(id=v['Id'], image=v['Config']['Image'], started=v['State']['StartedAt'],
             mounts=[(m['Type'], m.get('Name', m['Source']), m['Destination']) for m in v['Mounts']])
            for v in json.loads(run(['docker', 'inspect', *names]))}

def book_rows():
    return [json.loads(r[0]) for r in sql("SELECT JSON_OBJECT('id',CAST(id AS CHAR),'book_name',book_name,'author_name',author_name,'cat_name',cat_name,'pic_url',pic_url) FROM book ORDER BY id;")]

def snapshot():
    tables = [r[0] for r in sql('SHOW TABLES;')]
    columns = [r[0] for r in sql('SHOW COLUMNS FROM book;') if r[0] != 'pic_url']
    assert all(re.fullmatch(r'[a-z_0-9]+', c) for c in columns)
    fingerprint = sql('SELECT CAST(id AS CHAR),SHA2(CAST(JSON_ARRAY(' + ','.join('`' + c + '`' for c in columns) + ') AS CHAR),256) FROM book ORDER BY id;')
    preserved = [t for t in tables if t not in ('book', 'book_index', 'book_content')]
    return dict(counts={t: int(sql('SELECT COUNT(*) FROM `' + t + '`;')[0][0]) for t in tables},
                bookNonPictureFingerprints=fingerprint,
                otherTableChecksums=sql('CHECKSUM TABLE ' + ','.join('`' + t + '`' for t in preserved) + ' EXTENDED;'),
                contentSamples=sql('SELECT CAST(index_id AS CHAR),SHA2(content,256) FROM book_content WHERE index_id IN (2106418564102668288,2107067362993360896);'))

def file_hashes(directory):
    command = ['docker', 'exec', WRITER, 'sh', '-c', 'find "$1" -maxdepth 1 -type f -name "*.jpg" -exec sha256sum {} +', 'cover-hashes', directory]
    lines = run(command).decode().splitlines()
    return {Path(line.split(None, 1)[1].strip()).stem: line.split()[0] for line in lines}

def clear_book_cache():
    # Only the five book presentation caches; sessions, captcha and other caches stay intact.
    run(['docker', 'exec', REDIS, 'redis-cli', 'DEL', *CACHE_KEYS])

def cover_sql(rows, reverse=False):
    assert len(rows) == len(IMAGES) and {r['id'] for r in rows} == set(IMAGES)
    values = []
    for row in rows:
        book_id = row['id']
        assert re.fullmatch(r'[1-9]\d{0,18}', book_id)
        previous, expected = row['pic_url'], IMAGES[book_id]['picUrl']
        if reverse:
            previous, expected = expected, previous
        def value(text):
            return 'CONVERT(0x' + text.encode('utf-8').hex() + ' USING utf8mb4)'
        values.append('(' + book_id + ',' + value(previous) + ',' + value(expected) + ')')
    expected = len(rows)
    # A failed row-count check aborts mysql batch processing before COMMIT, rolling back.
    return ('SET NAMES utf8mb4;\n'
            'CREATE TEMPORARY TABLE covers_desired (id BIGINT PRIMARY KEY, old_pic_url VARCHAR(200), new_pic_url VARCHAR(200));\n'
            'CREATE TEMPORARY TABLE covers_import_guard (n INT NOT NULL, CHECK (n=' + str(expected) + '));\n'
            'INSERT INTO covers_desired VALUES\n' + ',\n'.join(values) + ';\n'
            'START TRANSACTION;\n'
            'UPDATE book b JOIN covers_desired d ON b.id=d.id SET b.pic_url=d.new_pic_url,b.update_time=b.update_time WHERE b.pic_url=d.old_pic_url;\n'
            'INSERT INTO covers_import_guard(n) SELECT ROW_COUNT();\n'
            'COMMIT;\nSELECT n FROM covers_import_guard;\n')

if args.phase == 'backup':
    backup = OUT / 'backup'
    assert not backup.exists(), 'Backup already exists; preserve the original baseline'
    backup.mkdir()
    run(['icacls', str(backup), '/inheritance:r', '/grant:r', r'lylboy:(OI)(CI)F', r'SYSTEM:(OI)(CI)F', r'Administrators:(OI)(CI)F'])
    assert digest(SOURCE) == VALIDATION['sha256']
    books = book_rows()
    mapped = {v['id']: v for v in json.loads((OUT / 'cover-mapping.json').read_text(encoding='utf-8'))}
    assert len(books) == len(mapped) and {b['id'] for b in books} == set(mapped)
    for book in books:
        for field in ('book_name', 'author_name', 'cat_name'):
            assert book[field] == mapped[book['id']][field]
        assert book['pic_url'] != IMAGES[book['id']]['picUrl'], 'Covers already imported'
    save('books-before.json', books)
    save('database-before.json', snapshot())
    save('runtime-before.json', containers())
    raw = run(['docker', 'exec', 'novel-plus-local-mysql-1', 'sh', '-c',
               'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump --user=root --single-transaction --skip-lock-tables --no-tablespaces --set-gtid-purged=OFF --default-character-set=utf8mb4 novel_plus book'])
    with gzip.open(backup / 'book-before.sql.gz', 'wb') as stream:
        stream.write(raw)
    (backup / 'restore-picture-urls.sql').write_text(cover_sql(books, reverse=True), encoding='utf-8')
    present = subprocess.run(['docker', 'exec', WRITER, 'test', '-e', TARGET]).returncode == 0
    assert not present, 'Existing cover directory detected; preserve it and revise the import destination before proceeding'
    save('storage-before.json', dict(coverDirectoryExisted=present, target=TARGET, volume='novel-plus-local_shared-files'))
    caches = {}
    for key in CACHE_KEYS:
        command = ['docker', 'exec', REDIS, 'redis-cli', '--raw']
        caches[key] = dict(present=run(command + ['EXISTS', key]).strip() == b'1',
                           value=run(command + ['GET', key]).decode('utf-8').removesuffix('\n'),
                           ttl=int(run(command + ['TTL', key]).strip()))
    (backup / 'book-cache-before.json').write_text(json.dumps(caches, ensure_ascii=False, indent=2), encoding='utf-8')
    save('backup-manifest.json', {p.name: digest(p) for p in backup.iterdir() if p.is_file()})
    save('import-state.json', dict(phase='backed_up', time=datetime.now(timezone.utc).isoformat()))
    print(json.dumps(dict(backedUpBooks=len(books), originalCoverDirectory=present, originalVolumePreserved=True)))

elif args.phase == 'stage':
    assert json.loads((OUT / 'import-state.json').read_text())['phase'] == 'backed_up'
    assert digest(SOURCE) == VALIDATION['sha256']
    local = OUT / 'staged-covers'
    local.mkdir(exist_ok=False)
    with zipfile.ZipFile(SOURCE) as archive:
        assert archive.testzip() is None
        for book_id, item in IMAGES.items():
            raw = archive.read(item['archivePath'])
            assert hashlib.sha256(raw).hexdigest() == item['sha256']
            (local / (book_id + '.jpg')).write_bytes(raw)
    run(['docker', 'exec', WRITER, 'sh', '-c', 'test ! -e "$1" && mkdir -p /data/pic/localPic && mkdir "$1"', 'cover-stage', STAGE])
    run(['docker', 'cp', str(local) + '/.', WRITER + ':' + STAGE + '/'])
    expected = {book_id: v['sha256'] for book_id, v in IMAGES.items()}
    assert file_hashes(STAGE) == expected
    # Resolve both absolute paths and keep this non-destructive rename within the shared picture directory.
    checked = run(['docker', 'exec', WRITER, 'readlink', '-m', STAGE, TARGET]).decode().splitlines()
    assert checked == [STAGE, TARGET] and all(p.startswith('/data/pic/localPic/') for p in checked)
    run(['docker', 'exec', WRITER, 'sh', '-c', 'test -d "$1" && test ! -e "$2" && mv -T "$1" "$2"', 'publish-covers', STAGE, TARGET])
    assert file_hashes(TARGET) == expected
    # The reader already serves this local prefix from the same read-only mounted volume.
    first = next(iter(IMAGES.values()))
    with urllib.request.urlopen('http://127.0.0.1:18987' + first['picUrl'], timeout=20) as response:
        assert hashlib.sha256(response.read()).hexdigest() == first['sha256']
    save('import-state.json', dict(phase='files_ready', time=datetime.now(timezone.utc).isoformat(), imageCount=len(IMAGES), target=TARGET))
    print(json.dumps(dict(stagedImages=len(IMAGES), fileHashesMatched=True, publicImageMatched=True)))

elif args.phase == 'import':
    assert json.loads((OUT / 'import-state.json').read_text())['phase'] == 'files_ready'
    before = json.loads((OUT / 'books-before.json').read_text(encoding='utf-8'))
    assert book_rows() == before, 'Book metadata changed since backup'
    query = cover_sql(before)
    (OUT / 'import-picture-urls.sql').write_text(query, encoding='utf-8')
    updated = sql(query, 'cover-update-error.log')
    assert updated == [[str(len(before))]]
    clear_book_cache()
    save('import-state.json', dict(phase='imported', time=datetime.now(timezone.utc).isoformat(), updatedBooks=len(before), invalidatedCacheKeys=CACHE_KEYS))
    print(json.dumps(dict(updatedBooks=len(before), invalidatedBookCaches=len(CACHE_KEYS), bookUpdateTimesPreserved=True)))

elif args.phase == 'verify':
    assert json.loads((OUT / 'import-state.json').read_text())['phase'] == 'imported'
    before = json.loads((OUT / 'database-before.json').read_text(encoding='utf-8'))
    after = snapshot()
    assert after == before, 'Data beyond picture URLs changed; inspect the snapshots'
    rows = book_rows()
    assert len(rows) == len(IMAGES)
    assert all(row['pic_url'] == IMAGES[row['id']]['picUrl'] for row in rows)
    assert file_hashes(TARGET) == {book_id: v['sha256'] for book_id, v in IMAGES.items()}
    original = json.loads((OUT / 'runtime-before.json').read_text(encoding='utf-8'))
    now = containers()
    assert json.loads(json.dumps(now)) == original, 'Container identity, image, start time or mounts changed'
    assert digest(SOURCE) == VALIDATION['sha256']
    save('database-after.json', after)
    save('storage-verification.json', dict(verifiedImages=len(IMAGES), allHashesMatched=True, volume='novel-plus-local_shared-files',
         target=TARGET, originalSourceUnchanged=True, originalContainersPreserved=len(now), onlyPicUrlChanged=True))
    print(json.dumps(dict(verifiedBookUrls=len(rows), verifiedFileHashes=len(IMAGES), otherDataPreserved=True, containersPreserved=len(now))))

elif args.phase == 'rollback':
    assert json.loads((OUT / 'import-state.json').read_text())['phase'] == 'imported'
    restored = sql((OUT / 'backup/restore-picture-urls.sql').read_text(encoding='utf-8'), 'cover-rollback-error.log')
    assert restored == [[str(len(IMAGES))]]
    clear_book_cache()
    # Retain imported image files and the original volume; revert only the backed-up URL field.
    save('rollback.json', dict(time=datetime.now(timezone.utc).isoformat(), restoredBookUrls=len(IMAGES), importedFilesRetained=True))
    save('import-state.json', dict(phase='rolled_back', time=datetime.now(timezone.utc).isoformat()))
    print(json.dumps(dict(restoredUrls=len(IMAGES), filesAndVolumePreserved=True)))
