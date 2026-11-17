"""Reload only this task's isolated UI container against source or built assets."""
import argparse
import json
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'doc' / (ROOT / 'doc/bootstrap-upgrade-current.txt').read_text(encoding='utf-8').strip()
parser = argparse.ArgumentParser()
parser.add_argument('mode', choices=('source', 'built'))
args = parser.parse_args()
config = json.loads((OUT/'ui-sandbox.private.json').read_text(encoding='utf-8'))
baseline = next(row for row in json.loads((OUT/'runtime-before.private.json').read_text(encoding='utf-8')) if row['name']=='novel-plus-local-admin-1')
name = config['admin']
assert name == 'novel-bootstrap-ui-admin-' + OUT.name.removeprefix('bootstrap-upgrade-')
envfile = OUT / 'ui-admin-env.private'
env = dict(line.split('=',1) for line in envfile.read_text(encoding='utf-8').splitlines())
assert config['mysql'] in env['SPRING_DATASOURCE_URL'] and env['SPRING_DATA_REDIS_HOST'] == config['redis']
image = baseline['image']
if args.mode == 'source':
    env['SPRING_THYMELEAF_PREFIX'] = 'file:/bootstrap-resources/templates/'
    env['SPRING_THYMELEAF_CACHE'] = 'false'
    env['SPRING_WEB_RESOURCES_STATIC_LOCATIONS'] = 'file:/bootstrap-resources/static/,classpath:/META-INF/resources/,classpath:/resources/,classpath:/static/,classpath:/public/'
else:
    image = json.loads((OUT/'package-verification.json').read_text(encoding='utf-8'))['admin']['image']
    for key in ('SPRING_THYMELEAF_PREFIX','SPRING_THYMELEAF_CACHE','SPRING_WEB_RESOURCES_STATIC_LOCATIONS'): env.pop(key, None)
modeenv = OUT / ('ui-admin-'+args.mode+'-env.private')
modeenv.write_text('\n'.join(key+'='+value for key,value in env.items())+'\n', encoding='utf-8')
subprocess.run(['docker','stop',name],check=True,capture_output=True)
subprocess.run(['docker','rm',name],check=True,capture_output=True)
command = ['docker','run','-d','--name',name,'--network','novel-plus-local_local','--env-file',str(modeenv),'-p','127.0.0.1:19388:8080']
for mount in baseline['mounts']:
    if mount['Destination']=='/var/pic': continue
    source = mount.get('Name') if mount['Type']=='volume' else mount['Source']
    command += ['--mount','type='+mount['Type']+',source='+source+',target='+mount['Destination']+',readonly']
command += ['--mount','type=volume,source=novel-bootstrap-ui-admin-uploads-'+OUT.name.removeprefix('bootstrap-upgrade-')+',target=/var/pic']
if args.mode == 'source': command += ['--mount','type=bind,source='+str(ROOT/'novel-admin/src/main/resources')+',target=/bootstrap-resources,readonly']
command += [image, 'java', '-jar', '/app/app.jar']
result = subprocess.run(command,check=True,capture_output=True,text=True)
subprocess.run(['docker','network','connect','novel-plus-local_access',name],check=True,capture_output=True)
(OUT/('ui-'+args.mode+'-runtime.json')).write_text(json.dumps(dict(name=name,id=result.stdout.strip(),image=image,sourceOverrides=args.mode=='source')),encoding='utf-8')
print(json.dumps(dict(mode=args.mode,name=name,image=image)))
