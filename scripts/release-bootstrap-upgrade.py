"""Scoped, reversible admin/crawl release without changing original business data."""
import argparse
import hashlib
import io
import json
import os
import re
import shutil
import subprocess
import zipfile
from datetime import datetime, timezone
from pathlib import Path

ROOT=Path(__file__).resolve().parent.parent
RELEASE=os.environ.get('NOVEL_BOOTSTRAP_RELEASE') or (ROOT/'doc/bootstrap-upgrade-current.txt').read_text(encoding='utf-8').strip()
assert re.fullmatch(r'bootstrap-upgrade-\d{8}-\d{6}',RELEASE)
OUT=ROOT/'doc'/RELEASE
SUFFIX=OUT.name.removeprefix('bootstrap-upgrade-')
BASELINE={row['name']:row for row in json.loads((OUT/'runtime-before.private.json').read_text(encoding='utf-8'))}
MODULES={'admin':('novel-plus-local-admin-1',19288),'crawl':('novel-plus-local-crawl-1',19281)}
IMAGES={module:'novel-plus-'+module+':bootstrap-5.3.8-'+SUFFIX for module in MODULES}
DEPLOY=Path('C:/Users/lylboy/Documents/Codex/2026-10-03/task/novel-plus-local')
CONFIGS=json.loads((OUT/'deployment-config-before.json').read_text(encoding='utf-8'))
SECURITY=ROOT/'doc/security-dependency-upgrade-20261008-003503/security.override.yaml'
COMPOSE=['docker','compose','--project-directory',str(DEPLOY),'-f',str(DEPLOY/'compose.yaml'),
         '-f',str(ROOT/'doc/frontend-realtest-20261006-231709/front-realtest.override.yaml'),
         '-f',str(ROOT/'doc/chapter-feedback-20261007-164353/admin-feedback.override.yaml'),'-f',str(SECURITY)]

def run(command,data=None):
    result=subprocess.run(command,input=data,capture_output=True,text=True,encoding='utf-8',errors='replace')
    if result.returncode: raise RuntimeError(result.stderr[-2400:] or result.stdout[-2400:])
    return result.stdout

def save(name,data): (OUT/name).write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def sha(path):
    with path.open('rb') as stream:return hashlib.file_digest(stream,'sha256').hexdigest()
def digest(data):return hashlib.sha256(data).hexdigest()

