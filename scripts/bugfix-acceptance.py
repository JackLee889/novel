"""Build and exercise fixes using retained test data and fresh dedicated containers."""
import argparse
import datetime
import hashlib
import json
import os
import re
import secrets
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PREVIOUS = ROOT / 'doc/preproduction-retest-20261008-100534'
POINTER = ROOT / 'doc/bugfix-acceptance-current.txt'
ORIGINAL = ['novel-plus-local-' + name + '-1' for name in
            ('mysql', 'redis', 'front', 'front-realtest', 'admin', 'crawl', 'gateway', 'gateway-realtest')]
PORTS = {'front': 19587, 'admin': 19588, 'crawl': 19581}

def run(args, data=None, timeout=600):
    result = subprocess.run(args, input=data, capture_output=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(result.stderr.decode('utf-8', errors='replace')[-1800:])
    return result.stdout

def save(out, name, value):
    path = out / name
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    if '.private' in name:
        secure(path)

def secure(path):
    account = os.environ.get('USERDOMAIN', '') + '\\' + os.environ['USERNAME']
    run(['icacls', str(path), '/inheritance:r', '/grant:r', account + ':(F)', 'SYSTEM:(F)'])

def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()

def current():
    out = ROOT / 'doc' / POINTER.read_text(encoding='utf-8').strip()
    assert out.is_dir() and out.name.startswith('bugfix-acceptance-')
    return out

def query(name, sql):
    assert name.startswith('novel-bugfix-') or name == 'novel-preproduction-mysql-20261008-100534'
    raw = run(['docker', 'exec', '-i', name, 'sh', '-c',
               'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --default-character-set=utf8mb4 --raw --batch --skip-column-names novel_plus'], sql.encode())
    return [line.split('\t') for line in raw.decode('utf-8').splitlines() if line]

def source_files():
    for item in ('pom.xml', '.gitignore', 'novel-common', 'novel-front', 'novel-admin', 'novel-crawl',
                 'templates', 'config', 'scripts', 'tests', 'doc/sql'):
        base = ROOT / item
        if not base.exists():
            continue
        for path in ([base] if base.is_file() else base.rglob('*')):
            if path.is_file() and not any(part in {'target', '__pycache__', 'node_modules', '.git'}
                                          for part in path.relative_to(ROOT).parts):
                yield path

def initialize():
    now = datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8)))
    out = ROOT / 'doc' / ('bugfix-acceptance-' + now.strftime('%Y%m%d-%H%M%S'))
    out.mkdir()
    POINTER.write_text(out.name + '\n', encoding='utf-8')
    runtime = json.loads(run(['docker', 'inspect', *ORIGINAL]))
    save(out, 'original-runtime.private.json', runtime)
    save(out, 'original-runtime.json', [dict(name=r['Name'], id=r['Id'], image=r['Image'],
         state=r['State']['Status'], started=r['State']['StartedAt'], mounts=r['Mounts']) for r in runtime])
    save(out, 'scope.json', dict(started=now.isoformat(), originalBusinessWrites=False,
         productionDeployment=False, originalServicesStarted=False, volumesDeleted=False))
    (out / '.gitignore').write_text('*.private*\ncandidate-source/\n', encoding='utf-8')
    (out / 'task-ledger.txt').write_text('In progress: fix JWT/API/header, admin permissions/cache, TTS loader/lifecycle; full isolated build and live acceptance.\n', encoding='utf-8')
    print(json.dumps({'out': str(out), 'originalContainers': len(runtime)}))

