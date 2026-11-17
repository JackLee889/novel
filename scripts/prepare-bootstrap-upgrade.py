"""Snapshot the current security release before the scoped Bootstrap upgrade."""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import shutil
import subprocess

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'doc' / ('bootstrap-upgrade-' + datetime.now().strftime('%Y%m%d-%H%M%S'))
OUT.mkdir()

def save(name, data):
    (OUT / name).write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()

manifest = {}
for item in ['pom.xml', 'novel-common', 'novel-admin', 'novel-crawl', 'config', 'scripts']:
    base = ROOT / item
    for source in ([base] if base.is_file() else base.rglob('*')):
        if not source.is_file() or any(p in {'target', 'node_modules', '__pycache__', '.git'} for p in source.relative_to(ROOT).parts):
            continue
        rel = source.relative_to(ROOT)
        destination = OUT / 'before' / rel
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, destination)
        manifest[rel.as_posix()] = sha(source)
save('source-before.json', manifest)
front = {}
for source in (ROOT / 'novel-front/src').rglob('*'):
    if source.is_file(): front[source.relative_to(ROOT).as_posix()] = sha(source)
save('front-source-before.json', front)

names = subprocess.check_output(['docker', 'ps', '-a', '--format', '{{.Names}}'], text=True).splitlines()
containers = json.loads(subprocess.check_output(['docker', 'inspect', *names], text=True, encoding='utf-8'))
save('all-runtime-before.private.json', containers)
runtime = []
for row in containers:
    name = row['Name'][1:]
    if name not in {'novel-plus-local-front-1', 'novel-plus-local-front-realtest-1', 'novel-plus-local-admin-1', 'novel-plus-local-crawl-1'}: continue
    entry = dict(name=name, id=row['Id'], image=row['Config']['Image'], image_id=row['Image'], env=row['Config']['Env'],
                 mounts=row['Mounts'], networks=row['NetworkSettings']['Networks'],
                 jar_sha256=subprocess.check_output(['docker', 'exec', name, 'sha256sum', '/app/app.jar'], text=True).split()[0])
    runtime.append(entry)
    if name.endswith(('admin-1', 'crawl-1')):
        destination = OUT / 'runtime-backup' / name / 'app.jar'
        destination.parent.mkdir(parents=True)
        subprocess.run(['docker', 'cp', name + ':/app/app.jar', str(destination)], check=True, capture_output=True)
save('runtime-before.private.json', runtime)

deployment = Path('C:/Users/lylboy/Documents/Codex/2026-10-03/task/novel-plus-local')
paths = [deployment / 'compose.yaml', ROOT / 'doc/frontend-realtest-20261006-231709/front-realtest.override.yaml',
         ROOT / 'doc/chapter-feedback-20261007-164353/admin-feedback.override.yaml',
         ROOT / 'doc/security-dependency-upgrade-20261008-003503/security.override.yaml',
         ROOT / 'doc/security-dependency-upgrade-20261008-003503/update-local.ps1']
configs = []
for index, source in enumerate(paths):
    if not source.is_file(): continue
    dest = OUT / 'deployment-before' / (str(index) + '-' + source.name)
    dest.parent.mkdir(exist_ok=True)
    shutil.copy2(source, dest)
    configs.append(dict(path=str(source), backup=str(dest.relative_to(OUT)), sha256=sha(source)))
save('deployment-config-before.json', configs)

sql = "SET SESSION TRANSACTION READ ONLY; START TRANSACTION WITH CONSISTENT SNAPSHOT;\n"
sql += "SELECT JSON_OBJECT('kind','source','id',id,'status',source_status,'rule_sha',SHA2(crawl_rule,256)) FROM crawl_source ORDER BY id;\n"
for table in ('book', 'book_index', 'book_content', 'user', 'author', 'order_pay', 'user_buy_record', 'crawl_single_task', 'crawl_batch_task', 'sys_user', 'sys_user_role'):
    sql += f"SELECT JSON_OBJECT('kind','count','table','{table}','count',COUNT(*)) FROM `{table}`;\n"
sql += 'COMMIT;'
query = subprocess.run(['docker', 'exec', '-i', 'novel-plus-local-mysql-1', 'sh', '-c',
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --raw --batch --skip-column-names -uroot novel_plus'],
    input=sql, capture_output=True, text=True, encoding='utf-8', check=True)
save('database-before.json', [json.loads(line) for line in query.stdout.splitlines() if line])
save('baseline.json', dict(created=datetime.now(timezone.utc).isoformat(), sourceFiles=len(manifest), frontFiles=len(front),
    runtime=[{key: row[key] for key in ('name', 'id', 'image', 'jar_sha256')} for row in runtime],
    scope=['Bootstrap 5.3.8', 'admin and crawl resources'], originalVolumesPreserved=True))
(OUT / 'task-ledger.txt').write_text('Scope: upgrade Bootstrap to 5.3.8; keep other vendor versions, reader, disabled features and stopped sources.\n'
    'Baseline: current security-upgrade-20261008-003503 images.\n'
    'Pending: adapt resources; component and real rendered-page acceptance; build; candidate checks; reversible local release.\n', encoding='utf-8')
(ROOT / 'doc/bootstrap-upgrade-current.txt').write_text(OUT.name + '\n', encoding='utf-8')
print(json.dumps(dict(artifact=str(OUT), sourceFiles=len(manifest), runtimeJarsBackedUp=2, preservedContainers=len(containers))))
