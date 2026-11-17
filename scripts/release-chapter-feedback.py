"""Package and deploy the chapter feedback feature to the existing local services."""
import argparse
import hashlib
import json
import re
import shutil
import subprocess
import urllib.request
import zipfile
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEPLOY = Path(r'C:\Users\lylboy\Documents\Codex\2026-10-03\task\novel-plus-local')
PREVIEW = ROOT / 'doc/frontend-realtest-20261006-231709'
FRONT = 'novel-plus-local-front-realtest-1'
ADMIN = 'novel-plus-local-admin-1'
parser = argparse.ArgumentParser()
parser.add_argument('phase', choices=('package', 'deploy', 'verify', 'rollback'))
parser.add_argument('--evidence-directory', type=Path, required=True)
args = parser.parse_args()
OUT = (ROOT / args.evidence_directory).resolve()
assert OUT.is_relative_to(ROOT / 'doc') and re.fullmatch(r'chapter-feedback-\d{8}-\d{6}', OUT.name) and OUT.is_dir()
BASELINE = json.loads((OUT / 'runtime-before.json').read_text(encoding='utf-8'))
IMAGES = {FRONT: 'novel-plus-front:' + OUT.name, ADMIN: 'novel-plus-admin:' + OUT.name}
ADMIN_OVERRIDE = OUT / 'admin-feedback.override.yaml'
COMPOSE = ['docker', 'compose', '--project-directory', str(DEPLOY), '-f', str(DEPLOY / 'compose.yaml'),
           '-f', str(PREVIEW / 'front-realtest.override.yaml'), '-f', str(ADMIN_OVERRIDE)]

def run(command):
    return subprocess.run(command, capture_output=True, check=True).stdout.decode('utf-8', 'replace')

def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()

def save(name, data):
    (OUT / name).write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding='utf-8')

def items():
    names = run(['docker', 'ps', '-a', '--format', '{{.Names}}']).splitlines()
    return {v['Name'][1:]: v for v in json.loads(run(['docker', 'inspect', *names]))}

def update_front(image):
    override = PREVIEW / 'front-realtest.override.yaml'
    value, count = re.subn(r'(?m)^    image: novel-plus-front:[^\r\n]+$', '    image: ' + image,
                          override.read_text(encoding='utf-8-sig'))
    assert count == 1
    override.write_text(value, encoding='utf-8')

