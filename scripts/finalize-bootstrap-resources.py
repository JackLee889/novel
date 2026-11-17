"""Finish owned markup details and grant only the isolated test account UI access."""
import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'doc' / (ROOT / 'doc/bootstrap-upgrade-current.txt').read_text(encoding='utf-8').strip()
for path in (ROOT/'novel-admin/src/main/resources/templates').rglob('*.html'):
    original=path.read_text(encoding='utf-8')
    updated=re.sub(r"(var s_\w+_h\s*=\s*['\"])hidden(['\"])",r'\1d-none\2',original)
    if original != updated: path.write_text(updated,encoding='utf-8',newline='\n')

config=json.loads((OUT/'ui-sandbox.private.json').read_text(encoding='utf-8'))
sql="""INSERT INTO sys_role(role_id,role_name,role_sign,remark,user_id_create,gmt_create,gmt_modified) VALUES(900000001,'Bootstrap UI acceptance','bootstrap_ui','isolated acceptance only',900000001,NOW(),NOW());
INSERT INTO sys_menu(menu_id,parent_id,name,perms,type,order_num,gmt_create,gmt_modified) VALUES(900000001,0,'Bootstrap isolated test permissions','*',2,999,NOW(),NOW());
INSERT INTO sys_role_menu(id,role_id,menu_id) VALUES(900000001,900000001,900000001);
INSERT INTO sys_user_role(id,user_id,role_id) VALUES(900000002,900000001,900000001);
SELECT JSON_OBJECT('bookId',CAST(id AS CHAR)) FROM book LIMIT 1;
SELECT JSON_OBJECT('indexId',CAST(id AS CHAR)) FROM book_index ORDER BY id LIMIT 1;
SELECT JSON_OBJECT('contentId',CAST(id AS CHAR)) FROM book_content ORDER BY id LIMIT 1;
SELECT JSON_OBJECT('deptId',CAST(dept_id AS CHAR)) FROM sys_dept ORDER BY dept_id LIMIT 1;
SELECT JSON_OBJECT('categoryId',CAST(id AS CHAR)) FROM book_category ORDER BY id LIMIT 1;
"""
assert config['mysql'].startswith('novel-bootstrap-ui-mysql-')
result=subprocess.run(['docker','exec','-i',config['mysql'],'sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -N -B -uroot novel_plus'],input=sql,capture_output=True,text=True,encoding='utf-8',check=True)
samples={}
for line in result.stdout.splitlines(): samples.update(json.loads(line))
(OUT/'ui-samples.json').write_text(json.dumps(samples,indent=2),encoding='utf-8')
print('Owned permission classes migrated; isolated UI account permissions seeded.')