def database_snapshot():
    sql="SET SESSION TRANSACTION READ ONLY; START TRANSACTION WITH CONSISTENT SNAPSHOT;\n"
    sql+="SELECT JSON_OBJECT('kind','source','id',id,'status',source_status,'rule_sha',SHA2(crawl_rule,256)) FROM crawl_source ORDER BY id;\n"
    for table in ('book','book_index','book_content','user','author','order_pay','user_buy_record','crawl_single_task','crawl_batch_task','sys_user','sys_user_role'):
        sql+=f"SELECT JSON_OBJECT('kind','count','table','{table}','count',COUNT(*)) FROM `{table}`;\n"
    sql+='COMMIT;'
    rows=[json.loads(line) for line in run(['docker','exec','-i','novel-plus-local-mysql-1','sh','-c',
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --raw --batch --skip-column-names -uroot novel_plus'],sql).splitlines() if line]
    assert [row['id'] for row in rows if row['kind']=='source']==[1,2,4,12]
    assert all(row['status']==0 for row in rows if row['kind']=='source')
    return rows

def archive_fingerprints(archive):
    return {name:digest(archive.read(name)) for name in archive.namelist() if name.endswith('.class') or name.startswith('BOOT-INF/lib/')}

def package():
    assert json.loads((OUT/'maven-package-result.json').read_text(encoding='utf-8'))['returncode']==0
    before=json.loads((OUT/'source-before.json').read_text(encoding='utf-8'))
    front=json.loads((OUT/'front-source-before.json').read_text(encoding='utf-8'))
    assert all(sha(ROOT/rel)==value for rel,value in front.items()),'Reader source changed'
    for rel,value in before.items():
        if rel.endswith('.java') or rel.endswith('pom.xml') or '/mybatis/' in rel: assert sha(ROOT/rel)==value,rel
    changed={rel:sha(ROOT/rel) for rel,value in before.items() if sha(ROOT/rel)!=value}
    new=[]
    for module in MODULES:
        for path in (ROOT/('novel-'+module)/'src/main/resources').rglob('*'):
            if path.is_file() and path.relative_to(ROOT).as_posix() not in before:new.append(path.relative_to(ROOT).as_posix())
    save('source-changes.json',dict(changed=changed,added=new,readerUnchanged=True,javaUnchanged=True))
    packages=OUT/'packages';packages.mkdir(exist_ok=True);result={}
    for module,(name,_) in MODULES.items():
        jarname='novel-'+module+'-5.3.3.jar'
        compiled=OUT/'candidate-source'/('novel-'+module)/'target'/jarname
        resources=ROOT/('novel-'+module)/'src/main/resources'
        updates={}
        for group in ('static','templates'):
            for source in (resources/group).rglob('*'):
                if source.is_file(): updates['BOOT-INF/classes/'+source.relative_to(resources).as_posix()]=source.read_bytes()
        jar=packages/jarname
        with zipfile.ZipFile(compiled) as source,zipfile.ZipFile(jar,'w') as dest:
            for info in source.infolist(): dest.writestr(info,updates.pop(info.filename,source.read(info.filename)))
            for entry,data in updates.items():dest.writestr(entry,data,compress_type=zipfile.ZIP_DEFLATED)
        baseline=OUT/'runtime-backup'/name/'app.jar'
        with zipfile.ZipFile(baseline) as old,zipfile.ZipFile(jar) as current:
            oldfp,newfp=archive_fingerprints(old),archive_fingerprints(current)
            assert oldfp.keys()==newfp.keys(),'Unexpected Java/library entries'
            verified=0
            for entry,value in oldfp.items():
                if entry.startswith('BOOT-INF/lib/novel-common-'):
                    with zipfile.ZipFile(io.BytesIO(old.read(entry))) as commonold,zipfile.ZipFile(io.BytesIO(current.read(entry))) as commonnew:
                        assert {x:digest(commonold.read(x)) for x in commonold.namelist() if not x.endswith('/')} == {x:digest(commonnew.read(x)) for x in commonnew.namelist() if not x.endswith('/')}, 'Common code/resources changed'
                else: assert value==newfp[entry],entry
                verified+=1
            assets=0
            for group in ('templates','static','mybatis'):
                for source in (resources/group).rglob('*'):
                    if not source.is_file() or source.name in {'.DS_Store','Thumbs.db','.gitignore','.gitattributes','.gitmodules'}:continue
                    entry='BOOT-INF/classes/'+source.relative_to(resources).as_posix()
                    assert digest(current.read(entry))==sha(source),entry;assets+=1
            assert not any('FixtureApplication' in name or '/test-classes/' in name for name in current.namelist())
        release=OUT/'candidate-source'/('novel-'+module)/'target/build'/('novel-'+module+'.zip')
        with zipfile.ZipFile(release) as source,zipfile.ZipFile(packages/('novel-'+module+'.zip'),'w') as dest:
            for info in source.infolist():dest.writestr(info,jar.read_bytes() if info.filename=='novel-'+module+'.jar' else source.read(info.filename))
        context=OUT/('image-'+module);context.mkdir(exist_ok=True);shutil.copy2(jar,context/'app.jar')
        (context/'Dockerfile').write_text('FROM '+BASELINE[name]['image']+'\nCOPY app.jar /app/app.jar\n',encoding='utf-8')
        output=run(['docker','build','-t',IMAGES[module],str(context)])
        (OUT/('docker-build-'+module+'.log')).write_text(output,encoding='utf-8')
        result[module]=dict(jar=jarname,sha256=sha(jar),image=IMAGES[module],resourcesVerified=assets,javaAndLibraryEntriesVerified=verified)
    save('package-verification.json',result);print(json.dumps(result))

def candidate():
    verified=json.loads((OUT/'package-verification.json').read_text(encoding='utf-8'));created=[]
    for module,(original,port) in MODULES.items():
        before=BASELINE[original];name='novel-bootstrap-candidate-'+module+'-'+SUFFIX
        existing=run(['docker','ps','-a','--format','{{.Names}}']).splitlines();assert name not in existing
        env=dict(item.split('=',1) for item in before['env'])
        env.update(SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE='4',SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE='1')
        if module=='crawl':env['CRAWL_JOBS_ENABLED']='false'
        else:env.update(ADMIN_SECURITY_SECURE_COOKIE='false',SPRING_THYMELEAF_MODE='HTML')
        envfile=OUT/(module+'-candidate-env.private');envfile.write_text('\n'.join(key+'='+value for key,value in env.items())+'\n',encoding='utf-8')
        command=['docker','run','-d','--name',name,'--network','novel-plus-local_local','--env-file',str(envfile),'-p','127.0.0.1:'+str(port)+':8080']
        for mount in before['mounts']:
            source=mount.get('Name') if mount['Type']=='volume' else mount['Source']
            command+=['--mount','type='+mount['Type']+',source='+source+',target='+mount['Destination']+',readonly']
        command += [IMAGES[module],'java','-jar','/app/app.jar'];cid=run(command).strip()
        run(['docker','network','connect','novel-plus-local_access',name])
        assert run(['docker','exec',name,'sha256sum','/app/app.jar']).split()[0]==verified[module]['sha256']
        created.append(dict(module=module,name=name,id=cid,image=IMAGES[module],url='http://127.0.0.1:'+str(port)))
        save('candidate-runtime.json',created)
    print('Two loopback candidates started with collection disabled.')

def deploy():
    for filename in ('candidate-acceptance.json','browser-built-acceptance.json'):
        acceptance=json.loads((OUT/filename).read_text(encoding='utf-8'));assert acceptance['passed'],filename
    packages=json.loads((OUT/'package-verification.json').read_text(encoding='utf-8'))
    assert json.loads((OUT/'candidate-acceptance.json').read_text(encoding='utf-8'))['packageSha256']=={module:row['sha256'] for module,row in packages.items()}
    ui=json.loads((OUT/'ui-built-runtime.json').read_text(encoding='utf-8'))
    assert json.loads((OUT/'browser-built-acceptance.json').read_text(encoding='utf-8'))['adminJarSha256']==packages['admin']['sha256']
    assert run(['docker','exec',ui['name'],'sha256sum','/app/app.jar']).split()[0]==packages['admin']['sha256']
    assert database_snapshot()==json.loads((OUT/'database-before.json').read_text(encoding='utf-8')),'Original business data changed'
    for config in CONFIGS:assert sha(Path(config['path']))==config['sha256'],'Deployment config changed; preserve newer work'
    save('database-predeploy.json',database_snapshot())
    lines=['services:']
    rollback=['services:']
    for module,(name,_) in MODULES.items():
        lines+=['  '+module+':','    image: '+IMAGES[module]]
        rollback+=['  '+module+':','    image: '+BASELINE[name]['image']]
    (OUT/'bootstrap.override.yaml').write_text('\n'.join(lines)+'\n',encoding='utf-8')
    (OUT/'rollback.override.yaml').write_text('\n'.join(rollback)+'\n',encoding='utf-8')
    run(COMPOSE+['-f',str(OUT/'bootstrap.override.yaml'),'up','-d','--no-deps','--wait','--wait-timeout','240','admin','crawl'])
    # Persist only the two image references in the existing launch chain.
    for config in CONFIGS:
        target=Path(config['path'])
        if target.suffix.lower() not in {'.yaml','.yml'}:continue
        text=target.read_text(encoding='utf-8')
        for module,(name,_) in MODULES.items():text=text.replace(BASELINE[name]['image'],IMAGES[module])
        if text!=target.read_text(encoding='utf-8'):target.write_text(text,encoding='utf-8',newline='\n')
    command=COMPOSE+['-f',str(OUT/'bootstrap.override.yaml'),'up','-d','--no-deps','--wait','--wait-timeout','240','admin','crawl']
    (OUT/'update-local.ps1').write_text(' '.join('"'+part+'"' if ' ' in part else part for part in command)+'\n',encoding='utf-8')
    (OUT/'rollback-local.ps1').write_text('& "'+str(ROOT/'scripts/rollback-bootstrap-upgrade.ps1')+'" -Release "'+RELEASE+'"\n',encoding='utf-8')
    save('deployment.json',dict(time=datetime.now(timezone.utc).isoformat(),images=IMAGES,services=['admin','crawl'],databaseMigration=False))
    print('Admin and crawl updated locally; readers and databases were not restarted.')

def verify():
    original=json.loads((OUT/'all-runtime-before.private.json').read_text(encoding='utf-8'))
    names=run(['docker','ps','-a','--format','{{.Names}}']).splitlines()
    current={row['Name'][1:]:row for row in json.loads(run(['docker','inspect',*names]))}
    verified=json.loads((OUT/'package-verification.json').read_text(encoding='utf-8'));details={}
    for module,(name,_) in MODULES.items():
        row=current[name];before=BASELINE[name]
        assert row['Config']['Image']==IMAGES[module];assert row['State'].get('Health',{}).get('Status')=='healthy'
        assert run(['docker','exec',name,'sha256sum','/app/app.jar']).split()[0]==verified[module]['sha256']
        def mounts(rows):return sorted((m['Type'],m.get('Name',m['Source']),m['Destination'],m['RW']) for m in rows)
        assert mounts(row['Mounts'])==mounts(before['mounts'])
        assert set(row['Config']['Env'])==set(before['env']),name+' environment changed'
        details[name]=dict(image=IMAGES[module],health='healthy',jarVerified=True,envAndMountsPreserved=True)
    untouched=0
    for row in original:
        name=row['Name'][1:]
        if name in {data[0] for data in MODULES.values()}:continue
        assert name in current and current[name]['Id']==row['Id'] and current[name]['State']['StartedAt']==row['State']['StartedAt'],name+' restarted'
        untouched+=1
    for name,before in BASELINE.items():
        if name.endswith(('front-1','front-realtest-1')):
            assert run(['docker','exec',name,'sha256sum','/app/app.jar']).split()[0]==before['jar_sha256']
    after=database_snapshot();assert after==json.loads((OUT/'database-predeploy.json').read_text(encoding='utf-8'))
    save('database-after.json',after);save('runtime-verification.json',dict(services=details,untouchedContainers=untouched,readerJarsUnchanged=True,originalDataUnchanged=True))
    print(json.dumps(dict(healthyServices=2,untouchedContainers=untouched,originalDataUnchanged=True)))

def rollback():
    run(COMPOSE+['-f',str(OUT/'rollback.override.yaml'),'up','-d','--no-deps','--wait','--wait-timeout','240','admin','crawl'])
    # Replace our image references while retaining any unrelated newer edits.
    for config in CONFIGS:
        target=Path(config['path'])
        if target.suffix.lower() not in {'.yaml','.yml'}:continue
        text=target.read_text(encoding='utf-8')
        for module,(name,_) in MODULES.items():text=text.replace(IMAGES[module],BASELINE[name]['image'])
        target.write_text(text,encoding='utf-8',newline='\n')
    print('Two application images rolled back to the security release. No data rollback or volume deletion.')

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('phase',choices=('package','candidate','deploy','verify','rollback'))
    globals()[parser.parse_args().phase]()
