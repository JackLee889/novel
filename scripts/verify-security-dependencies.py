"""Verify packaged dependencies against Maven Central over certificate-verified HTTPS."""
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
import json
import subprocess
import threading
import time
import zipfile
import requests

ROOT=Path(__file__).resolve().parent.parent
OUT=ROOT/'doc/security-dependency-upgrade-20261008-003503'
STAGE=OUT/'candidate-source'
result=subprocess.run(['docker','run','--rm','--name','novel-security-integrity-20261008',
    '-v','novel-plus-security-maven-cache-20261008-003503:/root/.m2:ro',
    'maven:3.9.9-eclipse-temurin-21','sh','-c',
    "find /root/.m2/repository -name '*.jar' -exec sha256sum {} +"],capture_output=True,check=True)
by_hash={}
for line in result.stdout.decode().splitlines():
    digest,path=line.split(None,1)
    relative=path.strip().removeprefix('/root/.m2/repository/')
    by_hash.setdefault(digest,[]).append(relative)
libraries={};module_versions={}
for module in ('front','admin','crawl'):
    jar=STAGE/('novel-'+module)/('target/novel-'+module+'-5.3.3.jar')
    versions=[]
    with zipfile.ZipFile(jar) as archive:
        for name in archive.namelist():
            if not name.startswith('BOOT-INF/lib/') or not name.endswith('.jar'):continue
            data=archive.read(name);digest=hashlib.sha256(data).hexdigest()
            basename=name.rsplit('/',1)[1]
            if basename=='novel-common-5.3.3.jar':continue
            candidates=[path for path in by_hash.get(digest,[]) if path.rsplit('/',1)[1]==basename]
            if not candidates and basename=='spring-boot-jarmode-tools-4.1.1.jar':
                # The Boot plugin embeds this helper instead of resolving it as an application dependency.
                candidates=['org/springframework/boot/spring-boot-jarmode-tools/4.1.1/'+basename]
            assert len(candidates)==1,(basename,candidates)
            relative=candidates[0];parts=relative.split('/')
            coordinate='.'.join(parts[:-3])+':'+parts[-3]
            version=parts[-2]
            versions.append(dict(coordinate=coordinate,version=version,file=basename,sha256=digest))
            libraries.setdefault(relative,dict(coordinate=coordinate,version=version,sha256=digest,sha1=hashlib.sha1(data).hexdigest()))
    # Prohibit vulnerable and removed legacy implementations in the actual executable artifact.
    assert not any(row['coordinate'].startswith('io.springfox:') for row in versions)
    assert all(row['version']=='3.0.1' for row in versions if row['coordinate'].startswith('org.apache.shiro:'))
    assert all(row['version']=='4.1.1' for row in versions if row['coordinate'].startswith('org.springframework.boot:'))
    assert all(row['version']=='2.0.65' for row in versions if row['coordinate']=='com.alibaba:fastjson')
    assert all(row['version']=='11.0.26' for row in versions if row['coordinate'].startswith('org.apache.tomcat.embed:'))
    assert all(row['version']=='1.6.5' for row in versions if row['coordinate'].startswith('ch.qos.logback:'))
    assert not any(row['coordinate']=='org.bouncycastle:bcprov-jdk15on' for row in versions)
    assert all(row['version']=='1.86' for row in versions if row['coordinate']=='org.bouncycastle:bcprov-jdk18on')
    assert all(row['version']=='1.15.0' for row in versions if row['coordinate']=='org.apache.commons:commons-text')
    assert all(row['version']=='2.2.55' for row in versions if row['coordinate']=='io.swagger.core.v3:swagger-annotations-jakarta')
    module_versions[module]=versions
(OUT/'packaged-library-versions.json').write_text(json.dumps(module_versions,indent=2),encoding='utf-8')
local=threading.local()
previous_file=OUT/'dependency-checksums.json'
previous={row['path']:row for row in json.loads(previous_file.read_text()).get('checks',[])} if previous_file.is_file() else {}
def verify(item):
    relative,expected=item
    if relative in previous and all(previous[relative].get(key)==expected[key] for key in ('sha1','sha256')):
        return previous[relative]
    if not hasattr(local,'session'):local.session=requests.Session()
    url='https://repo.maven.apache.org/maven2/'+relative+'.sha1'
    for attempt in range(3):
        try:
            response=local.session.get(url,timeout=(10,25));response.raise_for_status()
            actual=response.text.strip().split()[0].lower()
            assert len(actual)==40 and actual==expected['sha1'],relative+' checksum mismatch'
            return dict(path=relative,url=url,**expected,verified=True)
        except Exception:
            if attempt==2:raise
            time.sleep(attempt+1)
checks=[];errors=[]
with ThreadPoolExecutor(max_workers=8) as pool:
    futures={pool.submit(verify,item):item[0] for item in libraries.items()}
    for future in as_completed(futures):
        try:checks.append(future.result())
        except Exception as error:errors.append(dict(path=futures[future],error=str(error)))
report=dict(libraries=len(libraries),verified=len(checks),errors=errors,checks=sorted(checks,key=lambda row:row['path']))
(OUT/'dependency-checksums.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
print(json.dumps(dict(libraries=len(libraries),verified=len(checks),errors=len(errors))))
assert not errors
