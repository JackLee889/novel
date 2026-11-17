"""Use retained acceptance data; launch only owned isolated UI services."""
import argparse,json,subprocess,shutil
from pathlib import Path
ROOT=Path(__file__).resolve().parent.parent
OUT=ROOT/'doc'/(ROOT/'doc/layui-jquery-upgrade-current.txt').read_text(encoding='utf-8').strip()
OLD=ROOT/'doc/bootstrap-upgrade-20261008-075538'
SUFFIX=OUT.name.removeprefix('layui-jquery-upgrade-')
baseline={row['name']:row for row in json.loads((OUT/'runtime-before.private.json').read_text(encoding='utf-8'))}
config=json.loads((OLD/'ui-sandbox.private.json').read_text(encoding='utf-8'))
config.update(admin='novel-layui-jquery-ui-admin-'+SUFFIX,crawl='novel-layui-jquery-ui-crawl-'+SUFFIX,front='novel-layui-jquery-ui-front-'+SUFFIX)
def run(args):
    p=subprocess.run(args,capture_output=True,text=True,encoding='utf-8',errors='replace')
    if p.returncode:raise RuntimeError(p.stderr[-1600:])
    return p.stdout
parser=argparse.ArgumentParser();parser.add_argument('mode',choices=('source','built'));mode=parser.parse_args().mode
assert config['mysql']=='novel-bootstrap-ui-mysql-20261008-075538'
run(['docker','start',config['mysql'],config['redis']])
(OUT/'ui-sandbox.private.json').write_text(json.dumps(config,indent=2),encoding='utf-8')
for name in ('ui-samples.json','ui-dicts.json'):shutil.copy2(OLD/name,OUT/name)
existing=run(['docker','ps','-a','--format','{{.Names}}']).splitlines()
for module,port in [('admin',19388),('crawl',19381),('front',19387)]:
    name=config[module]
    if name in existing:
        # This task's replaceable container only; retain every attached volume.
        run(['docker','stop',name]);run(['docker','rm',name])
    original='novel-plus-local-'+('front-realtest' if module=='front' else module)+'-1'
    before=baseline[original];env=dict(item.split('=',1) for item in before['env'])
    env.update(SPRING_DATASOURCE_URL='jdbc:mysql://'+config['mysql']+':3306/novel_plus?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai',SPRING_DATASOURCE_USERNAME='root',SPRING_DATASOURCE_PASSWORD=config['databasePassword'],SPRING_DATA_REDIS_HOST=config['redis'],SPRING_DATA_REDIS_PORT='6379',SPRING_DATA_REDIS_PASSWORD='',SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE='4',SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE='1')
    if module=='admin':env.update(ADMIN_SECURITY_SECURE_COOKIE='false',SPRING_THYMELEAF_MODE='HTML',JAVA2NB_UPLOAD_PATH='/var/pic/')
    if module=='crawl':env['CRAWL_JOBS_ENABLED']='false'
    if mode=='source':env.update(SPRING_THYMELEAF_PREFIX='file:/upgrade-resources/templates/',SPRING_THYMELEAF_CACHE='false',SPRING_WEB_RESOURCES_STATIC_LOCATIONS='file:/upgrade-resources/static/,classpath:/META-INF/resources/,classpath:/resources/,classpath:/static/,classpath:/public/')
    else:
        for key in ('SPRING_THYMELEAF_PREFIX','SPRING_THYMELEAF_CACHE','SPRING_WEB_RESOURCES_STATIC_LOCATIONS'):env.pop(key,None)
    envfile=OUT/('ui-'+module+'-'+mode+'-env.private');envfile.write_text('\n'.join(k+'='+v for k,v in env.items())+'\n',encoding='utf-8')
    image=before['image'] if mode=='source' else json.loads((OUT/'package-verification.json').read_text(encoding='utf-8'))[module]['image']
    args=['docker','run','-d','--name',name,'--network','novel-plus-local_local','--env-file',str(envfile),'-p','127.0.0.1:'+str(port)+':8080']
    for mount in before['mounts']:
        src=mount.get('Name') if mount['Type']=='volume' else mount['Source'];args+=['--mount','type='+mount['Type']+',source='+src+',target='+mount['Destination']+',readonly']
    if module=='admin':args+=['--mount','type=volume,source=novel-bootstrap-ui-admin-uploads-20261008-075538,target=/var/pic']
    if mode=='source':args+=['--mount','type=bind,source='+str(ROOT/('novel-'+module)/'src/main/resources')+',target=/upgrade-resources,readonly']
    args+=[image,'java','-jar','/app/app.jar'];cid=run(args).strip();run(['docker','network','connect','novel-plus-local_access',name])
    (OUT/('ui-'+module+'-'+mode+'-runtime.json')).write_text(json.dumps(dict(name=name,id=cid,image=image,isolated=True,port=port)),encoding='utf-8')
    print(module,mode,port)
