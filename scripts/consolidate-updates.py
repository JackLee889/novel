"""Package the current checkout and switch only its existing local preview frontend."""
import argparse
import difflib
import hashlib
import json
import re
import shutil
import subprocess
import urllib.request
import zipfile
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEPLOY = Path(r'C:\Users\lylboy\Documents\Codex\2026-10-03\task\novel-plus-local')
PREVIEW = ROOT / 'doc/frontend-realtest-20261006-231709'
FRONT = 'novel-plus-local-front-realtest-1'
MODULES = ('novel-common', 'novel-front', 'novel-crawl', 'novel-admin')


def run(args):
    return subprocess.run(args, capture_output=True, check=True).stdout.decode('utf-8', 'replace')


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def write(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')


def containers():
    names = run(['docker', 'ps', '-a', '--format', '{{.Names}}']).splitlines()
    return {v['Name'].lstrip('/'): dict(id=v['Id'], image=v['Image'], started=v['State']['StartedAt'],
            status=v['State']['Status']) for v in json.loads(run(['docker', 'inspect', *names]))}


def config_files():
    return (DEPLOY / 'compose.yaml', DEPLOY / 'Caddyfile', DEPLOY / '.env',
            PREVIEW / 'front-realtest.override.yaml', PREVIEW / 'candidate-Caddyfile',
            PREVIEW / 'test-runtime.json')


parser = argparse.ArgumentParser()
parser.add_argument('phase', choices=['snapshot', 'refresh-front-resources', 'package-front', 'package', 'deploy', 'redeploy', 'verify', 'finalize', 'rollback'])
parser.add_argument('--evidence-directory', type=Path, required=True)
args = parser.parse_args()
OUT = (ROOT / args.evidence_directory).resolve()
assert OUT.is_relative_to(ROOT / 'doc') and OUT.is_dir()
assert re.fullmatch(r'updates-merged-\d{8}-\d{6}', OUT.name)
IMAGE = 'novel-plus-front:' + OUT.name
COMPOSE = ['docker', 'compose', '--project-directory', str(DEPLOY), '-f', str(DEPLOY / 'compose.yaml'),
           '-f', str(PREVIEW / 'front-realtest.override.yaml')]

if args.phase == 'snapshot':
    assert not (OUT / 'runtime-before.json').exists()
    write(OUT / 'runtime-before.json', containers())
    configs = {str(p): sha(p) for p in config_files()}
    write(OUT / 'config-before.json', configs)
    for i, path in enumerate(config_files()):
        shutil.copy2(path, OUT / 'backup' / (str(i) + '-' + path.name))
    before = OUT / 'before'
    before.mkdir()
    paths = [ROOT / 'pom.xml']
    for module in MODULES:
        shutil.copytree(ROOT / module / 'src', before / module / 'src')
        shutil.copy2(ROOT / module / 'pom.xml', before / module / 'pom.xml')
        paths += [ROOT / module / 'pom.xml'] + list((ROOT / module / 'src').rglob('*'))
    shutil.copy2(ROOT / 'pom.xml', before / 'pom.xml')
    shutil.copytree(ROOT / 'tests', before / 'tests')
    shutil.copytree(ROOT / 'templates/green', before / 'templates-green')
    write(OUT / 'source-before.json', {p.relative_to(ROOT).as_posix(): sha(p) for p in paths if p.is_file()})
    run(['docker', 'cp', FRONT + ':/app/app.jar', str(OUT / 'backup/previous-front.jar')])
    print(json.dumps(dict(containers=len(containers()), sourceFiles=len(paths), previousFrontJar=sha(OUT / 'backup/previous-front.jar'))))

elif args.phase == 'refresh-front-resources':
    resources = ROOT / 'novel-front/src/main/resources'
    jar = ROOT / 'novel-front/target/novel-front-5.3.3.jar'
    preserved = OUT / 'backup/frontend-before-dock-click.jar'
    assert not preserved.exists(), 'Resource refresh has already run'
    shutil.copy2(jar, preserved)
    refreshed = OUT / 'refreshed-front.jar'
    changed = []
    with zipfile.ZipFile(preserved) as before, zipfile.ZipFile(refreshed, 'w') as after:
        for info in before.infolist():
            data = before.read(info.filename)
            if info.filename.startswith('BOOT-INF/classes/') and not info.is_dir():
                source = resources / info.filename[len('BOOT-INF/classes/'):]
                if source.is_file() and source.read_bytes() != data:
                    assert info.filename.startswith(('BOOT-INF/classes/static/', 'BOOT-INF/classes/templates/'))
                    data = source.read_bytes()
                    changed.append(info.filename)
            after.writestr(info, data)
    with zipfile.ZipFile(preserved) as before, zipfile.ZipFile(refreshed) as after:
        classes = [n for n in before.namelist() if n.endswith('.class')]
        libraries = [n for n in before.namelist() if n.startswith('BOOT-INF/lib/') and n.endswith('.jar')]
        for name in classes + libraries:
            assert before.read(name) == after.read(name), name
    assert len(changed) == 6, changed
    for folder, external in (('templates', 'html'), ('static', 'static')):
        shutil.copytree(resources / folder, ROOT / 'templates/green' / external, dirs_exist_ok=True)
        shutil.copytree(resources / folder, ROOT / 'novel-front/target/classes' / folder, dirs_exist_ok=True)
    shutil.copytree(ROOT / 'templates/green', ROOT / 'novel-front/target/build/templates/green', dirs_exist_ok=True)
    shutil.copy2(refreshed, jar)
    shutil.copy2(refreshed, ROOT / 'novel-front/target/build/novel-front.jar')
    write(OUT / 'frontend-resource-refresh.json', dict(changedResources=changed, preservedClasses=len(classes),
          preservedLibraries=len(libraries), javaBytecodeUnchanged=True, nestedJarCompressionPreserved=True,
          beforeSha256=sha(preserved), afterSha256=sha(refreshed)))
    print(json.dumps(dict(changedResources=len(changed), preservedClasses=len(classes), preservedLibraries=len(libraries))))

elif args.phase in ('package-front', 'package'):
    build_log = (OUT / 'maven-package.log').read_text(encoding='utf-8-sig')
    if args.phase == 'package':
        assert 'BUILD SUCCESS' in build_log
    else:
        assert 'Tests run: 37, Failures: 0, Errors: 0, Skipped: 0' in build_log and 'Building novel-admin' in build_log
    packages = OUT / 'packages'
    packages.mkdir(exist_ok=True)
    records = []
    packaged_modules = MODULES if args.phase == 'package' else ('novel-front',)
    for module in packaged_modules:
        jar = ROOT / module / 'target' / (module + '-5.3.3.jar')
        target = packages / jar.name
        shutil.copy2(jar, target)
        records.append(dict(module=module, path=str(target), bytes=target.stat().st_size, sha256=sha(target)))
        if module != 'novel-common':
            build_directory = ROOT / module / 'target/build'
            previous_zip = build_directory / (module + '.zip')
            preserved_zip = OUT / 'backup' / (module + '-maven.zip')
            if previous_zip.is_file() and not preserved_zip.exists():
                shutil.copy2(previous_zip, preserved_zip)
            shutil.copy2(jar, build_directory / (module + '.jar'))
            # Recreate the ZIP rather than trusting Ant's incremental timestamps across Windows/Linux.
            archive = packages / (module + '.zip')
            with zipfile.ZipFile(archive, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as content:
                for file in sorted(build_directory.rglob('*')):
                    if file.is_file() and file != previous_zip:
                        content.write(file, file.relative_to(build_directory).as_posix())
            with zipfile.ZipFile(archive) as content, content.open(module + '.jar') as stream:
                assert hashlib.file_digest(stream, 'sha256').hexdigest() == sha(jar), module
            shutil.copy2(archive, previous_zip)
    if args.phase == 'package':
        common_hash = sha(packages / 'novel-common-5.3.3.jar')
        # Admin is an independent Spring Boot 2 application with its own common package.
        for module in ('novel-front', 'novel-crawl'):
            with zipfile.ZipFile(packages / (module + '-5.3.3.jar')) as jar:
                assert hashlib.sha256(jar.read('BOOT-INF/lib/novel-common-5.3.3.jar')).hexdigest() == common_hash, module
    required = {
        'novel-front': ('com/java2nb/novel/dto/LoginRequest.class', 'com/java2nb/novel/dto/HistoryPageRequest.class',
                        'com/java2nb/novel/dto/ReadHistoryRequest.class', 'com/java2nb/novel/controller/ReaderController.class',
                        'static/javascript/site/auth-state.js', 'static/javascript/reader/resource-cache.js',
                        'static/javascript/reader/global.worker.js', 'static/javascript/reader/player-dock.js'),
        'novel-crawl': ('com/java2nb/novel/core/crawl/Bqg616SourceAdapter.class',
                        'com/java2nb/novel/mapper/CrawlBookMapper.class', 'static/crawl-templates/bqg616.json',
                        'static/crawl-templates/piaotia.json')}
    for module, names in required.items():
        if module not in packaged_modules:
            continue
        with zipfile.ZipFile(packages / (module + '-5.3.3.jar')) as jar:
            for name in names:
                assert 'BOOT-INF/classes/' + name in jar.namelist(), name
    context = OUT / 'candidate'
    context.mkdir(exist_ok=True)
    shutil.copy2(packages / 'novel-front-5.3.3.jar', context / 'app.jar')
    shutil.copytree(ROOT / 'templates/green', context / 'templates/green', dirs_exist_ok=True)
    (context / 'Dockerfile').write_text('FROM novel-plus-front:frontend-realtest-20261006-231709-reader-dock-v1\n'
                                      'COPY app.jar /app/app.jar\nCOPY templates/green /app/templates/green\n', encoding='utf-8')
    resources = ROOT / 'novel-front/src/main/resources'
    selected = sorted(p for folder in ('templates', 'static') for p in (resources / folder).rglob('*') if p.is_file())
    with zipfile.ZipFile(packages / 'novel-front-5.3.3.jar') as jar, zipfile.ZipFile(packages / 'novel-front.zip') as archive:
        assert not any('FixtureApplication' in n or '/frontend-fixture/' in n or n.endswith(('.onnx', '.wasm', '.data')) for n in jar.namelist())
        for path in selected:
            relative = path.relative_to(resources).as_posix()
            external = 'html/' + relative[10:] if relative.startswith('templates/') else relative
            expected = sha(path)
            assert sha(ROOT / 'templates/green' / external) == expected, relative
            assert sha(context / 'templates/green' / external) == expected, relative
            assert hashlib.sha256(jar.read('BOOT-INF/classes/' + relative)).hexdigest() == expected, relative
            assert hashlib.sha256(archive.read('templates/green/' + external)).hexdigest() == expected, relative
    source = [ROOT / 'pom.xml']
    for module in MODULES:
        source += [ROOT / module / 'pom.xml'] + [p for p in (ROOT / module / 'src').rglob('*') if p.is_file()]
    write(OUT / 'source-final.json', {p.relative_to(ROOT).as_posix(): sha(p) for p in source})
    # Reader core resources retain their deployed identities, preserving warm models in existing tabs.
    old = PREVIEW / 'candidate-context-reader-dock-v1/templates/green/static/javascript/reader'
    unchanged = ('engine.js', 'global.worker.js', 'sherpa.worker.js', 'resource-cache.js')
    for name in unchanged:
        assert sha(resources / 'static/javascript/reader' / name) == sha(old / name), name
    write(OUT / 'release-manifest.json', dict(time=datetime.now(timezone.utc).isoformat(), image=IMAGE, complete=args.phase == 'package',
          modules=records, frontendResourcesVerified=len(selected), freshArchivesVerified=True, sharedReaderModulesPreserved=list(unchanged),
          source='current workspace; no Git repository', activeUrl='http://127.0.0.1:18987',
          includedUpdates=['crawler chapter summary and charset', 'Piaotia and Bqg616 sources', 'Sherpa reader',
          'site redesign and homepage fallback', 'model resource cache', 'shared global playback',
          'player orb and pin preference', 'five authentication and history fixes', 'chapter clicks during player collapse']))
    print(json.dumps(dict(modules=len(records), frontendResources=len(selected), readerCorePreserved=len(unchanged))))

elif args.phase in ('deploy', 'redeploy'):
    baseline = json.loads((OUT / 'runtime-before.json').read_text(encoding='utf-8'))
    if args.phase == 'deploy':
        assert containers()[FRONT]['id'] == baseline[FRONT]['id'], 'Preview changed since snapshot'
    else:
        owned = json.loads(run(['docker', 'inspect', FRONT]))[0]
        assert owned['Config']['Image'] == IMAGE
        assert run(['docker', 'exec', FRONT, 'sha256sum', '/app/app.jar']).split()[0] == sha(OUT / 'backup/frontend-before-dock-click.jar')
        run(['docker', 'tag', owned['Image'], IMAGE + '-before-dock-click'])
    build = subprocess.run(['docker', 'build', '--tag', IMAGE, str(OUT / 'candidate')], stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    (OUT / 'docker-build.log').write_bytes(build.stdout)
    assert build.returncode == 0
    override = PREVIEW / 'front-realtest.override.yaml'
    text = override.read_text(encoding='utf-8-sig')
    text, count = re.subn(r'(?m)^    image: novel-plus-front:[^\r\n]+$', '    image: ' + IMAGE, text)
    assert count == 1
    override.write_text(text, encoding='utf-8')
    change = subprocess.run(COMPOSE + ['up', '-d', '--no-deps', '--wait', '--wait-timeout', '180', 'front-realtest'],
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    (OUT / 'compose-update.log').write_bytes(change.stdout)
    assert change.returncode == 0, 'Preview startup failed; use the rollback phase'
    runtime = json.loads((PREVIEW / 'test-runtime.json').read_text(encoding='utf-8-sig'))
    runtime.update(candidateContext=str(OUT / 'candidate'), image=IMAGE, mergedEvidence=str(OUT))
    write(PREVIEW / 'test-runtime.json', runtime)
    write(OUT / 'test-runtime.json', runtime)
    shutil.copy2(PREVIEW / 'actual-data-proof.json', OUT / 'actual-data-proof.json')
    print(json.dumps(dict(image=IMAGE, container=FRONT, url='http://127.0.0.1:18987')))

elif args.phase == 'verify':
    before = json.loads((OUT / 'runtime-before.json').read_text(encoding='utf-8'))
    after = containers()
    changes = {name: dict(before=state, after=after.get(name)) for name, state in before.items() if state != after.get(name)}
    assert set(changes) == {FRONT}, list(changes)
    configs = json.loads((OUT / 'config-before.json').read_text(encoding='utf-8'))
    changed_config = [p for p, h in configs.items() if sha(Path(p)) != h]
    assert set(changed_config) == {str(PREVIEW / 'front-realtest.override.yaml'), str(PREVIEW / 'test-runtime.json')}
    current = json.loads(run(['docker', 'inspect', FRONT]))[0]
    assert current['Config']['Image'] == IMAGE and current['State']['Health']['Status'] == 'healthy'
    expected = sha(OUT / 'candidate/app.jar')
    assert run(['docker', 'exec', FRONT, 'sha256sum', '/app/app.jar']).split()[0] == expected
    resources = ROOT / 'novel-front/src/main/resources/static'
    public = ('javascript/site/site.js', 'javascript/site/api.js', 'javascript/site/auth-state.js',
              'javascript/site/account.js', 'javascript/reader/global.worker.js', 'javascript/reader/resource-cache.js',
              'javascript/reader/global-player.js', 'javascript/reader/player-dock.js', 'css/site.css', 'css/player-dock.css')
    for relative in public:
        with urllib.request.urlopen('http://127.0.0.1:18987/' + relative, timeout=15) as response:
            assert hashlib.sha256(response.read()).hexdigest() == sha(resources / relative), relative
    for port, route in ((18987, '/'), (18080, '/'), (18081, '/login.html'), (18088, '/login')):
        with urllib.request.urlopen(f'http://127.0.0.1:{port}' + route, timeout=15) as response:
            assert response.status == 200
    protected_volumes = {mount['Name']: mount['Destination'] for container in json.loads(run(['docker', 'inspect', 'novel-plus-local-mysql-1',
                         'novel-plus-local-redis-1'])) for mount in container['Mounts'] if mount['Type'] == 'volume'}
    assert protected_volumes['novel-plus-local_mysql-data'] == '/var/lib/mysql'
    assert protected_volumes['novel-plus-local_redis-data'] == '/data'
    write(OUT / 'runtime-verification.json', dict(time=datetime.now(timezone.utc).isoformat(), image=IMAGE,
          frontendJarSha256=expected, servedResourcesVerified=len(public), changedContainers=list(changes),
          unchangedContainers=len(before)-1, originalConfigsUnchanged=True, originalVolumes=protected_volumes,
          healthy=True, verifiedPorts=[18987, 18080, 18081, 18088]))
    print(json.dumps(dict(healthy=True, servedResources=len(public), unchangedContainers=len(before)-1, changedContainers=list(changes))))

elif args.phase == 'finalize':
    assert 'BUILD SUCCESS' in (OUT / 'maven-package.log').read_text(encoding='utf-8-sig')
    assert json.loads((OUT / 'release-manifest.json').read_text(encoding='utf-8'))['complete']
    java = dict(tests=0, failures=0, errors=0, skipped=0)
    suites = []
    for module in MODULES:
        for path in sorted((ROOT / module / 'target/surefire-reports').glob('TEST-*.xml')):
            suite = ET.parse(path).getroot()
            for key in java:
                java[key] += int(suite.get(key, '0'))
            suites.append(dict(module=module, name=suite.get('name'), tests=int(suite.get('tests', '0'))))
    assert java == dict(tests=57, failures=0, errors=0, skipped=0), java
    nodes = (OUT / 'node-tests.log').read_text(encoding='utf-8-sig')
    assert re.search(r'(?m)^# pass 23$', nodes) and re.search(r'(?m)^# fail 0$', nodes)
    real = json.loads((OUT / 'live-test-results.json').read_text(encoding='utf-8'))
    auth = json.loads((OUT / 'live-results.json').read_text(encoding='utf-8'))
    global_player = json.loads((OUT / 'global-results.json').read_text(encoding='utf-8'))
    dock = json.loads((OUT / 'dock-results.json').read_text(encoding='utf-8'))
    navigation = json.loads((OUT / 'dock-navigation-results.json').read_text(encoding='utf-8'))
    cold = json.loads((OUT / 'cold-model-results.json').read_text(encoding='utf-8'))
    assert real['success'] and not real['pageErrors'] and not real['httpErrors']
    assert not auth.get('failed') and not auth['pageErrors'] and len(auth['checks']) == 18
    assert global_player['passed'] and dock['passed'] and not global_player['errors'] and not dock['errors']
    assert navigation['passed'] and not navigation['errors'] and len(navigation['checks']) == 3
    assert cold['passed'] and not cold['pageErrors'] and not cold['httpErrors'] and len(cold['checks']) == 3
    checks = dict(publicFrontend=len(real['checks']), authenticationAndHistory=len(auth['checks']),
                  sharedPlayback=len(global_player['checks']), playerDock=len(dock['checks']), dockNavigation=len(navigation['checks']),
                  concurrentColdModels=len(cold['checks']))
    assert checks == dict(publicFrontend=22, authenticationAndHistory=18, sharedPlayback=12, playerDock=7, dockNavigation=3, concurrentColdModels=3), checks
    database = json.loads((OUT / 'database-comparison.json').read_text(encoding='utf-8'))
    assert set(database['changes']) <= {'user_read_history'}, database['changes']
    after = json.loads((OUT / 'database-after.json').read_text(encoding='utf-8'))
    before = json.loads((OUT / 'source-before.json').read_text(encoding='utf-8'))
    final = json.loads((OUT / 'source-final.json').read_text(encoding='utf-8'))
    changed = [p for p in final if final[p] != before.get(p)]
    assert not any('/src/main/java/' in p for p in changed), 'Consolidation must preserve the already accepted production logic'
    patch = []
    review_paths = changed + [p.relative_to(ROOT).as_posix() for p in (ROOT / 'tests').rglob('*') if p.is_file()]
    for relative in review_paths:
        previous = OUT / 'before' / relative
        current = ROOT / relative
        if previous.is_file() and sha(previous) != sha(current):
            try:
                patch += list(difflib.unified_diff(previous.read_text(encoding='utf-8').splitlines(keepends=True),
                    current.read_text(encoding='utf-8').splitlines(keepends=True), fromfile='before/' + relative, tofile=relative))
            except UnicodeDecodeError:
                pass
        elif not previous.exists() and relative.startswith('tests/'):
            patch += list(difflib.unified_diff([], current.read_text(encoding='utf-8').splitlines(keepends=True),
                                             fromfile='/dev/null', tofile=relative))
    helper = ROOT / 'scripts/consolidate-updates.py'
    patch += list(difflib.unified_diff([], helper.read_text(encoding='utf-8').splitlines(keepends=True),
                                     fromfile='/dev/null', tofile='scripts/consolidate-updates.py'))
    (OUT / 'consolidation-changes.patch').write_text(''.join(patch), encoding='utf-8')
    write(OUT / 'validation-summary.json', dict(time=datetime.now(timezone.utc).isoformat(), image=IMAGE,
          java=java, javaSuites=suites, nodeTests=23, realChecks=checks, totalRealChecks=sum(checks.values()),
          layouts=len(real['layouts']), browserErrors=0, server5xx=0,
          unchangedTables=len(after['tables'])-len(database['changes']), changedTables=list(database['changes']),
          books=after['tables']['book'], chapters=after['tables']['book_index'], users=after['tables']['user'],
          changedSourceFiles=changed, productionJavaPreserved=True, rollbackAvailable=True))
    print(json.dumps(dict(javaTests=java['tests'], nodeTests=23, realChecks=sum(checks.values()),
                         layouts=len(real['layouts']), unchangedTables=len(after['tables'])-len(database['changes']))))

elif args.phase == 'rollback':
    current = json.loads(run(['docker', 'inspect', FRONT]))[0]
    assert current['Config']['Image'] == IMAGE, 'Only this release may be rolled back'
    shutil.copy2(OUT / 'backup/3-front-realtest.override.yaml', PREVIEW / 'front-realtest.override.yaml')
    run(COMPOSE + ['up', '-d', '--no-deps', '--wait', '--wait-timeout', '180', 'front-realtest'])
    shutil.copy2(OUT / 'backup/5-test-runtime.json', PREVIEW / 'test-runtime.json')
    print(json.dumps(dict(rolledBack=True, url='http://127.0.0.1:18987')))
