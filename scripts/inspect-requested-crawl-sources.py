"""Read-only preflight of the user archive and the explicitly named live database."""
import hashlib
import json
import pathlib
import subprocess
import zipfile
from datetime import datetime, timezone, timedelta

root = pathlib.Path(__file__).resolve().parent.parent
archive = pathlib.Path('D:/tmp/novel_crawl_db_export.zip')
review = pathlib.Path('D:/tmp/novel-crawl-db-review-20261007-222020')
stamp = datetime.now(timezone(timedelta(hours=8))).strftime('%Y%m%d-%H%M%S')
out = root / 'doc' / ('crawl-source-import-' + stamp)
out.mkdir()
(root / 'doc/crawl-source-import-current.txt').write_text(str(out) + '\n', encoding='utf-8')

def save(name, value):
    (out / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

def mysql(sql):
    result = subprocess.run(['docker', 'exec', '-i', 'novel-plus-local-mysql-1', 'sh', '-c',
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --default-character-set=utf8mb4 --raw --batch --skip-column-names -uroot novel_plus'],
        input=sql, capture_output=True, encoding='utf-8', check=True)
    return [json.loads(line) for line in result.stdout.splitlines() if line]

digest = hashlib.sha256(archive.read_bytes()).hexdigest()
previous = json.loads((review / 'inspection.json').read_text(encoding='utf-8'))
assert digest == previous['archive_sha256'], 'Source archive changed since inspection'
with zipfile.ZipFile(archive) as zip_file:
    raw_sql = zip_file.read('crawl_db_export/crawl_rules_only.sql').decode('utf-8')
    start = raw_sql.index('INSERT INTO `crawl_source`')
    (out / 'source-insert-excerpt.txt').write_text(raw_sql[start:start+1600], encoding='utf-8')
    for source_id, name in [(4, 'xxiangshu'), (12, 'biquge365')]:
        raw = zip_file.read('crawl_db_export/rules_json/' + name + '.json')
        original = json.loads(raw.decode('utf-8'))
        compatible = {key: value for key, value in original.items() if key != 'bookListMaxPage'}
        assert compatible == json.loads((review / (name + '.compatible.json')).read_text(encoding='utf-8'))
        (out / (str(source_id) + '.original.json')).write_bytes(raw)
        save(str(source_id) + '.compatible.json', compatible)
save('archive-verified.json', dict(archive=str(archive), sha256=digest,
    matches_prior_crc_verified_archive=True, requested_source_ids=[4, 12],
    rule_compatibility_changes={'12': {'removed_empty_unsupported_property': 'bookListMaxPage'}}))

names = ['novel-plus-local-mysql-1', 'novel-plus-local-redis-1', 'novel-plus-local-crawl-1',
    'novel-plus-local-admin-1', 'novel-plus-local-front-1', 'novel-plus-local-front-realtest-1',
    'novel-plus-local-gateway-1', 'novel-plus-local-gateway-realtest-1']
runtime = json.loads(subprocess.run(['docker', 'inspect', *names], capture_output=True,
    encoding='utf-8', check=True).stdout)
assert runtime[0]['Config']['Labels']['com.docker.compose.project'] == 'novel-plus-local'
assert runtime[0]['Config']['Labels']['com.docker.compose.service'] == 'mysql'
save('runtime-before.json', [dict(name=row['Name'].lstrip('/'), id=row['Id'],
    image=row['Config']['Image'], started_at=row['State']['StartedAt'],
    mounts=[{key: mount.get(key) for key in ['Type', 'Name', 'Source', 'Destination', 'RW']}
        for mount in row['Mounts']]) for row in runtime])

sql = '''SET SESSION TRANSACTION READ ONLY;
START TRANSACTION WITH CONSISTENT SNAPSHOT;
SELECT JSON_OBJECT('kind','server','version',VERSION(),'database',DATABASE(),'host',@@hostname);
SELECT JSON_OBJECT('kind','table','name',TABLE_NAME,'engine',ENGINE,'collation',TABLE_COLLATION)
FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='crawl_source';
SELECT JSON_OBJECT('kind','column','name',COLUMN_NAME,'type',COLUMN_TYPE,'nullable',IS_NULLABLE)
FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='crawl_source' ORDER BY ORDINAL_POSITION;
SELECT JSON_OBJECT('kind','trigger','name',TRIGGER_NAME) FROM information_schema.TRIGGERS
WHERE TRIGGER_SCHEMA=DATABASE() AND EVENT_OBJECT_TABLE='crawl_source';
SELECT JSON_OBJECT('kind','source','id',id,'source_name',source_name,'crawl_rule',crawl_rule,
'source_status',source_status,'create_time',DATE_FORMAT(create_time,'%Y-%m-%d %H:%i:%s'),
'update_time',DATE_FORMAT(update_time,'%Y-%m-%d %H:%i:%s')) FROM crawl_source ORDER BY id;
SELECT JSON_OBJECT('kind','book_sources','id',crawl_source_id,'books',COUNT(*)) FROM book GROUP BY crawl_source_id;
SELECT JSON_OBJECT('kind','counts','table','book','rows',COUNT(*)) FROM book
UNION ALL SELECT JSON_OBJECT('kind','counts','table','book_index','rows',COUNT(*)) FROM book_index
UNION ALL SELECT JSON_OBJECT('kind','counts','table','book_content','rows',COUNT(*)) FROM book_content
UNION ALL SELECT JSON_OBJECT('kind','counts','table','crawl_single_task','rows',COUNT(*)) FROM crawl_single_task
UNION ALL SELECT JSON_OBJECT('kind','counts','table','crawl_batch_task','rows',COUNT(*)) FROM crawl_batch_task
UNION ALL SELECT JSON_OBJECT('kind','counts','table','user','rows',COUNT(*)) FROM user
UNION ALL SELECT JSON_OBJECT('kind','counts','table','order_pay','rows',COUNT(*)) FROM order_pay
UNION ALL SELECT JSON_OBJECT('kind','counts','table','user_buy_record','rows',COUNT(*)) FROM user_buy_record;
COMMIT;
'''
(out / 'preflight-select.sql').write_text(sql, encoding='utf-8')
rows = mysql(sql)
save('database-before.json', rows)
assert any(row['kind'] == 'table' and row['engine'] == 'InnoDB' for row in rows)
assert not any(row['kind'] == 'trigger' for row in rows), 'Unexpected trigger on target table'
existing = [row for row in rows if row['kind'] == 'source']
assert not any(row['id'] in [4, 12] for row in existing), 'Requested source ID already exists; compare before importing'
print(json.dumps(dict(directory=str(out), archive_verified=True,
    existing_sources=[{key: row[key] for key in ['id', 'source_name', 'source_status']} for row in existing],
    linked_books=[row for row in rows if row['kind'] == 'book_sources'],
    requested_ids_absent=True), ensure_ascii=False))
