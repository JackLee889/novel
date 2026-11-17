"""Build in a separate checkout and cache without changing source files or data volumes."""
from pathlib import Path
import argparse
import shutil
import subprocess
import json
from datetime import datetime

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'doc/security-dependency-upgrade-20261008-003503'
STAGE = OUT / 'candidate-source'
parser = argparse.ArgumentParser()
parser.add_argument('phase', choices=['sync', 'compile', 'test', 'package'])
parser.add_argument('--modules', default='')
args = parser.parse_args()
if args.phase == 'sync':
    for item in ['pom.xml', 'novel-common', 'novel-front', 'novel-crawl', 'novel-admin', 'templates', 'config', 'doc/sql']:
        base = ROOT / item
        files = [base] if base.is_file() else base.rglob('*')
        for src in files:
            if src.is_file() and not any(p in {'target', 'node_modules', '__pycache__', '.git'} for p in src.relative_to(ROOT).parts):
                dest = STAGE / src.relative_to(ROOT)
                dest.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(src, dest)
    (OUT / 'maven-settings.xml').write_text('<settings><mirrors><mirror><id>central-host-tls</id><mirrorOf>*</mirrorOf><url>http://host.docker.internal:19295/maven2/</url></mirror></mirrors></settings>', encoding='utf-8')
    print('Independent build sources synchronized')
else:
    volume = 'novel-plus-security-maven-cache-20261008-003503'
    name = 'novel-security-build-' + datetime.now().strftime('%Y%m%d-%H%M%S') + '-' + args.phase
    command = ['docker', 'run', '--name', name, '--mount', 'type=bind,source='+str(STAGE)+',target=/work',
               '--mount', 'type=bind,source='+str(OUT / 'maven-settings.xml')+',target=/settings.xml,readonly',
               '-v', volume + ':/root/.m2', '-v', 'novel-plus-local-maven-cache:/seed-m2:ro', '-w', '/work',
               'maven:3.9.9-eclipse-temurin-21', 'sh', '-c']
    linux_targets = ('novel-admin',) if args.modules == 'novel-admin' else ('novel-common','novel-front','novel-crawl','novel-admin')
    position = command.index('-w')
    for module in linux_targets:
        command[position:position] = ['--mount', 'type=volume,source=novel-plus-security-'+module.removeprefix('novel-')+'-targets-20261008-003503,target=/work/'+module+'/target']
    mvn = 'mvn -U -B -ntp -s /settings.xml -Dmaven.test.skip=false -Daether.dependencyCollector.impl=bf -Daether.connector.basic.threads=16 -Dmaven.artifact.threads=16 '
    if args.modules:mvn += '-pl ' + args.modules + ' -am '
    mvn += args.phase
    command += ['cp -an /seed-m2/. /root/.m2/ && find /root/.m2 -name _remote.repositories -exec sed -i "s/>[^=]*=/>central-host-tls=/g" {} + && ' + mvn]
    with (OUT / ('maven-' + args.phase + '.log')).open('wb') as log:
        result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT)
    for module in linux_targets:
        destination = STAGE / module / 'target'
        destination.mkdir(parents=True, exist_ok=True)
        subprocess.run(['docker', 'cp', name + ':/work/'+module+'/target/.', str(destination)], check=True, capture_output=True)
    (OUT / ('maven-' + args.phase + '-result.json')).write_text(json.dumps({'returncode': result.returncode, 'container': name}), encoding='utf-8')
    print(json.dumps({'phase': args.phase, 'returncode': result.returncode, 'log': str(OUT / ('maven-' + args.phase + '.log'))}))
    raise SystemExit(result.returncode)