def sandbox(out):
    suffix = out.name.removeprefix('bugfix-acceptance-')
    cfg = json.loads((ROOT / 'doc/bootstrap-upgrade-20261008-075538/ui-sandbox.private.json').read_text(encoding='utf-8'))
    cfg.update({name: 'novel-bugfix-' + name + '-' + suffix for name in ('mysql', 'redis', 'front', 'admin', 'crawl')})
    cfg['databasePassword'] = secrets.token_urlsafe(28)
    save(out, 'ui-sandbox.private.json', cfg)
    envfile = out / 'mysql-env.private'
    envfile.write_text('MYSQL_ROOT_PASSWORD=' + cfg['databasePassword'] + '\nMYSQL_DATABASE=novel_plus\n', encoding='utf-8')
    secure(envfile)
    run(['docker', 'run', '-d', '--name', cfg['mysql'], '--network', 'novel-plus-local_local',
         '--env-file', str(envfile), '--mount', 'type=volume,source=novel-bugfix-mysql-data-' + suffix + ',target=/var/lib/mysql', 'mysql:8.0.46'])
    run(['docker', 'run', '-d', '--name', cfg['redis'], '--network', 'novel-plus-local_local',
         '--mount', 'type=volume,source=novel-bugfix-redis-data-' + suffix + ',target=/data', 'redis:7.4-alpine'])
    for _ in range(40):
        try:
            query(cfg['mysql'], 'SELECT 1;')
            break
        except RuntimeError:
            time.sleep(2)
    else:
        raise RuntimeError('New test MySQL did not start')
    assert query(cfg['mysql'], "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='novel_plus';")[0][0] == '0'
    baseline = ROOT / 'doc/bootstrap-upgrade-20261008-075538'
    seed = (baseline / 'ui-sandbox-copy.private.sql').read_bytes()
    assert not re.search(rb'\b(?:DROP\s+(?:TABLE|DATABASE)|TRUNCATE|DELETE\s+FROM)\b', seed, re.I)
    run(['docker', 'exec', '-i', cfg['mysql'], 'sh', '-c',
         'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --default-character-set=utf8mb4 novel_plus'], seed)
    for name in ('ui-samples.json', 'ui-dicts.json'):
        shutil.copy2(baseline / name, out / name)
    samples = json.loads((out / 'ui-samples.json').read_text(encoding='utf-8'))
    # Add representative public content only from the retained acceptance database.
    old = 'novel-preproduction-mysql-20261008-100534'
    entry = json.loads(run(['docker', 'inspect', old]))[0]
    started = entry['State']['Status'] != 'running'
    if started:
        run(['docker', 'start', old])
    try:
        for _ in range(30):
            try:
                rows = query(old, 'SELECT CAST(id AS CHAR) FROM book ORDER BY id LIMIT 32;')
                break
            except RuntimeError:
                time.sleep(2)
        present = {row[0] for row in query(cfg['mysql'], 'SELECT CAST(id AS CHAR) FROM book;')}
        book_ids = [row[0] for row in rows if row[0] not in present][:14]
        assert book_ids and all(value.isdecimal() for value in book_ids)
        chapter_ids = [row[0] for row in query(old, 'SELECT CAST(id AS CHAR) FROM book_index WHERE book_id IN (' + ','.join(book_ids) + ');')]
        assert chapter_ids and all(value.isdecimal() for value in chapter_ids)
        for table, where in [('book', 'id IN (' + ','.join(book_ids) + ')'),
                             ('book_index', 'id IN (' + ','.join(chapter_ids) + ')'),
                             ('book_content', 'index_id IN (' + ','.join(chapter_ids) + ')')]:
            dump = run(['docker', 'exec', old, 'sh', '-c',
                 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --no-tablespaces --set-gtid-purged=OFF --no-create-info --skip-add-drop-table --skip-add-locks --where="$1" novel_plus "$2"', 'sh', where, table])
            assert not re.search(rb'\b(?:DROP\s+(?:TABLE|DATABASE)|TRUNCATE|DELETE\s+FROM)\b', dump, re.I)
            run(['docker', 'exec', '-i', cfg['mysql'], 'sh', '-c',
                 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --default-character-set=utf8mb4 novel_plus'], dump)
    finally:
        if started:
            run(['docker', 'stop', old])
    query(cfg['mysql'], "INSERT INTO book_index(id,book_id,index_num,index_name,word_count,is_vip,create_time,update_time) VALUES(9007199254741999," + samples['bookId'] + ",999999,'修复验收权限夹具',12,1,NOW(),NOW()); INSERT INTO book_content(id,index_id,content) VALUES(9007199254741999,9007199254741999,'PREDEPLOY_PRIVATE_BODY_SENTINEL');")
    # Retain all generated account/history records; credentials are copied privately.
    shutil.copy2(PREVIOUS / 'front-test-credentials.private.json', out / 'front-test-credentials.private.json')
    secure(out / 'front-test-credentials.private.json')
    tables = query(cfg['mysql'], 'SELECT COUNT(*) FROM book; SELECT COUNT(*) FROM book_index;')
    save(out, 'sandbox-evidence.json', dict(newVolumes=True, seedHasOriginalPermissions=True,
         dataSource='Retained isolated preproduction test database', counts=tables, originalDatabaseAccessed=False))
    print(json.dumps({'isolated': True, 'mysql': cfg['mysql'], 'books': tables[0][0], 'chapters': tables[1][0]}))

def freeze(out):
    stage = out / 'candidate-source'
    assert not stage.exists(), 'Freeze into a fresh stage; do not overwrite an already tested candidate'
    manifest = {}
    for path in source_files():
        dest = stage / path.relative_to(ROOT)
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, dest)
        manifest[path.relative_to(ROOT).as_posix()] = sha(path)
    save(out, 'source-frozen.json', manifest)
    (out / 'maven-settings.xml').write_text('<settings><mirrors><mirror><id>central-host-tls</id><mirrorOf>*</mirrorOf><url>https://repo.maven.apache.org/maven2/</url></mirror></mirrors></settings>', encoding='utf-8')
    print(json.dumps({'frozenFiles': len(manifest)}))

def build(out):
    suffix = out.name.removeprefix('bugfix-acceptance-')
    name = 'novel-bugfix-build-' + suffix
    args = ['docker', 'run', '--name', name, '--mount', 'type=bind,source=' + str(out / 'candidate-source') + ',target=/work',
            '--mount', 'type=bind,source=' + str(out / 'maven-settings.xml') + ',target=/settings.xml,readonly',
            '-v', 'novel-bugfix-maven-cache-' + suffix + ':/root/.m2',
            '-v', 'novel-plus-security-maven-cache-20261008-003503:/seed-m2:ro']
    for module in ('common', 'front', 'admin', 'crawl'):
        args += ['--mount', 'type=volume,source=novel-bugfix-' + module + '-targets-' + suffix + ',target=/work/novel-' + module + '/target']
    args += ['-w', '/work', 'maven:3.9.9-eclipse-temurin-21', 'sh', '-c',
             'cp -an /seed-m2/. /root/.m2/ && mvn -o -B -ntp -s /settings.xml -Dmaven.test.skip=false -DskipTests=false package']
    with (out / 'maven-package.log').open('wb') as log:
        result = subprocess.run(args, stdout=log, stderr=subprocess.STDOUT)
    for module in ('common', 'front', 'admin', 'crawl'):
        dest = out / 'candidate-source' / ('novel-' + module) / 'target'
        dest.mkdir(parents=True, exist_ok=True)
        run(['docker', 'cp', name + ':/work/novel-' + module + '/target/.', str(dest)])
    save(out, 'build-result.json', dict(returncode=result.returncode, container=name))
    print(json.dumps({'buildReturnCode': result.returncode}))
    raise SystemExit(result.returncode)

def node(out):
    stage = out / 'candidate-source'
    tests = sorted(str(path) for path in (stage / 'tests').rglob('*') if path.name.endswith(('.test.cjs', '.test.mjs')))
    with (out / 'node-tests.log').open('wb') as log:
        result = subprocess.run(['node', '--experimental-vm-modules', '--test', *tests], cwd=stage, stdout=log, stderr=subprocess.STDOUT)
    save(out, 'node-result.json', dict(returncode=result.returncode, files=[Path(path).name for path in tests]))
    print(json.dumps({'nodeReturnCode': result.returncode, 'files': len(tests)}))
    raise SystemExit(result.returncode)

def rebuild_admin(out):
    suffix = out.name.removeprefix('bugfix-acceptance-')
    stage = out / 'candidate-source'
    revision = out / 'admin-revision-2'
    revision.mkdir()
    admin_target = stage / 'novel-admin/target'
    shutil.copytree(admin_target / 'surefire-reports', revision / 'first-build-surefire-reports')
    before = sha(admin_target / 'novel-admin-5.3.3.jar')
    changed = {}
    for rel in ('novel-admin/src/main/java/com/java2nb/system/service/impl/RoleServiceImpl.java',
                'novel-admin/src/test/java/com/java2nb/system/service/impl/PermissionMutationTest.java'):
        old = stage / rel
        backup = revision / 'before' / rel
        backup.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(old, backup)
        changed[rel] = dict(before=sha(old), after=sha(ROOT / rel))
        shutil.copy2(ROOT / rel, old)
    name = 'novel-bugfix-build-admin-r2-' + suffix
    args = ['docker', 'run', '--name', name,
            '--mount', 'type=bind,source=' + str(stage) + ',target=/work',
            '--mount', 'type=bind,source=' + str(out / 'maven-settings.xml') + ',target=/settings.xml,readonly',
            '-v', 'novel-bugfix-maven-cache-' + suffix + ':/root/.m2']
    for module in ('common', 'front', 'admin', 'crawl'):
        args += ['--mount', 'type=volume,source=novel-bugfix-' + module + '-targets-' + suffix + ',target=/work/novel-' + module + '/target']
    args += ['-w', '/work', 'maven:3.9.9-eclipse-temurin-21', 'mvn', '-o', '-B', '-ntp',
             '-s', '/settings.xml', '-Dmaven.test.skip=false', '-DskipTests=false', '-pl', 'novel-admin', '-am', 'package']
    with (revision / 'maven-package.log').open('wb') as log:
        result = subprocess.run(args, stdout=log, stderr=subprocess.STDOUT)
    assert result.returncode == 0, 'Admin revision build failed; preserve first-build evidence'
    for module in ('common', 'admin'):
        run(['docker', 'cp', name + ':/work/novel-' + module + '/target/.', str(stage / ('novel-' + module) / 'target')])
    final = {path.relative_to(stage).as_posix(): sha(path) for path in stage.rglob('*')
             if path.is_file() and not any(part in {'target', '__pycache__', 'node_modules'} for part in path.relative_to(stage).parts)}
    save(out, 'source-frozen-final.json', final)
    save(revision, 'result.json', dict(returncode=result.returncode, container=name, changes=changed,
         firstAdminJarSha256=before, finalAdminJarSha256=sha(admin_target / 'novel-admin-5.3.3.jar'),
         actualRoleDeletionExecuted=False))
    print(json.dumps({'adminRevisionReturnCode': result.returncode, 'changedFiles': len(changed)}))


def rebuild_front(out):
    suffix = out.name.removeprefix('bugfix-acceptance-')
    stage = out / 'candidate-source'
    prior = [int(path.name.removeprefix('front-revision-')) for path in out.glob('front-revision-*') if path.is_dir()]
    number = max(prior, default=1) + 1
    revision = out / ('front-revision-' + str(number))
    revision.mkdir()
    (out / 'front-current-revision.txt').write_text(str(number) + '\n', encoding='utf-8')
    target = stage / 'novel-front/target'
    shutil.copytree(target / 'surefire-reports', revision / 'first-build-surefire-reports')
    shutil.copy2(out / 'package-verification.json', revision / 'first-package-verification.json')
    shutil.copy2(out / 'image-front-build.log', revision / 'first-image-build.log')
    before = sha(target / 'novel-front-5.3.3.jar')
    changed = {}
    cache_only = True
    for base in (ROOT / 'novel-front/src', ROOT / 'tests'):
        for source in base.rglob('*'):
            if not source.is_file() or '__pycache__' in source.parts:
                continue
            rel = source.relative_to(ROOT)
            old = stage / rel
            if old.exists() and sha(old) == sha(source):
                continue
            old_sha = sha(old) if old.exists() else None
            normalize = lambda text: re.sub(r'v=20261008\.\d+', 'v=20261008.CACHE', text)
            if not old.exists() or source.suffix not in {'.js', '.html'} or normalize(old.read_text(encoding='utf-8')) != normalize(source.read_text(encoding='utf-8')):
                cache_only = False
            if old.exists():
                backup = revision / 'before' / rel
                backup.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(old, backup)
            changed[rel.as_posix()] = dict(before=old_sha, after=sha(source))
            old.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, old)
    name = 'novel-bugfix-build-front-r' + str(number) + '-' + suffix
    args = ['docker', 'run', '--name', name,
            '--mount', 'type=bind,source=' + str(stage) + ',target=/work',
            '--mount', 'type=bind,source=' + str(out / 'maven-settings.xml') + ',target=/settings.xml,readonly',
            '-v', 'novel-bugfix-maven-cache-' + suffix + ':/root/.m2']
    for module in ('common', 'front', 'admin', 'crawl'):
        args += ['--mount', 'type=volume,source=novel-bugfix-' + module + '-targets-' + suffix + ',target=/work/novel-' + module + '/target']
    args += ['-w', '/work', 'maven:3.9.9-eclipse-temurin-21', 'mvn', '-o', '-B', '-ntp',
             '-s', '/settings.xml', '-Dmaven.test.skip=false', '-DskipTests=' + str(cache_only).lower(), '-pl', 'novel-front', '-am', 'package']
    with (revision / 'maven-package.log').open('wb') as log:
        result = subprocess.run(args, stdout=log, stderr=subprocess.STDOUT)
    assert result.returncode == 0, 'Front revision build failed; preserve first-build evidence'
    for module in ('common', 'front'):
        run(['docker', 'cp', name + ':/work/novel-' + module + '/target/.', str(stage / ('novel-' + module) / 'target')])
    final = {path.relative_to(stage).as_posix(): sha(path) for path in stage.rglob('*')
             if path.is_file() and not any(part in {'target', '__pycache__', 'node_modules'} for part in path.relative_to(stage).parts)}
    save(out, 'source-frozen-final.json', final)
    save(revision, 'result.json', dict(returncode=result.returncode, container=name, changes=changed,
         firstFrontJarSha256=before, finalFrontJarSha256=sha(target / 'novel-front-5.3.3.jar'),
         cacheUrlOnly=cache_only, javaTestsExecuted=not cache_only))
    print(json.dumps({'frontRevisionReturnCode': result.returncode, 'revision': number, 'changedFiles': len(changed)}))


def images(out):
    revision = int((out / 'front-current-revision.txt').read_text().strip()) if (out / 'front-current-revision.txt').exists() else (2 if (out / 'front-revision-2').exists() else 1)
    if revision > 1:
        assert (out / ('front-revision-' + str(revision)) / 'result.json').is_file(), 'Wait for revision build and target copy to complete'
    suffix = out.name.removeprefix('bugfix-acceptance-')
    baseline = {row['Name'][1:]: row for row in json.loads((out / 'original-runtime.private.json').read_text(encoding='utf-8'))}
    result = {}
    for module in ('front', 'admin', 'crawl'):
        jar = out / 'candidate-source' / ('novel-' + module) / 'target' / ('novel-' + module + '-5.3.3.jar')
        assert jar.is_file()
        with zipfile.ZipFile(jar) as archive:
            assert not any('FixtureApplication' in name or '/test-classes/' in name for name in archive.namelist())
            count = 0
            for source in (ROOT / ('novel-' + module) / 'src/main/resources').rglob('*'):
                if not source.is_file():
                    continue
                if source.name in {'.DS_Store', 'Thumbs.db', '.gitignore', '.gitattributes', '.gitmodules'}:
                    continue  # Maven's standard resource excludes omit OS metadata.
                rel = source.relative_to(ROOT / ('novel-' + module) / 'src/main/resources')
                if rel.parts[0] not in {'static', 'templates', 'mybatis'}:
                    continue
                entry = 'BOOT-INF/classes/' + rel.as_posix()
                assert hashlib.sha256(archive.read(entry)).hexdigest() == sha(source), entry
                count += 1
        context = out / ('image-' + module)
        context.mkdir(exist_ok=True)
        shutil.copy2(jar, context / 'app.jar')
        original = 'novel-plus-local-' + ('front-realtest' if module == 'front' else module) + '-1'
        dockerfile = 'FROM ' + baseline[original]['Config']['Image'] + '\nCOPY app.jar /app/app.jar\n'
        if module == 'front':
            shutil.copytree(out / 'candidate-source/templates', context / 'templates', dirs_exist_ok=True)
            dockerfile += 'COPY templates /app/templates\n'
        (context / 'Dockerfile').write_text(dockerfile, encoding='utf-8')
        revision_tag = 'bugfix-r' + str(revision) + '-' if module == 'front' and revision > 1 else 'bugfix-'
        image = 'novel-plus-' + module + ':' + revision_tag + suffix
        with (out / ('image-' + module + '-build.log')).open('wb') as log:
            built = subprocess.run(['docker', 'build', '-t', image, str(context)], stdout=log, stderr=subprocess.STDOUT)
        assert built.returncode == 0, module
        result[module] = dict(image=image, sha256=sha(jar), resourcesVerified=count, jar=str(jar))
    save(out, 'package-verification.json', result)
    print(json.dumps({'imagesBuilt': len(result)}))

def start(out):
    suffix = out.name.removeprefix('bugfix-acceptance-')
    cfg = json.loads((out / 'ui-sandbox.private.json').read_text(encoding='utf-8'))
    baseline = {row['Name'][1:]: row for row in json.loads((out / 'original-runtime.private.json').read_text(encoding='utf-8'))}
    packages = json.loads((out / 'package-verification.json').read_text(encoding='utf-8'))
    launched = []
    for module, port in PORTS.items():
        previous = baseline['novel-plus-local-' + ('front-realtest' if module == 'front' else module) + '-1']
        env = dict(item.split('=', 1) for item in previous['Config']['Env'])
        env.update(SPRING_DATASOURCE_URL='jdbc:mysql://' + cfg['mysql'] + ':3306/novel_plus?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai',
                   SPRING_DATASOURCE_USERNAME='root', SPRING_DATASOURCE_PASSWORD=cfg['databasePassword'],
                   SPRING_DATA_REDIS_HOST=cfg['redis'], SPRING_DATA_REDIS_PORT='6379', SPRING_DATA_REDIS_PASSWORD='',
                   SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE='4', SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE='1')
        if module == 'admin':
            env.update(ADMIN_SECURITY_SECURE_COOKIE='false', SPRING_THYMELEAF_MODE='HTML', JAVA2NB_UPLOAD_PATH='/var/pic/')
        if module == 'crawl':
            env.update(CRAWL_JOBS_ENABLED='false', CRAWL_UPDATE_THREAD='0')
        for key in ('SPRING_THYMELEAF_PREFIX', 'SPRING_THYMELEAF_CACHE', 'SPRING_WEB_RESOURCES_STATIC_LOCATIONS'):
            env.pop(key, None)
        envfile = out / ('ui-' + module + '-env.private')
        envfile.write_text('\n'.join(key + '=' + value for key, value in env.items()) + '\n', encoding='utf-8')
        secure(envfile)
        args = ['docker', 'run', '-d', '--name', cfg[module], '--network', 'novel-plus-local_local',
                '--env-file', str(envfile), '-p', '127.0.0.1:' + str(port) + ':8080']
        for mount in previous['Mounts']:
            if module == 'admin' and mount['Destination'] == '/var/pic':
                continue
            source = mount.get('Name') if mount['Type'] == 'volume' else mount['Source']
            args += ['--mount', 'type=' + mount['Type'] + ',source=' + source + ',target=' + mount['Destination'] + ',readonly']
        if module == 'admin':
            args += ['--mount', 'type=volume,source=novel-bugfix-admin-uploads-' + suffix + ',target=/var/pic']
        args += [packages[module]['image'], 'java', '-jar', '/app/app.jar']
        identity = run(args).decode().strip()
        run(['docker', 'network', 'connect', 'novel-plus-local_access', cfg[module]])
        launched.append(dict(module=module, name=cfg[module], id=identity, port=port))
    save(out, 'sandbox-runtime.json', launched)
    print(json.dumps({'startedTestServices': launched}))

def prepare_suites(out):
    adaptations = []
    for name in ('http-checks.py', 'auth-history-browser.cjs', 'front-browser.cjs', 'components-browser.cjs',
                 'admin-browser.cjs', 'component-server.py', 'tts-stress.cjs', 'parallel-cold-browser.cjs', 'reader-pcm-hook.js'):
        text = (PREVIOUS / name).read_text(encoding='utf-8')
        for old, new in [('preproduction-retest-current.txt', 'bugfix-acceptance-current.txt'),
                         ('^preproduction-retest-', '^bugfix-acceptance-'),
                         ('19487', '19587'), ('19488', '19588'), ('19481', '19581'), ('19490', '19590')]:
            text = text.replace(old, new)
        (out / name).write_text(text, encoding='utf-8')
        adaptations.append(dict(source=name, sha256=sha(PREVIOUS / name), changes='Evidence directory and isolated ports only'))
    runtime = json.loads((out / 'original-runtime.private.json').read_text(encoding='utf-8'))
    save(out, 'runtime-business.private.json', [dict(name=row['Name'][1:], env=row['Config']['Env']) for row in runtime])
    save(out, 'browser-suite-adaptations.json', adaptations)
    print('Prepared isolated real HTTP/browser/PCM acceptance suites')

def restart_front(out):
    cfg = json.loads((out / 'ui-sandbox.private.json').read_text(encoding='utf-8'))
    assert cfg['front'].startswith('novel-bugfix-front-')
    packages = json.loads((out / 'package-verification.json').read_text(encoding='utf-8'))
    old = json.loads(run(['docker', 'inspect', cfg['front']]))[0]
    run(['docker', 'stop', cfg['front']])
    old_revision = re.search(r':bugfix-r(\d+)-', old['Config']['Image'])
    old_number = old_revision.group(1) if old_revision else '1'
    run(['docker', 'rename', cfg['front'], cfg['front'].replace('front-', 'front-r' + old_number + '-', 1)])
    args = ['docker', 'run', '-d', '--name', cfg['front'], '--network', 'novel-plus-local_local',
            '--env-file', str(out / 'ui-front-env.private'), '-p', '127.0.0.1:19587:8080']
    for mount in old['Mounts']:
        source = mount.get('Name') if mount['Type'] == 'volume' else mount['Source']
        args += ['--mount', 'type=' + mount['Type'] + ',source=' + source + ',target=' + mount['Destination'] + ',readonly']
    args += [packages['front']['image'], 'java', '-jar', '/app/app.jar']
    identity = run(args).decode().strip()
    run(['docker', 'network', 'connect', 'novel-plus-local_access', cfg['front']])
    revision = (out / 'front-current-revision.txt').read_text().strip() if (out / 'front-current-revision.txt').exists() else '2'
    save(out, 'front-revision-' + revision + '/runtime.json', dict(firstContainerId=old['Id'], finalContainerId=identity,
         firstContainerRetained=True, originalServicesTouched=False, finalImage=packages['front']['image']))
    print(json.dumps({'restartedOnlyCandidateFront': True, 'id': identity}))

def stop(out):
    cfg = json.loads((out / 'ui-sandbox.private.json').read_text(encoding='utf-8'))
    counts = query(cfg['mysql'], 'SELECT COUNT(*) FROM user; SELECT COUNT(*) FROM user_read_history; SELECT COUNT(*) FROM user_feedback;')
    names = [cfg[name] for name in ('front', 'admin', 'crawl', 'mysql', 'redis')]
    assert all(name.startswith('novel-bugfix-') for name in names)
    run(['docker', 'stop', *names])
    old = json.loads((out / 'original-runtime.private.json').read_text(encoding='utf-8'))
    new = json.loads(run(['docker', 'inspect', *ORIGINAL]))
    unchanged = all(a['Id'] == b['Id'] and a['Image'] == b['Image'] and a['Mounts'] == b['Mounts']
                    and a['Config']['Env'] == b['Config']['Env'] and a['State'] == b['State'] for a, b in zip(old, new))
    save(out, 'cleanup.json', dict(ownedStopped=names, originalRuntimeUnchanged=unchanged,
         retainedTestCounts=counts, recordsDeleted=False, volumesDeleted=False, productionDeployment=False))
    print(json.dumps({'originalRuntimeUnchanged': unchanged, 'retainedCounts': counts}))

def sync_generated(out):
    source = out / 'candidate-source/templates/green'
    target = ROOT / 'templates/green'
    backup = out / 'generated-green-before'
    assert not backup.exists(), 'Keep the original generated-template backup intact'
    changed = {}
    for file in source.rglob('*'):
        if not file.is_file():
            continue
        rel = file.relative_to(source)
        dest = target / rel
        if dest.exists() and sha(dest) == sha(file):
            continue
        before = sha(dest) if dest.exists() else None
        if dest.exists():
            old = backup / rel
            old.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(dest, old)
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(file, dest)
        changed[(Path('templates/green') / rel).as_posix()] = dict(before=before, after=sha(dest))
    save(out, 'generated-green-sync.json', dict(changed=changed, source='Validated candidate-source/templates/green',
         originalsBackedUp=True, deletedFiles=0))
    print(json.dumps({'generatedTemplateFilesUpdated': len(changed), 'deletedFiles': 0}))

def sync_inputs(out):
    stage = out / 'candidate-source'
    previous = out / 'validation-input-sync.json'
    changes = json.loads(previous.read_text(encoding='utf-8')).get('changes', {}) if previous.exists() else {}
    for name in ('novel-common', 'novel-front', 'novel-admin', 'novel-crawl'):
        for path in (ROOT / name / 'src').rglob('*'):
            if path.is_file():
                frozen = stage / path.relative_to(ROOT)
                assert frozen.is_file() and sha(frozen) == sha(path), 'Business source changed after candidate build: ' + str(path)
    for base in (ROOT / 'scripts', ROOT / 'tests'):
        for path in base.rglob('*'):
            if not path.is_file() or any(part in {'__pycache__', 'node_modules'} for part in path.parts):
                continue
            rel = path.relative_to(ROOT)
            frozen = stage / rel
            if frozen.exists() and sha(frozen) == sha(path):
                continue
            before = sha(frozen) if frozen.exists() else None
            if frozen.exists():
                backup = out / 'validation-inputs-before' / rel
                backup.parent.mkdir(parents=True, exist_ok=True)
                if not backup.exists():
                    shutil.copy2(frozen, backup)
            frozen.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, frozen)
            initial = changes.get(rel.as_posix(), {}).get('before', before)
            changes[rel.as_posix()] = dict(before=initial, after=sha(path))
    manifest = {path.relative_to(stage).as_posix(): sha(path) for path in stage.rglob('*')
                if path.is_file() and not any(part in {'target', '__pycache__', 'node_modules'} for part in path.relative_to(stage).parts)}
    save(out, 'validation-input-sync.json', dict(changes=changes, businessSourcesMatchFrozenCandidate=True))
    save(out, 'source-frozen-final.json', manifest)
    print(json.dumps({'businessSourcesMatchFrozenCandidate': True, 'validationFilesSynced': len(changes)}))

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('phase', choices=['init', 'sandbox', 'freeze', 'build', 'rebuild-admin', 'rebuild-front', 'node', 'images', 'start', 'restart-front', 'suites', 'sync-generated', 'sync-inputs', 'stop'])
    options = parser.parse_args()
    if options.phase == 'init':
        initialize()
    else:
        {'sandbox': sandbox, 'freeze': freeze, 'build': build, 'rebuild-admin': rebuild_admin, 'rebuild-front': rebuild_front,
         'node': node, 'images': images, 'start': start, 'restart-front': restart_front,
         'suites': prepare_suites, 'sync-generated': sync_generated, 'sync-inputs': sync_inputs, 'stop': stop}[options.phase](current())
