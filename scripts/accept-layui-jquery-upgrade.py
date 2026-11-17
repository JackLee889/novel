"""Verify current candidate/runtime resources and security without business writes."""
import argparse
import hashlib
import json
import time
from html.parser import HTMLParser
from pathlib import Path
import requests
import subprocess

ROOT=Path(__file__).resolve().parent.parent
OUT=ROOT/'doc'/(ROOT/'doc/layui-jquery-upgrade-current.txt').read_text(encoding='utf-8').strip()
parser=argparse.ArgumentParser();parser.add_argument('phase',choices=('candidate','runtime'));phase=parser.parse_args().phase
packages=json.loads((OUT/'package-verification.json').read_text(encoding='utf-8'))
baseline={row['name']:row for row in json.loads((OUT/'runtime-before.private.json').read_text(encoding='utf-8'))}
urls={'admin':'http://127.0.0.1:19288','crawl':'http://127.0.0.1:19281'} if phase=='candidate' else {'admin':'http://127.0.0.1:18088','crawl':'http://127.0.0.1:18081'}
report=dict(phase=phase,checks=[],passed=False,packageSha256={module:row['sha256'] for module,row in packages.items()})
def check(name,condition,**info):
    assert condition,(name,info)
    report['checks'].append(dict(name=name,**info))
class Markup(HTMLParser):
    def __init__(self,text):super().__init__();self.meta={};self.inputs={};self.feed(text)
    def handle_starttag(self,tag,attrs):
        data=dict(attrs)
        if tag=='meta' and data.get('name'):self.meta[data['name']]=data.get('content','')
        if tag=='input' and data.get('name'):self.inputs[data['name']]=data.get('value','')
for module,base in urls.items():
    session=requests.Session();path='/login' if module=='admin' else '/login.html'
    for attempt in range(30):
        try:
            response=session.get(base+path,timeout=10)
            if response.status_code==200:break
        except requests.RequestException:pass
        time.sleep(2)
    else:raise RuntimeError(module+' startup not ready')
    check(module+' login',response.status_code==200)
    markup=Markup(response.text);check(module+' CSRF metadata',bool(markup.meta.get('_csrf')) and bool(markup.meta.get('_csrf_header')))
    csrf=markup.meta['_csrf'];header=markup.meta['_csrf_header']
    rejected=session.post(base+'/login',data=dict(username='bootstrap_invalid',password='invalid',verify='0000'),allow_redirects=False,timeout=10)
    check(module+' missing CSRF rejected',rejected.status_code in ((403,) if module=='admin' else (401,403)),status=rejected.status_code)
    source=ROOT/('novel-'+module)/'src/main/resources/static/css/bootstrap.min.css'
    css=session.get(base+'/css/bootstrap.min.css?v=5.3.8',timeout=20)
    check(module+' Bootstrap CSS checksum',css.status_code==200 and hashlib.sha256(css.content).digest()==hashlib.sha256(source.read_bytes()).digest())
    if module=='admin':
        for resource in ('js/bootstrap.min.js','js/bootstrap-compat.js','css/bootstrap-compat.css','js/csrf.js','js/vendor/jquery/jquery-4.0.0.min.js','js/vendor/jquery/jquery-migrate-4.0.2.min.js','js/vendor/jquery/jquery-legacy-compat.js','js/vendor/layui/layui.js','js/vendor/layui/css/layui.css','js/vendor/layui/font/iconfont.woff2','js/vendor/layui/layui-compat.js'):
            fetched=session.get(base+'/'+resource+'?v=5.3.8',timeout=20)
            expected=ROOT/'novel-admin/src/main/resources/static'/resource
            check('admin '+resource+' checksum',fetched.status_code==200 and fetched.content==expected.read_bytes())
        protected=session.get(base+'/novel/book',allow_redirects=False,timeout=10)
        check('admin protected page',protected.status_code in (302,401,403))
        logout=session.get(base+'/logout',allow_redirects=False,timeout=10)
        check('admin GET logout rejected',logout.status_code==405,status=logout.status_code)
        captcha=session.get(base+'/getVerify',timeout=10)
        check('admin CAPTCHA',captcha.status_code==200 and captcha.headers.get('Content-Type','').startswith('image/'))
    else:
        check('crawl login v5 classes','visually-hidden' in response.text and 'w-100' in response.text and '?v=5.3.8' in response.text)
        env=dict(item.split('=',1) for item in baseline['novel-plus-local-crawl-1']['env'])
        signed=session.post(base+'/login',headers={header:csrf},data={'username':env['ADMIN_USERNAME'],'password':env['ADMIN_PASSWORD'],'_csrf':csrf},allow_redirects=False,timeout=10)
        check('crawl real login',signed.status_code==302,status=signed.status_code)
        for resource in ('vendor/jquery/jquery-4.0.0.min.js','vendor/jquery/jquery-migrate-4.0.2.min.js','vendor/jquery/jquery-legacy-compat.js','vendor/layui/layui.js','vendor/layui/css/layui.css','vendor/layui/font/iconfont.woff2','vendor/layui/layui-compat.js'):
            fetched=session.get(base+'/'+resource,timeout=20)
            check('crawl '+resource+' checksum',fetched.status_code==200 and fetched.content==(ROOT/'novel-crawl/src/main/resources/static'/resource).read_bytes())
        for source in (1,2,4,12):
            rules=session.get(base+'/crawl/getCrawlSource/'+str(source),timeout=20)
            row=rules.json()['data'];check('crawl source '+str(source)+' remains stopped',rules.status_code==200 and int(row['id'])==source and row['sourceStatus']==0)
        home=session.get(base+'/',timeout=10);check('crawl authenticated home',home.status_code==200)
        authed=Markup(home.text);token=authed.meta['_csrf']
        rejected=session.post(base+'/crawl/testParse',data={'url':'invalid','rule':'unused'},timeout=10)
        check('crawl authenticated AJAX missing CSRF rejected',rejected.status_code==403,status=rejected.status_code)
        accepted=session.post(base+'/crawl/testParse',headers={'X-CSRF-TOKEN':token},data={'url':'invalid','rule':'unused'},timeout=10)
        check('crawl legitimate AJAX accepted without fetching remote source',accepted.status_code==200 and accepted.json()['data'].get('成功') is False)
        check('crawl security hook',session.get(base+'/js/csrf.js',timeout=10).content==(ROOT/'novel-crawl/src/main/resources/static/js/csrf.js').read_bytes())
