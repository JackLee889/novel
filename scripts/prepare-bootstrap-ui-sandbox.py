"""Isolate logged-in UI acceptance from the original MySQL/Redis/files."""
import hashlib
import json
import secrets
import subprocess
import time
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'doc' / (ROOT / 'doc/bootstrap-upgrade-current.txt').read_text(encoding='utf-8').strip()
suffix = OUT.name.removeprefix('bootstrap-upgrade-')
database = 'novel-bootstrap-ui-mysql-' + suffix
redis = 'novel-bootstrap-ui-redis-' + suffix
admin = 'novel-bootstrap-ui-admin-' + suffix

def run(command, data=None):
    result = subprocess.run(command, input=data, capture_output=True)
    if result.returncode: raise RuntimeError(result.stderr.decode('utf-8', errors='replace')[-1600:])
    return result.stdout

def original_query(sql):
    return run(['docker', 'exec', '-i', 'novel-plus-local-mysql-1', 'sh', '-c',
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --default-character-set=utf8mb4 --raw --batch --skip-column-names -uroot novel_plus'], sql.encode()).decode('utf-8')

private = OUT / 'ui-sandbox.private.json'
if '--resume' in sys.argv:
    config = json.loads(private.read_text(encoding='utf-8'))
    dbpassword, user, password = config['databasePassword'], config['username'], config['password']
else:
    assert not private.exists(), 'Inspect the existing sandbox before repeating setup.'
    dbpassword, user, password = secrets.token_urlsafe(30), 'bootstrap_ui_test', secrets.token_urlsafe(20)
    private.write_text(json.dumps(dict(mysql=database, redis=redis, admin=admin, username=user, password=password, port=19388,
        databasePassword=dbpassword, originalDatabaseWasReadOnly=True), indent=2), encoding='utf-8')
    db_env = OUT / 'ui-mysql-env.private'
    db_env.write_text('MYSQL_ROOT_PASSWORD='+dbpassword+'\nMYSQL_DATABASE=novel_plus\n', encoding='utf-8')
    run(['docker', 'run', '-d', '--name', database, '--network', 'novel-plus-local_local', '--env-file', str(db_env),
         '--mount', 'type=volume,source=novel-bootstrap-ui-mysql-data-'+suffix+',target=/var/lib/mysql', 'mysql:8.0.46'])
    run(['docker', 'run', '-d', '--name', redis, '--network', 'novel-plus-local_local', 'redis:7.4-alpine'])
for _ in range(45):
    ready = subprocess.run(['docker','exec',database,'sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -h127.0.0.1 -uroot -e "SELECT 1"'], capture_output=True)
    if ready.returncode == 0: break
    time.sleep(2)
else: raise RuntimeError('Sandbox MySQL did not start')
empty = run(['docker','exec',database,'sh','-c', 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -h127.0.0.1 -uroot -N -B -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=\'novel_plus\'"']).decode().strip()
assert empty == '0', 'Sandbox already has tables; do not overwrite existing records.'

tables = original_query('SHOW TABLES;').splitlines()
safe = [table for table in tables if table.startswith('sys_') and table not in {'sys_user','sys_user_role','sys_log'}]
safe += ['book_category', 'news_category', 'website_info', 'crawl_source']
dump_command = 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --default-character-set=utf8mb4 --single-transaction --no-tablespaces --set-gtid-purged=OFF '
schema = run(['docker','exec','novel-plus-local-mysql-1','sh','-c',dump_command+'--no-data --skip-add-drop-table novel_plus'])
data = run(['docker','exec','novel-plus-local-mysql-1','sh','-c',dump_command+'--no-create-info --skip-add-drop-table novel_plus '+' '.join(safe)])
# Representative existing book/chapters exercise actual server-rendered list/edit forms.
book = original_query('SELECT book_id FROM book_index WHERE is_vip=0 ORDER BY id LIMIT 1;').strip()
assert book.isdecimal()
for table, where in [('book','id='+book),('book_index','book_id='+book+' LIMIT 3'),
                     ('book_content','index_id IN (SELECT id FROM book_index WHERE book_id='+book+') LIMIT 3')]:
    data += run(['docker','exec','novel-plus-local-mysql-1','sh','-c',dump_command+'--no-create-info --skip-add-drop-table --where="'+where+'" novel_plus '+table])
digest = hashlib.md5((user+'1qazxsw2').encode()+password.encode()).digest()
digest = hashlib.md5(digest).hexdigest()
dept = original_query('SELECT dept_id FROM sys_dept ORDER BY dept_id LIMIT 1;').strip()
assert dept.isdecimal()
seed = ("\nINSERT INTO sys_user(user_id,username,name,password,email,dept_id,status,gmt_create,gmt_modified,sex) VALUES"
        "(900000001,'"+user+"','Bootstrap UI test','"+digest+"','bootstrap-test@example.invalid',"+dept+",1,NOW(),NOW(),1);\n"
        "INSERT INTO sys_user_role(id,user_id,role_id) VALUES(900000001,900000001,1);\n").encode()
sqlfile = OUT / 'ui-sandbox-copy.private.sql'
sqlfile.write_bytes(schema+data+seed)
run(['docker','exec','-i',database,'sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --default-character-set=utf8mb4 -uroot novel_plus'], sqlfile.read_bytes())

baseline = next(row for row in json.loads((OUT/'runtime-before.private.json').read_text(encoding='utf-8')) if row['name']=='novel-plus-local-admin-1')
env = dict(item.split('=',1) for item in baseline['env'])
env.update(SPRING_DATASOURCE_URL='jdbc:mysql://'+database+':3306/novel_plus?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai',
           SPRING_DATASOURCE_USERNAME='root', SPRING_DATASOURCE_PASSWORD=dbpassword,
           SPRING_DATA_REDIS_HOST=redis, SPRING_DATA_REDIS_PORT='6379', SPRING_DATA_REDIS_PASSWORD='',
           ADMIN_SECURITY_SECURE_COOKIE='false', SPRING_THYMELEAF_MODE='HTML',
           JAVA2NB_UPLOAD_PATH='/var/pic/', SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE='4', SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE='1')
envfile = OUT / 'ui-admin-env.private'
envfile.write_text('\n'.join(key+'='+value for key,value in env.items())+'\n', encoding='utf-8')
command = ['docker','run','-d','--name',admin,'--network','novel-plus-local_local','--env-file',str(envfile),'-p','127.0.0.1:19388:8080']
for mount in baseline['mounts']:
    if mount['Destination']=='/var/pic': continue
    source = mount.get('Name') if mount['Type']=='volume' else mount['Source']
    command += ['--mount','type='+mount['Type']+',source='+source+',target='+mount['Destination']+',readonly']
command += ['--mount','type=volume,source=novel-bootstrap-ui-admin-uploads-'+suffix+',target=/var/pic',baseline['image']]
run(command)
run(['docker', 'network', 'connect', 'novel-plus-local_access', admin])
(OUT/'ui-sandbox.json').write_text(json.dumps(dict(mysql=database,redis=redis,admin=admin,port=19388,
    sourceDataCopiedReadOnly=True, schemaTables=len(tables), fullDataTables=safe, sampledBook=True, dedicatedAccountId=900000001,
    originalCredentialsUnchanged=True),indent=2),encoding='utf-8')
print(json.dumps(dict(isolated=True,admin=admin,port=19388,schemaTables=len(tables))))
