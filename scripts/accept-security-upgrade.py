"""HTTP acceptance without starting collection, purchasing, publishing or changing business records."""
import argparse
import json
import time
from html.parser import HTMLParser
from pathlib import Path
import requests
import subprocess

ROOT=Path(__file__).resolve().parent.parent
OUT=ROOT/'doc/security-dependency-upgrade-20261008-003503'
parser=argparse.ArgumentParser();parser.add_argument('phase',choices=('candidate','runtime'))
phase=parser.parse_args().phase
urls=({'front':'http://127.0.0.1:19287','admin':'http://127.0.0.1:19288','crawl':'http://127.0.0.1:19281'} if phase=='candidate'
      else {'front':'http://127.0.0.1:18987','legacyFront':'http://127.0.0.1:18080','admin':'http://127.0.0.1:18088','crawl':'http://127.0.0.1:18081'})
snapshot=json.loads((OUT/'database-before.json').read_text())
free=next(row for row in snapshot if row['kind']=='freeChapter')
report=dict(phase=phase,passed=False,readerPcmVerified=False,checks=[])
packages=json.loads((OUT/'package-verification.json').read_text())
report['packageSha256']={module:row['sha256'] for module,row in packages.items()}
if phase=='candidate':
    containers={row['name']:row['service'] for row in json.loads((OUT/'candidate-runtime.json').read_text())}
else:
    containers={'novel-plus-local-front-realtest-1':'front-realtest','novel-plus-local-front-1':'front',
                'novel-plus-local-admin-1':'admin','novel-plus-local-crawl-1':'crawl'}
for name,service in containers.items():
    module='front' if service=='front-realtest' else service
    digest=subprocess.check_output(['docker','exec',name,'sha256sum','/app/app.jar'],text=True).split()[0]
    assert digest==report['packageSha256'][module], 'HTTP acceptance must use the current verified JAR'

class Markup(HTMLParser):
    def __init__(self,text):
        super().__init__();self.meta={};self.inputs={};self.feed(text)
    def handle_starttag(self,tag,attrs):
        data=dict(attrs)
        if tag=='meta' and data.get('name'):self.meta[data['name']]=data.get('content','')
        if tag=='input' and data.get('name'):self.inputs[data['name']]=data.get('value','')

def request(session,method,url,**kwargs):
    response=session.request(method,url,timeout=30,allow_redirects=False,**kwargs)
    return response

def check(name,condition,**details):
    assert condition,(name,details)
    report['checks'].append(dict(name=name,**details))