if args.phase == 'package':
    packages = OUT / 'packages'
    packages.mkdir(exist_ok=True)
    front_jar = ROOT / 'novel-front/target/novel-front-5.3.3.jar'
    shutil.copy2(front_jar, packages / front_jar.name)
    front_zip = ROOT / 'novel-front/target/build/novel-front.zip'
    with zipfile.ZipFile(front_zip) as archive:
        assert hashlib.sha256(archive.read('novel-front.jar')).hexdigest() == sha(front_jar)
        assert 'novel-front.zip' not in archive.namelist()
    shutil.copy2(front_zip, packages / front_zip.name)
    resources = ROOT / 'novel-front/src/main/resources'
    verified = []
    with zipfile.ZipFile(front_jar) as jar, zipfile.ZipFile(front_zip) as archive:
        for group in ('templates', 'static'):
            for p in (resources / group).rglob('*'):
                if not p.is_file():
                    continue
                rel = p.relative_to(resources).as_posix()
                external = 'html/' + rel[10:] if rel.startswith('templates/') else rel
                assert sha(ROOT / 'templates/green' / external) == sha(p), rel
                assert hashlib.sha256(jar.read('BOOT-INF/classes/' + rel)).hexdigest() == sha(p), rel
                assert hashlib.sha256(archive.read('templates/green/' + external)).hexdigest() == sha(p), rel
                verified.append(rel)
        for rel in ('controller/ChapterFeedbackController.class', 'service/ChapterFeedbackService.class', 'dto/ChapterFeedbackRequest.class'):
            assert 'BOOT-INF/classes/com/java2nb/novel/' + rel in jar.namelist()
        assert not any('FixtureApplication' in name for name in jar.namelist())
    admin_paths = ('static/js/appjs/novel/userFeedback/userFeedback.js', 'templates/novel/userFeedback/userFeedback.html')
    admin_jar = packages / 'novel-admin-5.3.3.jar'
    with zipfile.ZipFile(OUT / 'backup/admin-before.jar') as old, zipfile.ZipFile(admin_jar, 'w') as new:
        for entry in old.infolist():
            rel = entry.filename.removeprefix('BOOT-INF/classes/')
            data = (ROOT / 'novel-admin/src/main/resources' / rel).read_bytes() if rel in admin_paths else old.read(entry.filename)
            new.writestr(entry, data)
    untouched = 0
    with zipfile.ZipFile(OUT / 'backup/admin-before.jar') as old, zipfile.ZipFile(admin_jar) as new:
        assert old.namelist() == new.namelist()
        for entry in old.infolist():
            if entry.filename.removeprefix('BOOT-INF/classes/') not in admin_paths:
                assert old.read(entry.filename) == new.read(entry.filename)
                assert old.getinfo(entry.filename).compress_type == new.getinfo(entry.filename).compress_type
                untouched += 1
    # Make a fresh admin ZIP from its existing distribution and the verified resource update.
    build = ROOT / 'novel-admin/target/build'
    assert (build / 'novel-admin.jar').exists()
    with zipfile.ZipFile(packages / 'novel-admin.zip', 'w', compression=zipfile.ZIP_DEFLATED) as archive:
        for p in build.rglob('*'):
            if p.is_file() and p.suffix != '.zip':
                rel = p.relative_to(build).as_posix()
                archive.write(admin_jar if rel == 'novel-admin.jar' else p, rel)
    for key, module, jar_path in ((FRONT, 'front', front_jar), (ADMIN, 'admin', admin_jar)):
        context = OUT / ('candidate-' + module)
        context.mkdir(exist_ok=True)
        shutil.copy2(jar_path, context / 'app.jar')
        commands = 'FROM ' + BASELINE[key]['image'] + '\nCOPY app.jar /app/app.jar\n'
        if module == 'front':
            shutil.copytree(ROOT / 'templates/green', context / 'templates/green', dirs_exist_ok=True)
            commands += 'COPY templates/green /app/templates/green\n'
        (context / 'Dockerfile').write_text(commands, encoding='utf-8')
    source_before = json.loads((OUT / 'source-before.json').read_text(encoding='utf-8'))
    before_admin_html = OUT / 'before/novel-admin/src/main/resources/templates/novel/userFeedback/userFeedback.html'
    source_before[before_admin_html.relative_to(OUT / 'before').as_posix()] = sha(before_admin_html)
    save('source-before.json', source_before)
    current = {rel: sha(ROOT / rel) for rel in source_before if (ROOT / rel).is_file()}
    for p in (ROOT / 'novel-front/src').rglob('*'):
        if p.is_file():
            current[p.relative_to(ROOT).as_posix()] = sha(p)
    save('source-final.json', current)
    unchanged = ('engine.js', 'global.worker.js', 'sherpa.worker.js', 'resource-cache.js')
    for name in unchanged:
        assert sha(resources / 'static/javascript/reader' / name) == sha(OUT / 'before/novel-front/src/main/resources/static/javascript/reader' / name)
    save('release-manifest.json', dict(time=datetime.now(timezone.utc).isoformat(), images=IMAGES,
         frontResourcesVerified=len(verified), adminResourcesUpdated=list(admin_paths),
         adminEntriesPreserved=untouched, readerEngineFilesPreserved=list(unchanged),
         packages={p.name: sha(p) for p in packages.iterdir()},
         changes=[rel for rel in current if source_before.get(rel) != current[rel]]))
    print(json.dumps(dict(frontResources=len(verified), adminResources=2, adminEntriesPreserved=untouched)))

