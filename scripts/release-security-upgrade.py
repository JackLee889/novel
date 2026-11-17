"""Reversible local security release; retain databases, volumes, credentials and stopped sources."""
import argparse
import hashlib
import json
import re
import shutil
import subprocess
import zipfile
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'doc/security-dependency-upgrade-20261008-003503'
STAGE = OUT / 'candidate-source'
DEPLOY = Path('C:/Users/lylboy/Documents/Codex/2026-10-03/task/novel-plus-local')
PREVIEW = ROOT / 'doc/frontend-realtest-20261006-231709'
ADMIN_OVERRIDE = ROOT / 'doc/chapter-feedback-20261007-164353/admin-feedback.override.yaml'
OVERRIDE = OUT / 'security.override.yaml'
ROLLBACK = OUT / 'rollback.override.yaml'
BASELINE = {row['name']: row for row in json.loads((OUT / 'runtime-before.private.json').read_text(encoding='utf-8'))}
SERVICES = {
    'front-realtest': ('novel-plus-local-front-realtest-1', 'front', 19287),
    'front': ('novel-plus-local-front-1', 'front', 19280),
    'admin': ('novel-plus-local-admin-1', 'admin', 19288),
    'crawl': ('novel-plus-local-crawl-1', 'crawl', 19281),
}
IMAGES = {module: 'novel-plus-' + module + ':security-upgrade-20261008-003503' for module in ('front', 'admin', 'crawl')}
COMPOSE = ['docker', 'compose', '--project-directory', str(DEPLOY), '-f', str(DEPLOY / 'compose.yaml'),
           '-f', str(PREVIEW / 'front-realtest.override.yaml'), '-f', str(ADMIN_OVERRIDE)]

def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()

def save(name, data):
    (OUT / name).write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

def run(command, input=None):
    result = subprocess.run(command, input=input, capture_output=True, encoding='utf-8', errors='replace')
    if result.returncode:
        # Commands may carry private configuration; do not echo command arguments.
        raise RuntimeError(result.stderr[-3500:] or result.stdout[-3500:])
    return result.stdout

def inspect(names):
    return {row['Name'][1:]: row for row in json.loads(run(['docker', 'inspect', *names]))}

def all_containers():
    names = run(['docker', 'ps', '-a', '--format', '{{.Names}}']).splitlines()
    return inspect(names)