for name,base in urls.items():
    session=requests.Session()
    path='/login.html' if name=='crawl' else '/login' if name=='admin' else '/'
    for attempt in range(24):
        try:
            page=request(session,'GET',base+path)
            if page.status_code==200:break
        except requests.RequestException:pass
        time.sleep(5)
    else:raise RuntimeError(name+' startup did not become ready')
    check(name+' startup',page.status_code==200,status=page.status_code)
    if name in ('front','legacyFront'):
        chapter=request(session,'GET',base+'/book/reader/chapter',params=dict(bookId=free['bookId'],bookIndexId=free['indexId']))
        body=chapter.json()['data']
        check(name+' free chapter',chapter.status_code==200 and body['access']=='allowed' and bool(body['paragraphs'])
              and isinstance(body['bookIndexId'],str),paragraphs=len(body['paragraphs']))
        for path in ('/user/pay.html','/author/index.html','/pay/notify'):
            denied=request(session,'GET',base+path)
            check(name+' disabled '+path,denied.status_code==403,status=denied.status_code)
        denied=request(session,'POST',base+'/file/picUpload')
        check(name+' submission upload disabled',denied.status_code==403,status=denied.status_code)
        if name=='front':
            wasm=request(session,'HEAD',base+'/reader/tts/1.13.8-matcha-icefall-zh-en/sherpa-onnx-wasm-main-tts.wasm')
            check('TTS WASM',wasm.status_code==200 and 'application/wasm' in wasm.headers.get('Content-Type',''),status=wasm.status_code)
    else:
        markup=Markup(page.text);token=markup.meta.get('_csrf')
        check(name+' rendered CSRF token',bool(token) and (name=='admin' or markup.inputs.get('_csrf')==token))
        js=request(session,'GET',base+'/js/csrf.js')
        check(name+' CSRF client',js.status_code==200 and markup.meta.get('_csrf_header')=='X-CSRF-TOKEN'
              and 'setRequestHeader' in js.text and 'localUnsafe' in js.text)
        # A rejected anonymous request can populate Spring Security's saved-request cache.
        # Keep the rejection probe separate from the subsequent normal form-login session.
        negative=requests.Session()
        request(negative,'GET',base+('/login' if name=='admin' else '/login.html'))
        denied=request(negative,'POST',base+'/login',data=dict(username='unused-check'))
        check(name+' login without CSRF rejected',denied.status_code in ((403,) if name=='admin' else (401,403)),status=denied.status_code)
        if name=='admin':
            check('admin password not prefilled',not markup.inputs.get('password'))
            captcha=request(session,'GET',base+'/getVerify')
            check('admin CAPTCHA available',captcha.status_code==200 and captcha.headers.get('Content-Type','').startswith('image/'))
            for path in ('/common/sysFile/download?filePath=../app.jar','/novel/book/list?sort=id%3BSELECT&offset=0&limit=10',
                         '/common/sysFile/download;anything?filePath=app.jar','/common/sysFile/%2e%2e/download?filePath=app.jar'):
                blocked=request(session,'GET',base+path)
                check('admin protected '+path,blocked.status_code in (302,303,400,403,404),status=blocked.status_code)
            logout=request(session,'GET',base+'/logout')
            check('admin GET logout rejected',logout.status_code==405,status=logout.status_code)
        else:
            before=json.loads((OUT/'runtime-before.private.json').read_text())
            env=dict(item.split('=',1) for item in next(row for row in before if row['name']=='novel-plus-local-crawl-1')['env'])
            login=request(session,'POST',base+'/login',data=dict(username=env['ADMIN_USERNAME'],password=env['ADMIN_PASSWORD'],_csrf=token))
            check('crawler credentials retained and login accepted',login.status_code in (302,303) and 'error' not in login.headers.get('Location',''))
            home=request(session,'GET',base+'/')
            check('crawler authenticated home',home.status_code==200)
            token=Markup(home.text).meta['_csrf']
            for source in (4,12):
                row=request(session,'GET',base+'/crawl/getCrawlSource/'+str(source)).json()['data']
                check('crawler source '+str(source)+' remains stopped',int(row['id'])==source and int(row['sourceStatus'])==0)
            denied=request(session,'POST',base+'/crawl/testParse',data=dict(url='invalid',rule='unused'))
            check('crawler AJAX missing CSRF rejected',denied.status_code==403)
            valid=request(session,'POST',base+'/crawl/testParse',data=dict(url='invalid',rule='unused'),headers={'X-CSRF-TOKEN':token})
            check('crawler legitimate AJAX token accepted',valid.status_code==200 and valid.json()['data'].get('成功') is False)
            logout=request(session,'POST',base+'/logout',data={'_csrf':token})
            check('crawler legitimate POST logout accepted',logout.status_code in (204,302,303),status=logout.status_code)
            denied=request(session,'GET',base+'/crawl/getCrawlSource/4',headers={'Accept':'application/json'})
            check('crawler logout revokes access',denied.status_code in (401,403),status=denied.status_code)
pcm=OUT/('candidate-reader-pcm.json' if phase=='candidate' else 'runtime-reader-pcm.json')
if pcm.is_file():
    playback=json.loads(pcm.read_text())
    report['readerPcmVerified']=playback.get('passed',False) and playback.get('frontJarSha256')==report['packageSha256']['front']
report['passed']=True
(OUT/(phase+'-acceptance.json')).write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(dict(phase=phase,passed=report['passed'],checks=len(report['checks']),readerPcmVerified=report['readerPcmVerified'])))
