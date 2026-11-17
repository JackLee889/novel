"""Compile/test/package all modules in an independent Linux build directory."""
import json
import shutil
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'doc' / (ROOT / 'doc/layui-jquery-upgrade-current.txt').read_text(encoding='utf-8').strip()
STAGE = OUT / 'candidate-source'
for item in ['pom.xml', 'novel-front', 'novel-common', 'novel-admin', 'novel-crawl', 'config', 'doc/sql']:
    source = ROOT / item
    for path in ([source] if source.is_file() else source.rglob('*')):
        if not path.is_file() or any(p in {'target', 'node_modules', '__pycache__', '.git'} for p in path.relative_to(ROOT).parts): continue
        dest = STAGE / path.relative_to(ROOT)
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, dest)
settings = OUT / 'maven-settings.xml'
settings.write_text('<settings><mirrors><mirror><id>central-host-tls</id><mirrorOf>*</mirrorOf><url>https://repo.maven.apache.org/maven2/</url></mirror></mirrors></settings>', encoding='utf-8')
suffix = OUT.name.removeprefix('layui-jquery-upgrade-')
name = 'novel-layui-jquery-build-' + suffix
command = ['docker', 'run', '--name', name, '--mount', 'type=bind,source='+str(STAGE)+',target=/work',
           '--mount', 'type=bind,source='+str(settings)+',target=/settings.xml,readonly',
           '-v', 'novel-layui-jquery-maven-cache-'+suffix+':/root/.m2',
           '-v', 'novel-plus-security-maven-cache-20261008-003503:/seed-m2:ro']
for module in ('common', 'admin', 'crawl', 'front'):
    command += ['--mount', 'type=volume,source=novel-layui-jquery-'+module+'-targets-'+suffix+',target=/work/novel-'+module+'/target']
command += ['-w', '/work', 'maven:3.9.9-eclipse-temurin-21', 'sh', '-c',
            'cp -an /seed-m2/. /root/.m2/ && mvn -o -B -ntp -s /settings.xml -Dmaven.test.skip=false -pl novel-admin,novel-crawl,novel-front -am package']
with (OUT / 'maven-package.log').open('wb') as log:
    result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT)
for module in ('common', 'admin', 'crawl', 'front'):
    dest = STAGE / ('novel-'+module) / 'target'
    dest.mkdir(parents=True, exist_ok=True)
    subprocess.run(['docker', 'cp', name+':/work/novel-'+module+'/target/.', str(dest)], check=True, capture_output=True)
(OUT / 'maven-package-result.json').write_text(json.dumps(dict(returncode=result.returncode, container=name)), encoding='utf-8')
print(json.dumps(dict(returncode=result.returncode, container=name, log=str(OUT / 'maven-package.log'))))
raise SystemExit(result.returncode)
