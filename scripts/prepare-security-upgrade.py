"""Create an independent, reversible source/runtime snapshot before security work."""
from pathlib import Path
import hashlib
import json
import shutil
import subprocess
from datetime import datetime

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'doc' / ('security-dependency-upgrade-' + datetime.now().strftime('%Y%m%d-%H%M%S'))
OUT.mkdir()
SNAP = OUT / 'before'
SNAP.mkdir()
manifest = {}
for item in ['pom.xml', 'novel-common', 'novel-front', 'novel-crawl', 'novel-admin', 'templates', 'config', 'scripts']:
    base = ROOT / item
    files = [base] if base.is_file() else base.rglob('*')
    for src in files:
        if not src.is_file() or any(p in {'target', 'node_modules', '__pycache__', '.git'} for p in src.relative_to(ROOT).parts):
            continue
        rel = src.relative_to(ROOT)
        dest = SNAP / rel
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dest)
        manifest[rel.as_posix()] = hashlib.sha256(src.read_bytes()).hexdigest()
(OUT / 'source-before.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
names = ['novel-plus-local-front-realtest-1', 'novel-plus-local-front-1', 'novel-plus-local-admin-1', 'novel-plus-local-crawl-1']
runtime = []
for name in names:
    container = json.loads(subprocess.run(['docker', 'inspect', name], capture_output=True, check=True, encoding='utf-8').stdout)[0]
    entry = {'name': name, 'id': container['Id'], 'image': container['Config']['Image'], 'image_id': container['Image'],
             'env': container['Config']['Env'], 'mounts': container['Mounts'], 'network': container['NetworkSettings']['Networks'],
             'jar_sha256': subprocess.run(['docker', 'exec', name, 'sha256sum', '/app/app.jar'], capture_output=True, check=True, encoding='utf-8').stdout.split()[0]}
    runtime.append(entry)
    dest = OUT / 'runtime-backup' / name
    dest.mkdir(parents=True)
    subprocess.run(['docker', 'cp', name + ':/app/app.jar', str(dest / 'app.jar')], check=True, stdout=subprocess.DEVNULL)
(OUT / 'runtime-before.private.json').write_text(json.dumps(runtime, ensure_ascii=False, indent=2), encoding='utf-8')
base = Path('C:/Users/lylboy/Documents/Codex/2026-10-03/task/novel-plus-local')
for source in [base / 'compose.yaml', ROOT / 'doc/frontend-realtest-20261006-231709/front-realtest.override.yaml',
               ROOT / 'doc/chapter-feedback-20261007-164353/admin-feedback.override.yaml']:
    dest = OUT / 'deployment-before' / source.name
    dest.parent.mkdir(exist_ok=True)
    shutil.copy2(source, dest)
(OUT / 'baseline.json').write_text(json.dumps({'path': str(OUT), 'source_files': len(manifest),
    'runtime': [{k: x[k] for k in ['name', 'id', 'image', 'jar_sha256']} for x in runtime],
    'original_database_volumes_preserved': True}, indent=2), encoding='utf-8')
print(json.dumps({'artifact': str(OUT), 'source_files': len(manifest), 'runtime_jars_backed_up': len(runtime)}, ensure_ascii=False))