def database_snapshot():
    sql = "SET SESSION TRANSACTION READ ONLY; START TRANSACTION WITH CONSISTENT SNAPSHOT;\n"
    sql += "SELECT JSON_OBJECT('kind','sources','id',id,'name',source_name,'status',source_status,'rule_sha',SHA2(crawl_rule,256),'created',create_time,'updated',update_time) FROM crawl_source ORDER BY id;\n"
    for table in ('book','book_index','book_content','user','author','order_pay','user_buy_record','crawl_single_task','crawl_batch_task'):
        sql += f"SELECT JSON_OBJECT('kind','count','table','{table}','count',COUNT(*)) FROM `{table}`;\n"
    sql += "SELECT JSON_OBJECT('kind','freeChapter','bookId',bi.book_id,'indexId',bi.id) FROM book_index bi JOIN book_content bc ON bc.index_id=bi.id WHERE bi.is_vip=0 ORDER BY bi.id LIMIT 1;\nCOMMIT;"
    output = run(['docker', 'exec', '-i', 'novel-plus-local-mysql-1', 'sh', '-c',
                  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --default-character-set=utf8mb4 --raw --batch --skip-column-names -uroot novel_plus'], sql)
    rows = [json.loads(line) for line in output.splitlines() if line]
    assert [row['id'] for row in rows if row['kind']=='sources'] == [1,2,4,12]
    assert all(row['status']==0 for row in rows if row['kind']=='sources')
    return rows

def package(modules=('common','front','admin','crawl')):
    if len(modules)==4:
        assert json.loads((OUT / 'maven-package-result.json').read_text())['returncode']==0
    packages = OUT / 'packages'; packages.mkdir(exist_ok=True)
    manifest=OUT/'package-verification.json'
    result = json.loads(manifest.read_text()) if manifest.is_file() else {}
    for module in modules:
        name = 'novel-' + module
        jar = STAGE / name / ('target/' + name + '-5.3.3.jar')
        assert jar.is_file()
        shutil.copy2(jar, packages / jar.name)
        entries = []
        if module != 'common':
            release = STAGE / name / ('target/build/' + name + '.zip')
            with zipfile.ZipFile(release) as archive:
                with archive.open(name + '.jar') as stream:
                    assert hashlib.file_digest(stream,'sha256').hexdigest()==sha(jar)
                assert name + '.zip' not in archive.namelist()
            shutil.copy2(release, packages / release.name)
            resources = ROOT / name / 'src/main/resources'
            with zipfile.ZipFile(jar) as archive:
                assert not any('FixtureApplication' in entry or '/test-classes/' in entry for entry in archive.namelist())
                if module in ('front','crawl'):
                    common=STAGE/'novel-common/target/novel-common-5.3.3.jar'
                    assert hashlib.sha256(archive.read('BOOT-INF/lib/novel-common-5.3.3.jar')).hexdigest()==sha(common)
                for group in ('templates','static','mybatis'):
                    for path in (resources / group).rglob('*'):
                        if not path.is_file() or path.name in ('.DS_Store','Thumbs.db','.gitignore','.gitattributes','.gitmodules'): continue
                        rel = path.relative_to(resources).as_posix()
                        assert hashlib.sha256(archive.read('BOOT-INF/classes/' + rel)).hexdigest()==sha(path), rel
                        entries.append(rel)
            context = OUT / ('image-' + module); context.mkdir(exist_ok=True)
            shutil.copy2(jar, context / 'app.jar')
            old = BASELINE[SERVICES['front-realtest' if module=='front' else module][0]]['image']
            commands = 'FROM ' + old + '\nCOPY app.jar /app/app.jar\n'
            if module=='front':
                # Package resources and external Thymeleaf override must agree byte-for-byte.
                shutil.copytree(STAGE / 'templates/green', context / 'templates/green', dirs_exist_ok=True)
                for path in (resources / 'templates').rglob('*.html'):
                    rel = path.relative_to(resources / 'templates')
                    assert sha(path)==sha(context / 'templates/green/html' / rel)
                commands += 'COPY templates/green /app/templates/green\n'
            (context / 'Dockerfile').write_text(commands, encoding='utf-8')
            built = subprocess.run(['docker','build','-t',IMAGES[module],str(context)], capture_output=True)
            (OUT / ('docker-build-' + module + '.log')).write_bytes(built.stdout+built.stderr)
            assert built.returncode==0, module + ' image build failed'
        result[module] = dict(jar=jar.name, sha256=sha(jar), resourcesVerified=len(entries))
    save('package-verification.json', result)
    print(json.dumps(result))

def candidates(modules=('front','admin','crawl')):
    assert (OUT / 'package-verification.json').is_file()
    verified=json.loads((OUT/'package-verification.json').read_text())
    assert all(module in verified for module in modules), 'Build and verify each selected image first'
    current = all_containers()
    manifest=OUT/'candidate-runtime.json'
    created = json.loads(manifest.read_text()) if manifest.is_file() else []
    # One front candidate covers both front services with the same tested JAR.
    for service in ('front-realtest','admin','crawl'):
        original, module, port = SERVICES[service]
        if module not in modules:continue
        name = 'novel-security-candidate-' + module + '-20261008'
        assert name not in current, 'Existing candidate must be inspected before another run'
        created=[row for row in created if row['name']!=name]
        before = BASELINE[original]
        env = dict(item.split('=',1) for item in before['env'])
        env.update(SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE='4',SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE='1')
        if module=='front':
            env.update(FEATURES_PAYMENT_ENABLED='false',FEATURES_AI_ENABLED='false',FEATURES_SUBMISSION_ENABLED='false')
        if module=='crawl': env['CRAWL_JOBS_ENABLED']='false'
        if module=='admin':
            env['ADMIN_SECURITY_SECURE_COOKIE']='false' # Candidate is loopback HTTP; production default remains true.
            env['SPRING_THYMELEAF_MODE']='HTML'
        envfile = OUT / (module + '-candidate-env.private')
        assert all('\n' not in value and '\r' not in value for value in env.values())
        envfile.write_text('\n'.join(key+'='+value for key,value in env.items())+'\n',encoding='utf-8')
        command = ['docker','run','-d','--name',name,'--label','novel.security.task=20261008-003503',
                   '--network','novel-plus-local_local','--env-file',str(envfile),'-p','127.0.0.1:'+str(port)+':8080']
        for mount in before['mounts']:
            source = mount['Name'] if mount['Type']=='volume' else mount['Source']
            command += ['--mount','type='+mount['Type']+',source='+source+',target='+mount['Destination']+',readonly']
        command += [IMAGES[module],'java','-jar','/app/app.jar']
        container = run(command).strip()
        # Docker Desktop publishes loopback ports only through a non-internal network.
        run(['docker','network','connect','novel-plus-local_access',name])
        created.append(dict(service=service,name=name,id=container,image=IMAGES[module],url='http://127.0.0.1:'+str(port)))
        save('candidate-runtime.json', created)
    if not (OUT/'database-before.json').is_file():
        save('database-before.json',database_snapshot())
        save('unrelated-before.json',{name:dict(id=row['Id'],started=row['State']['StartedAt']) for name,row in current.items()
                                     if name not in BASELINE and not name.startswith('novel-security-')})
    print(json.dumps(created))

def deploy():
    acceptance=json.loads((OUT / 'candidate-acceptance.json').read_text(encoding='utf-8'))
    assert acceptance['passed'] and acceptance['readerPcmVerified']
    packages=json.loads((OUT/'package-verification.json').read_text())
    assert acceptance['packageSha256']=={module:row['sha256'] for module,row in packages.items()}, 'Candidate proof belongs to a different build'
    current=all_containers()
    if (OUT/'deployment.json').is_file():
        previous=json.loads((OUT/'deployment-runtime-ids.json').read_text())
        assert all(current[name]['Id']==previous[name] and current[name]['Config']['Image']==IMAGES[module]
                   for name,module,port in SERVICES.values()), 'Service changed since the last verified release'
    else:
        assert all(current[name]['Id']==row['id'] for name,row in BASELINE.items()), 'Original service changed since backup'
    save('database-predeploy.json',database_snapshot())
    assert database_snapshot()==json.loads((OUT / 'database-before.json').read_text()), 'Business data changed during acceptance'
    new=['services:']; old=['services:']
    for service,(name,module,port) in SERVICES.items():
        new += ['  '+service+':','    image: '+IMAGES[module]]
        old += ['  '+service+':','    image: '+BASELINE[name]['image']]
        if module=='front':
            for dest in (new,old): dest += ['    environment:','      FEATURES_PAYMENT_ENABLED: "false"','      FEATURES_AI_ENABLED: "false"','      FEATURES_SUBMISSION_ENABLED: "false"']
        if module=='admin': new += ['    environment:','      ADMIN_SECURITY_SECURE_COOKIE: "false"','      SPRING_THYMELEAF_MODE: HTML']
    OVERRIDE.write_text('\n'.join(new)+'\n',encoding='utf-8')
    ROLLBACK.write_text('\n'.join(old)+'\n',encoding='utf-8')
    result=subprocess.run(COMPOSE+['-f',str(OVERRIDE),'up','-d','--no-deps','--wait','--wait-timeout','240',*SERVICES],capture_output=True)
    (OUT/'compose-update.log').write_bytes(result.stdout+result.stderr)
    if result.returncode:
        for filename,destination in [('compose.yaml',DEPLOY/'compose.yaml'),('front-realtest.override.yaml',PREVIEW/'front-realtest.override.yaml'),('admin-feedback.override.yaml',ADMIN_OVERRIDE)]:
            shutil.copy2(OUT/'deployment-before'/filename,destination)
        restore=subprocess.run(COMPOSE+['-f',str(ROLLBACK),'up','-d','--no-deps','--wait','--wait-timeout','240',*SERVICES],capture_output=True)
        (OUT/'automatic-rollback.log').write_bytes(restore.stdout+restore.stderr)
        raise RuntimeError('Startup failed. Automatic rollback result: '+str(restore.returncode))
    # Persist the verified image references in the existing launch configuration as well.
    preview=PREVIEW/'front-realtest.override.yaml'
    text,count=re.subn(r'(?m)^    image: novel-plus-front:[^\r\n]+$','    image: '+IMAGES['front'],preview.read_text(encoding='utf-8-sig'))
    assert count==1;preview.write_text(text,encoding='utf-8')
    ADMIN_OVERRIDE.write_text('services:\n  admin:\n    image: '+IMAGES['admin']+'\n    environment:\n      ADMIN_SECURITY_SECURE_COOKIE: "false"\n      SPRING_THYMELEAF_MODE: HTML\n',encoding='utf-8')
    base=DEPLOY/'compose.yaml'; text=base.read_text(encoding='utf-8-sig')
    for module in ('front','crawl'):
        text,count=re.subn(r'(?m)^(    image: novel-plus-'+module+r':)[^\r\n]+$','    image: '+IMAGES[module],text)
        assert count==1, module
    # Root default flags are also false, but make local deployment intent explicit.
    base.write_text(text,encoding='utf-8')
    (OUT/'update-local.ps1').write_text('& docker compose --project-directory "'+str(DEPLOY)+'" -f "'+str(base)+'" -f "'+str(preview)+'" -f "'+str(ADMIN_OVERRIDE)+'" -f "'+str(OVERRIDE)+'" up -d --no-deps --wait --wait-timeout 240 front front-realtest admin crawl\n',encoding='utf-8')
    (OUT/'rollback-local.ps1').write_text('python -X utf8 "'+str(ROOT/'scripts/release-security-upgrade.py')+'" rollback\n',encoding='utf-8')
    save('deployment.json',dict(time=datetime.now(timezone.utc).isoformat(),images=IMAGES,overrides=str(OVERRIDE),databaseMigration=False))
    print('Updated four local application services; original database and Redis containers retained.')

def verify():
    current=all_containers();report={}
    def mounts(row):
        result=[]
        for m in row['Mounts'] if 'Mounts' in row else row['mounts']:
            source=m.get('Name',m['Source'])
            if source.startswith('/run/desktop/mnt/host/'):
                drive,tail=source[len('/run/desktop/mnt/host/'):].split('/',1);source=drive.upper()+':/'+tail
            result.append((m['Type'],source.lower(),m['Destination'],m['RW']))
        return sorted(result)
    for service,(name,module,port) in SERVICES.items():
        row=current[name];before=BASELINE[name]
        assert row['Config']['Image']==IMAGES[module] and row['State']['Health']['Status']=='healthy',name
        digest=run(['docker','exec',name,'sha256sum','/app/app.jar']).split()[0]
        assert digest==sha(OUT/('image-'+module)/'app.jar')
        assert mounts(row)==mounts(before),name+' mounts changed'
        oldenv=dict(e.split('=',1) for e in before['env']); env=dict(e.split('=',1) for e in row['Config']['Env'])
        allowed={'FEATURES_PAYMENT_ENABLED','FEATURES_AI_ENABLED','FEATURES_SUBMISSION_ENABLED','ADMIN_SECURITY_SECURE_COOKIE','SPRING_THYMELEAF_MODE'}
        assert all(env.get(key)==value for key,value in oldenv.items() if key not in allowed),name+' configuration changed'
        if module=='front':assert all(env.get('FEATURES_'+f+'_ENABLED','false')=='false' for f in ('PAYMENT','AI','SUBMISSION'))
        report[name]=dict(image=IMAGES[module],sha256=digest,health='healthy',mountsPreserved=True,credentialsAndDomainPreserved=True)
    previous=json.loads((OUT/'unrelated-before.json').read_text())
    assert all(current[name]['Id']==row['id'] and current[name]['State']['StartedAt']==row['started'] for name,row in previous.items()),'Unrelated container changed'
    after=database_snapshot();assert after==json.loads((OUT/'database-predeploy.json').read_text())
    save('database-after.json',after)
    save('runtime-verification.json',dict(services=report,unrelatedContainersPreserved=len(previous),businessDataPreserved=True))
    save('deployment-runtime-ids.json',{name:current[name]['Id'] for name,module,port in SERVICES.values()})
    print(json.dumps(dict(services=len(report),unrelatedContainersPreserved=len(previous),businessDataPreserved=True)))

def rollback():
    current=all_containers()
    assert all(current[name]['Config']['Image']==IMAGES[module] for name,module,port in SERVICES.values()),'Different release is running'
    # Restore launch files from snapshots; roll back application images only.
    for filename,destination in [('compose.yaml',DEPLOY/'compose.yaml'),('front-realtest.override.yaml',PREVIEW/'front-realtest.override.yaml'),('admin-feedback.override.yaml',ADMIN_OVERRIDE)]:
        shutil.copy2(OUT/'deployment-before'/filename,destination)
    result=subprocess.run(COMPOSE+['-f',str(ROLLBACK),'up','-d','--no-deps','--wait','--wait-timeout','240',*SERVICES],capture_output=True)
    (OUT/'rollback.log').write_bytes(result.stdout+result.stderr);assert result.returncode==0
    save('rollback.json',dict(time=datetime.now(timezone.utc).isoformat(),businessDataPreserved=True,featuresDisabled=True))
    print('Previous application images restored with payment, AI and submission disabled.')

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('phase',choices=('package','candidate','deploy','verify','rollback'))
    parser.add_argument('--modules',default='')
    args=parser.parse_args()
    if args.modules:
        modules=tuple(args.modules.split(','))
        assert all(module in ('common','front','admin','crawl') for module in modules)
        assert args.phase in ('package','candidate')
        (package if args.phase=='package' else candidates)(modules)
    else:{'package':package,'candidate':candidates,'deploy':deploy,'verify':verify,'rollback':rollback}[args.phase]()