for frontbase in (('http://127.0.0.1:19287',) if phase=='candidate' else ('http://127.0.0.1:18987','http://127.0.0.1:18080')):
    check('front home '+frontbase,requests.get(frontbase+'/',timeout=20).status_code==200)
    for resource in ('vendor/jquery/jquery-4.0.0.min.js','vendor/jquery/jquery-migrate-4.0.2.min.js','vendor/jquery/jquery-legacy-compat.js','vendor/layui/layui.js','vendor/layui/css/layui.css','vendor/layui/font/iconfont.woff2','vendor/layui/layui-compat.js','javascript/jquery-1.8.0.min.js','mobile/js/jquery-1.9.1.js'):
        fetched=requests.get(frontbase+'/'+resource,timeout=20)
        check('front '+frontbase+' '+resource+' checksum',fetched.status_code==200 and fetched.content==(ROOT/'novel-front/src/main/resources/static'/resource).read_bytes())
    protected=requests.get(frontbase+'/user/queryIsInShelf?bookId=2106418450629967872',allow_redirects=False,timeout=15)
    check('front account authorization retained '+frontbase,protected.status_code in (401,403) or (protected.status_code==200 and protected.json().get('code')==1001 and not protected.json().get('data')))
if phase=='runtime':
    for base in ('http://127.0.0.1:18987','http://127.0.0.1:18080'):
        response=requests.get(base+'/',timeout=20);check('reader homepage '+base,response.status_code==200)
        for path in ('/user/pay.html','/author/index.html','/pay/notify'):
            disabled=requests.get(base+path,timeout=15,allow_redirects=False)
            check('disabled feature '+base+path,disabled.status_code==403,status=disabled.status_code)
report['passed']=True
(OUT/(phase+'-acceptance.json')).write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(dict(phase=phase,passed=True,checks=len(report['checks']))))
