"""Re-run deployment checks in a fresh evidence directory without replacing services."""
import argparse
import concurrent.futures
import datetime
import hashlib
import json
import os
import shutil
import subprocess
import time
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()

def save(out, name, data):
    (out / name).write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

def run(command, **kwargs):
    result = subprocess.run(command, capture_output=True, **kwargs)
    if result.returncode:
        raise RuntimeError(result.stderr.decode('utf-8', errors='replace')[-1600:])
    return result.stdout

def files():
    for item in ['pom.xml', '.gitignore', 'novel-common', 'novel-front', 'novel-crawl', 'novel-admin', 'templates', 'config', 'scripts', 'tests', 'doc/sql']:
        base = ROOT / item
        if not base.exists():
            continue
        for path in ([base] if base.is_file() else base.rglob('*')):
            if path.is_file() and not any(p in {'target', '__pycache__', 'node_modules', '.git'} for p in path.relative_to(ROOT).parts):
                yield path

def prepare():
    now = datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8)))
    out = ROOT / 'doc' / ('preproduction-retest-' + now.strftime('%Y%m%d-%H%M%S'))
    out.mkdir()
    (ROOT / 'doc/preproduction-retest-current.txt').write_text(out.name + '\n', encoding='utf-8')
    paths = list(files())
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        values = list(pool.map(sha, paths))
    manifest = {path.relative_to(ROOT).as_posix(): digest for path, digest in zip(paths, values)}
    save(out, 'source-before.json', manifest)
    for path in paths:
        dest = out / 'candidate-source' / path.relative_to(ROOT)
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, dest)
    settings = '<settings><mirrors><mirror><id>central-host-tls</id><mirrorOf>*</mirrorOf><url>https://repo.maven.apache.org/maven2/</url></mirror></mirrors></settings>'
    (out / 'maven-settings.xml').write_text(settings, encoding='utf-8')
    (out / '.gitignore').write_text('*.private*\ncandidate-source/\n', encoding='utf-8')
    (out / 'task-ledger.txt').write_text('In progress: current-source snapshot, full Maven/Node regressions, isolated authenticated UI and real PCM, original runtime/data/config verification. No production deployment, no crawler activation, no original business writes, no record or volume deletion.\n', encoding='utf-8')
    save(out, 'scope.json', dict(started=now.isoformat(), sourceFiles=len(manifest), productionDeployment=False, originalBusinessWrites=False, storageWipeOrFormat=False))
    print(json.dumps(dict(out=str(out), sourceFiles=len(manifest))))

def build(out):
    suffix = out.name.removeprefix('preproduction-retest-')
    name = 'novel-preproduction-build-' + suffix
    command = ['docker', 'run', '--name', name,
        '--mount', 'type=bind,source='+str(out/'candidate-source')+',target=/work',
        '--mount', 'type=bind,source='+str(out/'maven-settings.xml')+',target=/settings.xml,readonly',
        '-v', 'novel-preproduction-maven-cache-'+suffix+':/root/.m2',
        '-v', 'novel-plus-security-maven-cache-20261008-003503:/seed-m2:ro']
    for module in ('common', 'admin', 'crawl', 'front'):
        command += ['--mount', 'type=volume,source=novel-preproduction-'+module+'-targets-'+suffix+',target=/work/novel-'+module+'/target']
    command += ['-w', '/work', 'maven:3.9.9-eclipse-temurin-21', 'sh', '-c',
        'cp -an /seed-m2/. /root/.m2/ && mvn -o -B -ntp -s /settings.xml -Dmaven.test.skip=false -DskipTests=false package']
    with (out/'maven-package.log').open('wb') as log:
        result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT)
    for module in ('common', 'admin', 'crawl', 'front'):
        dest = out/'candidate-source'/('novel-'+module)/'target'
        dest.mkdir(parents=True, exist_ok=True)
        run(['docker', 'cp', name+':/work/novel-'+module+'/target/.', str(dest)])
    save(out, 'build-result.json', dict(returncode=result.returncode, container=name))
    print(json.dumps(dict(returncode=result.returncode, log=str(out/'maven-package.log'))))
    raise SystemExit(result.returncode)

def node(out):
    testfiles = sorted(str(p) for p in (out/'candidate-source'/'tests').rglob('*') if p.name.endswith(('.test.cjs', '.test.mjs')))
    if (out/'node-tests.log').exists():
        shutil.copy2(out/'node-tests.log', out/'node-tests-initial.log')
    with (out/'node-tests.log').open('wb') as log:
        result = subprocess.run(['node', '--experimental-vm-modules', '--test', *testfiles], cwd=out/'candidate-source', stdout=log, stderr=subprocess.STDOUT)
    save(out, 'node-result.json', dict(returncode=result.returncode, files=[Path(p).name for p in testfiles]))
    print(json.dumps(dict(returncode=result.returncode, files=len(testfiles))))
    raise SystemExit(result.returncode)

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('phase', choices=['prepare', 'build', 'node'])
    args = parser.parse_args()
    if args.phase == 'prepare':
        prepare()
    else:
        out = ROOT/'doc'/(ROOT/'doc/preproduction-retest-current.txt').read_text(encoding='utf-8').strip()
        assert out.is_dir() and out.name.startswith('preproduction-retest-')
        {'build': build, 'node': node}[args.phase](out)
