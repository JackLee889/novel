"""Verify a finished staged library and the tables that must be preserved."""
import argparse
import json
import re
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('evidence', type=Path)
parser.add_argument('--database', help='Validate the activated database instead of staging.')
args = parser.parse_args()
evidence = args.evidence.resolve()
state = json.loads((evidence / 'import-state.json').read_text(encoding='utf-8'))
assert state['phase'] == 'imported', 'A successful complete import is required.'
database = args.database or state['stageSchema']
assert re.fullmatch(r'[a-zA-Z0-9_]+', database)
mysql = ['docker', 'exec', '-i', 'novel-plus-local-mysql-1', 'sh', '-c',
         'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --user=root --default-character-set=utf8mb4 --batch --raw --skip-column-names ' + database]


def query(sql, timeout=300):
    result = subprocess.run(mysql, input=sql.encode('utf-8'), capture_output=True, timeout=timeout)
    if result.returncode:
        (evidence / 'backup/validation-sql-error.log').write_bytes(result.stderr)
        raise RuntimeError('Validation SQL failed; see the private error log.')
    return [line.split('\t') for line in result.stdout.decode('utf-8').splitlines()]


def count(label, sql, timeout=300):
    value = int(query(sql, timeout)[0][0])
    print(json.dumps({'check': label, 'value': value}), flush=True)
    return value


started = time.monotonic()
tables = {table: count(table, f'SELECT COUNT(*) FROM `{table}`;') for table in state['bookTables']}
contents = [t for t in state['bookTables'] if re.fullmatch(r'book_content\d?', t)]
nonempty_contents = [t for t in contents if tables[t]]
assert nonempty_contents == ['book_content'], 'The local direct JDBC deployment requires the unsharded book_content table.'
checks = {
    'chaptersWithoutBooks': count('chaptersWithoutBooks', 'SELECT COUNT(*) FROM book_index i LEFT JOIN book b ON b.id=i.book_id WHERE b.id IS NULL;'),
    'chaptersWithoutContent': count('chaptersWithoutContent', "SELECT COUNT(*) FROM book_index i LEFT JOIN book_content c ON c.index_id=i.id WHERE i.storage_type='db' AND c.id IS NULL;"),
    'contentWithoutChapters': count('contentWithoutChapters', 'SELECT COUNT(*) FROM book_content c LEFT JOIN book_index i ON i.id=c.index_id WHERE i.id IS NULL;'),
    'duplicateContentChapterIds': count('duplicateContentChapterIds', 'SELECT COUNT(*)-COUNT(DISTINCT index_id) FROM book_content;'),
    'invalidLatestChapterReferences': count('invalidLatestChapterReferences', 'SELECT COUNT(*) FROM book b LEFT JOIN book_index i ON i.id=b.last_index_id AND i.book_id=b.id WHERE b.last_index_id IS NOT NULL AND i.id IS NULL;'),
    'recommendationsWithoutBooks': count('recommendationsWithoutBooks', 'SELECT COUNT(*) FROM book_setting s LEFT JOIN book b ON b.id=s.book_id WHERE b.id IS NULL;'),
    'booksWithoutCategories': count('booksWithoutCategories', 'SELECT COUNT(*) FROM book b LEFT JOIN book_category c ON c.id=b.cat_id WHERE b.cat_id IS NOT NULL AND c.id IS NULL;'),
    'booksWithoutChapters': count('booksWithoutChapters', 'SELECT COUNT(*) FROM book b WHERE NOT EXISTS (SELECT 1 FROM book_index i WHERE i.book_id=b.id);'),
    'emptyContentRows': count('emptyContentRows', "SELECT COUNT(*) FROM book_content WHERE content IS NULL OR OCTET_LENGTH(content)=0;", 600),
}
storage = {row[0]: int(row[1]) for row in query('SELECT storage_type,COUNT(*) FROM book_index GROUP BY storage_type;')}
columns = query(f"SELECT TABLE_NAME,COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COALESCE(COLUMN_DEFAULT,'__NULL__'),EXTRA FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='{database}' AND TABLE_NAME IN (" + ','.join("'"+t+"'" for t in state['bookTables']) + ') ORDER BY TABLE_NAME,ORDINAL_POSITION;')
original_columns = query("SELECT TABLE_NAME,COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COALESCE(COLUMN_DEFAULT,'__NULL__'),EXTRA FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='novel_plus' AND TABLE_NAME IN (" + ','.join("'"+t+"'" for t in state['bookTables']) + ') ORDER BY TABLE_NAME,ORDINAL_POSITION;')
sample_books = query('SELECT CAST(b.id AS CHAR),b.book_name,b.author_name,(SELECT COUNT(*) FROM book_index i WHERE i.book_id=b.id),CAST((SELECT i.id FROM book_index i WHERE i.book_id=b.id ORDER BY i.index_num LIMIT 1) AS CHAR),b.pic_url FROM book b ORDER BY b.id LIMIT 12;')
preserved = sorted(set(json.loads((evidence / 'database-before.json').read_text(encoding='utf-8'))['tables']) - set(state['bookTables']) - {'user_read_history', 'user_bookshelf'})
checksums = {row[0].split('.')[-1]: row[1] for row in query('CHECKSUM TABLE ' + ','.join('`novel_plus`.`'+t+'`' for t in preserved) + ' EXTENDED;')}
before = json.loads((evidence / 'database-before.json').read_text(encoding='utf-8'))
preservation = {t: {'before': before['checksums'][t], 'current': checksums[t]} for t in preserved if before['checksums'][t] != checksums[t]}
critical = ['chaptersWithoutBooks', 'chaptersWithoutContent', 'contentWithoutChapters',
            'duplicateContentChapterIds', 'recommendationsWithoutBooks', 'booksWithoutCategories',
            'invalidLatestChapterReferences', 'booksWithoutChapters']
known_empty_path = evidence / 'source-empty-chapters.json'
known_empty = json.loads(known_empty_path.read_text(encoding='utf-8')) if known_empty_path.exists() else []
empty_ids = [e['chapterId'] for e in known_empty]
assert all(re.fullmatch(r'[0-9]+', i) for i in empty_ids)
known_empty_count = count('recordedSourceEmptyChapters',
                         'SELECT COUNT(*) FROM book_content WHERE index_id IN (' + ','.join(empty_ids) + ') AND (content IS NULL OR OCTET_LENGTH(content)=0);') if empty_ids else 0
empty_matches_source = checks['emptyContentRows'] == known_empty_count == len(known_empty)
result = {'database': database, 'verifiedAt': datetime.now(timezone.utc).isoformat(),
          'tables': tables, 'books': tables['book'], 'chapters': tables['book_index'],
          'contentRows': sum(tables[t] for t in contents), 'storageTypes': storage,
          'checks': checks, 'columnDefinitionsCompatible': columns == original_columns,
          'sourceEmptyChapters': known_empty, 'emptyContentMatchesSource': empty_matches_source,
          'preservedTableCount': len(preserved), 'preservedTableChanges': preservation,
          'sampleBooks': [dict(zip(['id', 'name', 'author', 'chapters', 'firstChapterId', 'picture'], row)) for row in sample_books],
          'elapsedSeconds': round(time.monotonic()-started, 2)}
result['ready'] = (all(checks[c] == 0 for c in critical) and columns == original_columns
                   and storage == {'db': tables['book_index']} and not preservation and empty_matches_source)
name = 'library-after-validation.json' if args.database else 'library-stage-validation.json'
(evidence / name).write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(result, ensure_ascii=False), flush=True)
assert result['ready'], 'Library verification found a problem; inspect the recorded result before activating.'