elif args.phase == 'deploy':
    current = items()
    for name in (FRONT, ADMIN):
        assert current[name]['Id'] == BASELINE[name]['id'], 'Service changed since backup: ' + name
    config_before = json.loads((OUT / 'config-before.json').read_text(encoding='utf-8'))
    assert all(sha(Path(p)) == digest for p, digest in config_before.items()), 'Deployment configuration changed'
    for name, module in ((FRONT, 'front'), (ADMIN, 'admin')):
        result = subprocess.run(['docker', 'build', '-t', IMAGES[name], str(OUT / ('candidate-' + module))],
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        (OUT / ('docker-' + module + '-build.log')).write_bytes(result.stdout)
        assert result.returncode == 0, module + ' image failed'
    update_front(IMAGES[FRONT])
    ADMIN_OVERRIDE.write_text('services:\n  admin:\n    image: ' + IMAGES[ADMIN] + '\n', encoding='utf-8')
    result = subprocess.run(COMPOSE + ['up', '-d', '--no-deps', '--wait', '--wait-timeout', '180', 'front-realtest', 'admin'],
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    (OUT / 'compose-update.log').write_bytes(result.stdout)
    if result.returncode != 0:
        update_front(BASELINE[FRONT]['image'])
        ADMIN_OVERRIDE.write_text('services:\n  admin:\n    image: ' + BASELINE[ADMIN]['image'] + '\n', encoding='utf-8')
        rollback = subprocess.run(COMPOSE + ['up', '-d', '--no-deps', '--wait', '--wait-timeout', '180', 'front-realtest', 'admin'],
                                  stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        (OUT / 'automatic-rollback.log').write_bytes(rollback.stdout)
        raise RuntimeError('Startup failed; previous images restored' if rollback.returncode == 0 else 'Startup and rollback failed; inspect logs')
    runtime = json.loads((PREVIEW / 'test-runtime.json').read_text(encoding='utf-8-sig'))
    runtime.update(image=IMAGES[FRONT], candidateContext=str(OUT / 'candidate-front'), chapterFeedbackEvidence=str(OUT), adminFeedbackOverride=str(ADMIN_OVERRIDE))
    (PREVIEW / 'test-runtime.json').write_text(json.dumps(runtime, ensure_ascii=False, indent=2), encoding='utf-8')
    save('deployment.json', dict(images=IMAGES, frontendUrl='http://127.0.0.1:18987', adminUrl='http://127.0.0.1:18088'))
    print(json.dumps(dict(front=IMAGES[FRONT], admin=IMAGES[ADMIN])))

elif args.phase == 'verify':
    current = items()
    verified = {}
    for name, module in ((FRONT, 'front'), (ADMIN, 'admin')):
        assert current[name]['Config']['Image'] == IMAGES[name]
        assert current[name]['State']['Health']['Status'] == 'healthy'
        assert run(['docker', 'exec', name, 'sha256sum', '/app/app.jar']).split()[0] == sha(OUT / ('candidate-' + module) / 'app.jar')
        mounts = [(m['Type'], m.get('Name', m['Source']), m['Destination']) for m in current[name]['Mounts']]
        assert [list(m) for m in mounts] == BASELINE[name]['mounts']
        verified[name] = dict(image=IMAGES[name], jarSha256=sha(OUT / ('candidate-' + module) / 'app.jar'), health='healthy')
    preserved = []
    for name, previous in BASELINE.items():
        if name not in (FRONT, ADMIN):
            assert current[name]['Id'] == previous['id'] and current[name]['State']['StartedAt'] == previous['started'], name
            preserved.append(name)
    config = json.loads((OUT / 'config-before.json').read_text(encoding='utf-8'))
    assert all(sha(Path(p)) == digest for p, digest in config.items() if Path(p).parent == DEPLOY)
    public = {'/javascript/reader/feedback.js?v=20261007.6': ROOT / 'novel-front/src/main/resources/static/javascript/reader/feedback.js',
              '/css/reader.css?v=20261007.6': ROOT / 'novel-front/src/main/resources/static/css/reader.css',
              '/javascript/reader/controller.js?v=20261007.6': ROOT / 'novel-front/src/main/resources/static/javascript/reader/controller.js'}
    for url, path in public.items():
        with urllib.request.urlopen('http://127.0.0.1:18987' + url, timeout=20) as response:
            assert hashlib.sha256(response.read()).hexdigest() == sha(path)
    save('runtime-verification.json', dict(services=verified, otherContainersPreserved=preserved,
         originalConfigPreserved=True, originalMountsPreserved=True, publicResourcesVerified=len(public)))
    print(json.dumps(dict(services=2, otherContainersPreserved=len(preserved), resources=len(public))))

elif args.phase == 'rollback':
    current = items()
    assert all(current[name]['Config']['Image'] == IMAGES[name] for name in (FRONT, ADMIN)), 'Current release differs'
    update_front(BASELINE[FRONT]['image'])
    ADMIN_OVERRIDE.write_text('services:\n  admin:\n    image: ' + BASELINE[ADMIN]['image'] + '\n', encoding='utf-8')
    result = subprocess.run(COMPOSE + ['up', '-d', '--no-deps', '--wait', '--wait-timeout', '180', 'front-realtest', 'admin'],
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    (OUT / 'rollback.log').write_bytes(result.stdout)
    assert result.returncode == 0
    shutil.copy2(OUT / 'backup/4-test-runtime.json', PREVIEW / 'test-runtime.json')
    save('rollback.json', dict(time=datetime.now(timezone.utc).isoformat(), images={n: BASELINE[n]['image'] for n in (FRONT, ADMIN)}, databaseChanged=False))
    print('Previous frontend and admin images restored; feedback records preserved.')
