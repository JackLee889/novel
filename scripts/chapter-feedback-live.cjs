const fs=require('node:fs'),path=require('node:path'),cp=require('node:child_process'),assert=require('node:assert/strict'),crypto=require('node:crypto');
const {chromium,request}=require('C:/Users/lylboy/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const root=process.cwd(),run=fs.readFileSync('doc/chapter-feedback-current.txt','utf8').trim(),out=path.join(root,'doc',run),base='http://127.0.0.1:18987';
assert.match(run,/^chapter-feedback-\d{8}-\d{6}$/);
const marker='QA_CHAPTER_FEEDBACK_'+run.replaceAll('-','_');
const report={started:new Date().toISOString(),base,checks:[],layouts:[],pageErrors:[],persistedReports:[],blockedSideEffects:[]};
function save(){fs.writeFileSync(path.join(out,'live-feedback-validation.json'),JSON.stringify(report,null,2));}
function record(name,details={}){report.checks.push({name,passed:true,...details});save();console.log('PASS '+name);}
function sql(text){try{return cp.execFileSync('docker',['exec','-i','novel-plus-local-mysql-1','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --user=root --default-character-set=utf8mb4 --batch --raw --skip-column-names novel_plus'],{input:text,encoding:'utf8',windowsHide:true,maxBuffer:8*1024*1024}).trim().split(/\r?\n/).filter(Boolean).map(line=>line.split('\t'));}catch(error){fs.writeFileSync(path.join(out,'backup/live-sql-error.log'),String(error.stderr||error.message));throw new Error('SQL check failed; inspect the private log.');}}
function credentials(){const file=path.join(root,'doc/auth-history-20261007-104112/backup/test-credentials.dpapi.json').replace(/'/g,"''"),code=`$items=Get-Content -LiteralPath '${file}' -Raw | ConvertFrom-Json; $items | ForEach-Object { $s=ConvertTo-SecureString $_.encryptedPassword; $p=New-Object System.Management.Automation.PSCredential('qa',$s); [pscustomobject]@{username=$_.username;password=$p.GetNetworkCredential().Password} } | ConvertTo-Json`;return JSON.parse(cp.execFileSync('pwsh.exe',['-NoProfile','-Command',code],{encoding:'utf8',windowsHide:true}));}
let browser,api,context;
(async()=>{
  assert.equal(sql(`SELECT COUNT(*) FROM user_feedback WHERE LOCATE('${marker}',content)>0;`)[0][0],'0','Acceptance marker already exists; do not create duplicate reports.');
  const before=JSON.parse(fs.readFileSync(path.join(out,'database-before.json'),'utf8'));
  const main={bookId:'2106418450629967872',first:'2106418564102668288'},empty={bookId:'2107040802131267584',chapter:'2107067362993360896'};
  const chapters=sql(`SELECT CAST(id AS CHAR),index_name FROM book_index WHERE book_id=${main.bookId} ORDER BY index_num LIMIT 2;`);
  main.second=chapters[1][0];main.firstName=chapters[0][1];main.secondName=chapters[1][1];
  api=await request.newContext({baseURL:base});
  const accounts=[];
  for(const credential of credentials()){
    const response=await api.post('/user/login',{form:credential});assert.equal(response.status(),200);
    const result=await response.json();assert.equal(result.code,200);
    accounts.push({token:result.data.token,username:credential.username,id:sql(`SELECT CAST(id AS CHAR) FROM user WHERE username='${credential.username}';`)[0][0]});
  }
  const valid={bookId:main.bookId,bookIndexId:main.first,type:'typo',details:''};
  async function rejected(form,expected,token){const response=await api.post('/book/reader/feedback',{form,headers:token?{Authorization:token}:{}});assert.equal(response.status(),expected);assert.equal((await response.json()).code,expected===401?1001:expected);assert.match(response.headers()['cache-control'],/private.*no-store/);}
  await rejected(valid,401);
  for(const form of [{...valid,bookId:'0'},{...valid,bookIndexId:'-1'},{...valid,type:'unknown'},{...valid,type:'other',details:'  '},{...valid,details:'字'.repeat(201)},{...valid,bookId:empty.bookId},{...valid,bookIndexId:'9223372036854775808'}])await rejected(form,form.bookId===empty.bookId?404:400,accounts[0].token);
  assert.equal(sql('SELECT COUNT(*) FROM user_feedback;')[0][0],String(before.counts.user_feedback));
  record('真实接口拒绝未登录、无效参数、跨书章节和超长内容，未产生反馈记录',{requests:8});
  browser=await chromium.launch({channel:'chrome',headless:true,args:['--disable-gpu','--renderer-process-limit=1']});
  context=await browser.newContext({viewport:{width:1440,height:1000}});
  let fault=false,hold=false,attempts=0;
  await context.route('**/*',async route=>{
    const req=route.request(),url=new URL(req.url());
    if(url.hostname!=='127.0.0.1')return route.abort();
    if(req.method()==='POST'&&['/book/addVisitCount','/user/addReadHistory'].includes(url.pathname)){
      if(!report.blockedSideEffects.includes(url.pathname))report.blockedSideEffects.push(url.pathname);
      return route.fulfill({status:200,contentType:'application/json',body:'{"code":200,"data":null}'});
    }
    if(url.pathname==='/book/reader/feedback'&&req.method()==='POST'){
      attempts++;if(fault)return route.fulfill({status:503,contentType:'application/json',body:'{"code":503,"msg":"测试网络故障，请重试"}'});
      if(hold)await new Promise(resolve=>setTimeout(resolve,350));
    }
    return route.continue();
  });
  const page=await context.newPage();page.on('pageerror',error=>report.pageErrors.push(error.message));
  async function go(bookId,chapter){const response=await page.goto(`${base}/book/${bookId}/${chapter}.html`,{waitUntil:'domcontentloaded'});assert.equal(response.status(),200);await page.waitForFunction(id=>window.novelReader?.chapter?.bookIndexId===id,chapter,{timeout:30000});}
  async function open(){await page.locator('[data-feedback-open]').click();await page.waitForFunction(()=>document.querySelector('#chapter-feedback').open);}
  async function close(){await page.locator('[data-feedback-close]').click();await page.waitForFunction(()=>!document.querySelector('#chapter-feedback').open);}
  async function account(n,notify=true){await context.addCookies([{name:'Authorization',value:encodeURIComponent(accounts[n].token),url:base,sameSite:'Lax'}]);if(notify)await page.evaluate(async()=>{const auth=await import('/javascript/site/auth-state.js?v=20261007.4');auth.announceAuthChange();});}
  await go(main.bookId,main.first);await open();assert.equal(await page.locator('[data-feedback-book]').textContent(),'深空彼岸');assert.equal(await page.locator('[data-feedback-chapter]').textContent(),main.firstName);
  assert.equal(await page.locator('#chapter-feedback-form').isVisible(),false);
  assert.ok((await page.locator('[data-feedback-login] a').getAttribute('href')).includes(encodeURIComponent(`/book/${main.bookId}/${main.first}.html`)));
  await page.screenshot({path:path.join(out,'feedback-guest.png')});await page.keyboard.press('Escape');
  await page.waitForFunction(()=>!document.querySelector('#chapter-feedback').open);
  assert.equal(await page.evaluate(()=>document.activeElement.hasAttribute('data-feedback-open')),true);
  record('章节页低调入口、自动定位、未登录提示和 Escape 焦点返回正常');
  await account(0);await open();assert.equal(await page.locator('#chapter-feedback-form').isVisible(),true);
  await page.selectOption('#feedback-type','other');await page.fill('#feedback-details','旧章节草稿，不提交');
  await page.evaluate(id=>window.novelReader.navigate(id),main.second);
  await page.waitForFunction(id=>window.novelReader.chapter.bookIndexId===id,main.second);
  assert.equal(await page.locator('#chapter-feedback').evaluate(e=>e.open),false);
  await open();assert.equal(await page.locator('[data-feedback-chapter]').textContent(),main.secondName);assert.equal(await page.inputValue('#feedback-details'),'');
  record('软翻页关闭旧报告，重新打开对应当前章节，完整保留长整数 ID');
  const textA=marker+'_A 功能验收记录；原文 <标记> & 错字示例。';
  await page.selectOption('#feedback-type','other');await page.fill('#feedback-details','   ');await page.locator('#chapter-feedback-form [type=submit]').click();
  assert.equal(attempts,0);assert.match(await page.locator('[data-feedback-status]').textContent(),/描述/);
  await page.fill('#feedback-details',textA);fault=true;await page.locator('#chapter-feedback-form [type=submit]').click();
  await page.waitForFunction(()=>document.querySelector('[data-feedback-status]').textContent.includes('测试网络故障'));
  assert.equal(await page.inputValue('#feedback-details'),textA);assert.equal(sql('SELECT COUNT(*) FROM user_feedback;')[0][0],String(before.counts.user_feedback));
  fault=false;hold=true;const beforeAttempts=attempts;
  await page.evaluate(()=>{const form=document.querySelector('#chapter-feedback-form');form.requestSubmit();form.requestSubmit();});
  await page.waitForSelector('[data-feedback-success]:visible');hold=false;assert.equal(attempts,beforeAttempts+1);
  record('其他问题要求说明，失败保留草稿，重试及重复点击只提交一条记录');
  await page.screenshot({path:path.join(out,'feedback-success.png')});await close();await open();
  await page.fill('#feedback-details','账号 A 草稿，不提交');await account(1);
  await page.waitForFunction(()=>!document.querySelector('#chapter-feedback').open);await open();assert.equal(await page.inputValue('#feedback-details'),'');
  record('账号切换会关闭旧表单并清除私人草稿');await close();
  await go(empty.bookId,empty.chapter);assert.equal(await page.locator('.rd-paragraph').count(),0);await open();
  await page.selectOption('#feedback-type','missing');await page.fill('#feedback-details',marker+'_B 功能验收记录：导入源中此章正文为空，验证空章节也可反馈。');
  await page.locator('#chapter-feedback-form [type=submit]').click();await page.waitForSelector('[data-feedback-success]:visible');await close();
  record('空正文章节也有入口，能通过真实接口提交内容缺失报告');
  const rows=sql(`SELECT CAST(id AS CHAR),CAST(user_id AS CHAR),HEX(content) FROM user_feedback WHERE LOCATE('${marker}',content)>0 ORDER BY id;`).map(([id,userId,hex])=>({id,userId,content:Buffer.from(hex,'hex').toString('utf8')}));
  assert.equal(rows.length,2);assert.equal(rows[0].userId,accounts[0].id);assert.equal(rows[1].userId,accounts[1].id);
  assert.ok(rows[0].content.includes(`页面：/book/${main.bookId}/${main.second}.html`));assert.ok(rows[0].content.includes(`章节：${main.secondName}`));assert.ok(rows[0].content.endsWith(textA));
  assert.ok(rows[1].content.includes(`页面：/book/${empty.bookId}/${empty.chapter}.html`));report.persistedReports=rows.map(({id,userId})=>({id,userId}));
  for(const [n,item]of accounts.entries()){
    const response=await api.get('/user/listUserFeedBackByPage?curr=1&limit=10',{headers:{Authorization:item.token}});assert.equal(response.status(),200);
    const result=await response.json();assert.equal(result.code,200);const own=result.data.list.filter(f=>f.content.includes(marker));assert.equal(own.length,1);assert.equal(own[0].content,rows[n].content);
  }
  await account(0);await page.goto(base+'/user/feedback_list.html');await page.waitForSelector('#feedback-results .content-panel');
  const feedbackText=await page.locator('#feedback-results').textContent();assert.ok(feedbackText.includes('<标记> & 错字示例'));assert.ok(feedbackText.includes(main.secondName));assert.equal(await page.locator('#feedback-results script,#feedback-results img').count(),0);
  await page.screenshot({path:path.join(out,'feedback-account-list.png')});
  record('SQL 确认两条反馈及真实章节归属，两个账号仅能读取自己的报告，原文标记完整显示');
  const sampleBooks=sql('SELECT CAST(id AS CHAR),book_name FROM (SELECT id,book_name,ROW_NUMBER() OVER(PARTITION BY cat_id ORDER BY id) n FROM book) t WHERE n=1 ORDER BY id;');
  for(const [bookId]of sampleBooks){const chapter=sql(`SELECT CAST(id AS CHAR) FROM book_index WHERE book_id=${bookId} ORDER BY index_num LIMIT 1;`)[0][0];await go(bookId,chapter);assert.equal(await page.locator('[data-feedback-open]').count(),1);await open();assert.equal(await page.locator('[data-feedback-chapter]').textContent(),await page.locator('#chapter-title').textContent());await close();}
  record('全部七个分类的代表章节均由通用模板提供本章反馈入口',{categories:sampleBooks.length});
  await go(main.bookId,main.first);
  for(const width of [1440,390,320])for(const theme of ['light','dark']){
    await page.setViewportSize({width,height:width>1000?1000:844});await page.evaluate(theme=>window.novelReader.changeSetting('theme',theme),theme);
    await page.locator('[data-feedback-open]').scrollIntoViewIfNeeded();
    assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth+1));
    if(width===1440&&theme==='light')await page.screenshot({path:path.join(out,'feedback-entry-desktop.png')});
    if(width===390&&theme==='light')await page.screenshot({path:path.join(out,'feedback-entry-mobile.png')});
    await open();await page.selectOption('#feedback-type','typo');await page.fill('#feedback-details','响应式检查，不提交');
    const geometry=await page.locator('#chapter-feedback').boundingBox();assert.ok(geometry.x>=0&&geometry.x+geometry.width<=width+1);
    const controls=await page.locator('#chapter-feedback select,#chapter-feedback [type=submit]').evaluateAll(elements=>elements.map(e=>e.getBoundingClientRect().height));assert.ok(controls.every(height=>height>=44));
    assert.ok(await page.locator('#chapter-feedback .rd-panel-body').evaluate(e=>e.scrollWidth<=e.clientWidth+1));
    report.layouts.push({width,theme,passed:true});await page.screenshot({path:path.join(out,`feedback-dialog-${width}-${theme}.png`)});await close();
  }
  record('桌面、390 和 320 像素手机的日间及夜间窗口无横向溢出，控件可触达',{layouts:report.layouts.length});
  // Load the published admin scripts with synthetic rows to check its actual HTML renderer.
  const admin=await browser.newContext({viewport:{width:1440,height:900}}),adminPage=await admin.newPage();adminPage.on('pageerror',e=>report.pageErrors.push(e.message));
  const malicious='<img src=x onerror="window.__feedbackXss=1"> 原文 <标记> & 例子\n第二行';
  await admin.route('http://127.0.0.1:18088/chapter-feedback-render-fixture',route=>route.fulfill({status:200,contentType:'text/html',body:'<!doctype html><meta charset="utf-8"><form id="searchForm"></form><table id="exampleTable"></table><script>window.getFormJson=()=>({});window.layer={alert:()=>{}};</script>'}));
  await admin.route('**/novel/userFeedback/list*',route=>route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({code:0,data:{total:1,rows:[{userName:'渲染验收',content:malicious,createTime:'2026-10-07'}]}})}));
  await adminPage.goto('http://127.0.0.1:18088/chapter-feedback-render-fixture');
  for(const resource of ['/js/jquery.min.js?v=2.1.4','/js/plugins/bootstrap-table/bootstrap-table.js','/js/appjs/novel/userFeedback/userFeedback.js?v=20261007.6']){
    const response=await api.get('http://127.0.0.1:18088'+resource);assert.equal(response.status(),200);
    const content=await response.text();if(resource.includes('/appjs/'))assert.equal(crypto.createHash('sha256').update(content).digest('hex'),crypto.createHash('sha256').update(fs.readFileSync(path.join(root,'novel-admin/src/main/resources/static/js/appjs/novel/userFeedback/userFeedback.js'))).digest('hex'));
    await adminPage.addScriptTag({content});
  }
  await adminPage.waitForFunction(()=>document.querySelector('#exampleTable').textContent.includes('第二行'));
  assert.equal(await adminPage.locator('#exampleTable img,#exampleTable script').count(),0);assert.equal(await adminPage.evaluate(()=>window.__feedbackXss||0),0);
  assert.ok((await adminPage.locator('#exampleTable').textContent()).includes(malicious));
  assert.equal(await adminPage.locator('#exampleTable td').nth(2).evaluate(e=>getComputedStyle(e).whiteSpace),'pre-wrap');await admin.close();
  record('已发布的后台反馈脚本将报告作为文本分行显示，不执行原文中的 HTML',{data:'synthetic renderer fixture; real storage verified separately'});
  const tables=sql('SHOW TABLES;').flat(),counts={};for(const table of tables)counts[table]=Number(sql(`SELECT COUNT(*) FROM \`${table}\`;`)[0][0]);
  for(const [table,count]of Object.entries(before.counts))if(table!=='user_feedback')assert.equal(counts[table],count,table);
  const preserved=tables.filter(t=>!t.startsWith('book')&&t!=='user_feedback');
  const checksums=sql('CHECKSUM TABLE '+preserved.map(t=>'`'+t+'`').join(',')+' EXTENDED;').map(r=>r.join('\t')).join('\n')+'\n';assert.equal(checksums,before.preservedChecksums);
  const samples=sql('SELECT CAST(index_id AS CHAR),SHA2(content,256) FROM book_content WHERE index_id IN (2106418564102668288,2107067362993360896);').map(r=>r.join('\t')).join('\n')+'\n';assert.equal(samples,before.contentSamples);
  fs.writeFileSync(path.join(out,'database-after.json'),JSON.stringify({counts,preservedChecksums:checksums,contentSamples:samples},null,2));
  record('原书库各表数量、正文样本及账号和系统配置校验值保持一致，仅增加反馈记录',{books:counts.book,chapters:counts.book_index,bodies:counts.book_content,feedbackDelta:counts.user_feedback-before.counts.user_feedback});
  assert.deepEqual(report.pageErrors,[]);report.finished=new Date().toISOString();report.passed=true;save();
})().catch(error=>{report.passed=false;report.error=error.stack;save();console.error(error.message);process.exitCode=1;}).finally(async()=>{await context?.close();await api?.dispose();await browser?.close();});
