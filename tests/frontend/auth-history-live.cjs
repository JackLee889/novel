// Regression against an actual candidate backend and the two retained acceptance accounts.
// No credentials, cookies or tokens are written to the public evidence.
const fs=require('node:fs'),path=require('node:path'),cp=require('node:child_process'),assert=require('node:assert/strict');
const deps=path.join(process.env.USERPROFILE,'.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules');
const {chromium}=require(require.resolve('playwright',{paths:[deps,__dirname]}));
const root=path.resolve(__dirname,'../..'),out=process.env.NOVEL_TEST_EVIDENCE_DIR?path.resolve(process.env.NOVEL_TEST_EVIDENCE_DIR):path.join(root,'doc/auth-history-fix-20261007'),base=process.env.AUTH_HISTORY_TEST_URL||'http://127.0.0.1:18989';
assert.ok(out.startsWith(path.join(root,'doc')+path.sep)&&fs.statSync(out).isDirectory(),'Evidence directory must exist under doc.');
assert.equal(new URL(base).hostname,'127.0.0.1');
const report={started:new Date().toISOString(),base,checks:[],responses:[],pageErrors:[]};
const redact=value=>Array.isArray(value)?value.map(redact):value&&typeof value==='object'?Object.fromEntries(Object.entries(value).map(([k,v])=>[k,/password|token|authorization|cookie/i.test(k)?'[REDACTED]':redact(v)])):value;
function save(){fs.writeFileSync(path.join(out,'live-results.json'),JSON.stringify(report,null,2));}
function record(name,details={}){report.checks.push({name,result:'pass',time:new Date().toISOString(),...redact(details)});save();console.log(name);}
function credentials(){const file=path.join(root,'doc/auth-history-20261007-104112/backup/test-credentials.dpapi.json').replace(/'/g,"''");const script=`$items=Get-Content -LiteralPath '${file}' -Raw | ConvertFrom-Json; $items | ForEach-Object { $s=ConvertTo-SecureString $_.encryptedPassword; $p=New-Object System.Management.Automation.PSCredential('qa',$s); [pscustomobject]@{username=$_.username;password=$p.GetNetworkCredential().Password} } | ConvertTo-Json`;return JSON.parse(cp.execFileSync('pwsh.exe',['-NoProfile','-Command',script],{encoding:'utf8',windowsHide:true}));}
const accounts=credentials();
function sql(query){const result=cp.execFileSync('docker',['exec','-i','novel-plus-local-mysql-1','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --user=root --default-character-set=utf8mb4 --batch --raw --skip-column-names novel_plus'],{input:query,encoding:'utf8',windowsHide:true});return result.trim()?result.trim().split(/\r?\n/).map(v=>v.split('\t')):[];}
function snapshot(name){const names=accounts.map(a=>"'"+a.username+"'").join(',');const scope='select id from user where username in ('+names+')';const history=sql('select cast(h.id as char),u.username,cast(h.book_id as char),cast(h.pre_content_id as char),h.create_time,h.update_time,cast(i.book_id as char) from user_read_history h join user u on u.id=h.user_id left join book_index i on i.id=h.pre_content_id where h.user_id in ('+scope+') order by h.user_id,h.create_time desc,h.id desc;').map(r=>Object.fromEntries(['id','username','bookId','chapterId','created','updated','chapterBookId'].map((k,i)=>[k,r[i]])));const shelf=sql('select * from user_bookshelf where user_id in ('+scope+') order by id;');const result={history,shelf};fs.writeFileSync(path.join(out,'sql-'+name+'.json'),JSON.stringify(result,null,2));return result;}
const ready=page=>page.waitForFunction(()=>document.documentElement.dataset.ready==='true',null,{timeout:35000});
async function shot(page,name){await page.screenshot({path:path.join(out,name+'.png'),fullPage:true});}
async function request(context,url,form){const response=await context.request.fetch(base+url,{method:form?'POST':'GET',form,headers:{Accept:'application/json'}});const body=await response.json();report.responses.push({url,status:response.status(),params:redact(form),body:redact(body)});save();return body;}
async function login(page,account,origin='/user/read_history.html'){
  await page.goto(base+'/user/login.html?originUrl='+encodeURIComponent(origin));await ready(page);
  await page.locator('#txtUName').fill(account.username);await page.locator('#txtPassword').fill(account.password);
  const response=page.waitForResponse(r=>r.url()===base+'/user/login');await page.locator('#btnLogin').click();assert.equal((await response).status(),200);assert.equal(Number(page.context().authResponses.get(page)),200);
  await page.waitForURL(base+origin);await ready(page);
}
async function expectIdentity(page,username){await page.waitForFunction(u=>document.querySelector('[data-header-auth]')?.textContent.includes(u==='19910710411'?'QA_AUTH_A':'QA_AUTH_B'),username);}
async function expectedHistory(page,context,account){await expectIdentity(page,account.username);const data=await request(context,'/user/listReadHistoryByPage?curr=1&limit=12');const expected=data.data.list.map(v=>String(v.bookId));await page.waitForFunction(ids=>JSON.stringify([...document.querySelectorAll('#shelf-results article')].map(e=>e.dataset.book))===JSON.stringify(ids),expected);return expected;}
async function context(browser,storageOnly=false){const ctx=await browser.newContext({viewport:{width:1440,height:1000}});ctx.authResponses=new WeakMap();if(storageOnly)await ctx.addInitScript(()=>{window.BroadcastChannel=undefined;});await ctx.route('**/book/addVisitCount',r=>r.abort());await ctx.route('https://hm.baidu.com/**',r=>r.abort());await ctx.route('**/user/login',async route=>{const response=await route.fetch();const body=await response.json();ctx.authResponses.set(route.request().frame().page(),body.code);report.responses.push({url:'/user/login',status:response.status(),body:redact(body),capture:'unchanged real-server response'});save();await route.fulfill({response});});ctx.on('page',page=>page.on('pageerror',error=>{report.pageErrors.push({url:page.url(),message:error.message});save();}));return ctx;}
let browser;
(async()=>{
  browser=await chromium.launch({headless:true,channel:'chrome'});report.browser=browser.version();
  const ctx=await context(browser),page=await ctx.newPage();
  for(const fields of [{},{username:accounts[0].username},{username:accounts[0].username,password:' '},{username:' ',password:'valid'}]){
    assert.equal(Number((await request(ctx,'/user/login',fields)).code),400);
  }
  record('04：缺失和空白登录字段返回参数错误');
  await login(page,accounts[0]);await expectedHistory(page,ctx,accounts[0]);record('正常登录与13条历史保持可用');
  const before=snapshot('invalid-before');
  const main={bookId:'2106695344219004928',chapterId:'2106695391920824320'};
  const foreign={bookId:'2106682855655419904',chapterId:main.chapterId};
  for(const fields of [{bookId:main.bookId},{bookId:'0',preContentId:main.chapterId},{bookId:main.bookId,preContentId:'-1'},
    {bookId:'9223372036854775808',preContentId:main.chapterId},{bookId:'1',preContentId:main.chapterId},
    {bookId:main.bookId,preContentId:'1'},{bookId:foreign.bookId,preContentId:foreign.chapterId}]){
    assert.equal(Number((await request(ctx,'/user/addReadHistory',fields)).code),400);
  }
  assert.deepEqual(snapshot('invalid-after'),before);record('02：无效、缺失、跨书章节被拒绝，历史及书架原记录未变');
  for(const query of ['curr=0&limit=12','curr=-1&limit=12','curr=1&limit=0','curr=1&limit=-1','curr=1&limit=101','curr=abc&limit=12','curr=2147483648&limit=12','curr=1&limit=abc'])assert.equal(Number((await request(ctx,'/user/listReadHistoryByPage?'+query)).code),400);
  const defaultPage=await request(ctx,'/user/listReadHistoryByPage');assert.equal(Number(defaultPage.data.pageSize),10);
  const maximum=await request(ctx,'/user/listReadHistoryByPage?curr=1&limit=100');assert.equal(Number(maximum.code),200);
  const beyond=await request(ctx,'/user/listReadHistoryByPage?curr=999&limit=12');assert.equal(beyond.data.list.length,0);
  record('05：非法分页返回参数错误，默认、上限及超出末页行为正常');
  const books=sql("select cast(b.id as char),cast(i.id as char) from book b join book_index i on i.book_id=b.id and i.index_num=0 where b.id in (select book_id from user_read_history where user_id=(select id from user where username='"+accounts[0].username+"') and pre_content_id is not null) and b.id<>"+main.bookId+" order by b.id limit 2;");assert.equal(books.length,2);
  let tie;
  for(let attempt=0;attempt<3;attempt++){
    await new Promise(resolve=>setTimeout(resolve,1000-Date.now()%1000+40));
    for(const [bookId,preContentId]of books)assert.equal(Number((await request(ctx,'/user/addReadHistory',{bookId,preContentId})).code),200);
    const state=snapshot('same-second');tie=state.history.filter(h=>h.username===accounts[0].username&&books.some(b=>b[0]===h.bookId));if(tie[0].created===tie[1].created)break;
  }
  assert.equal(tie[0].created,tie[1].created);const sorted=await request(ctx,'/user/listReadHistoryByPage?curr=1&limit=100');const ids=sorted.data.list.map(v=>v.bookId);assert.ok(ids.indexOf(books[1][0])<ids.indexOf(books[0][0]));
  await page.reload();await ready(page);await expectedHistory(page,ctx,accounts[0]);await shot(page,'same-second-order-fixed');record('03：真实同秒写入按最新记录排序',{rows:tie});
  await page.locator('#pagination button').filter({hasText:'下一页'}).click();await page.waitForFunction(()=>document.querySelectorAll('#shelf-results article').length===1);await shot(page,'history-page-two');
  await page.reload();await ready(page);await expectedHistory(page,ctx,accounts[0]);record('正常12加1分页及完整长ID可用');
  const other=await ctx.newPage(),reader=await ctx.newPage();
  await reader.goto(base+'/book/'+main.bookId+'/'+main.chapterId+'.html');await reader.waitForFunction(()=>window.novelGlobalPlayer&&window.novelReader?.chapter);
  await reader.evaluate(()=>{window.__authRegressionPlayer=window.novelGlobalPlayer;window.__authRegressionDocument=document;});
  await login(other,accounts[1]);await expectedHistory(page,ctx,accounts[1]);
  assert.equal(await reader.evaluate(()=>window.__authRegressionPlayer===window.novelGlobalPlayer&&window.__authRegressionDocument===document),true);
  await shot(page,'cross-tab-account-b');record('01：跨标签直接切换账号，旧历史与头部同步为B，阅读页和播放器实例保持');
  await other.locator('[data-header-auth] button').click();await page.waitForURL(/\/user\/login.html/);
  assert.equal(Number((await request(ctx,'/user/listReadHistoryByPage')).code),1001);record('跨标签退出清除私有页面，匿名接口仍拒绝访问');
  await login(other,accounts[0]);await page.waitForURL(base+'/user/read_history.html');await ready(page);await expectedHistory(page,ctx,accounts[0]);record('另一标签重新登录后，原历史标签恢复正确账号');
  // Hold an unchanged real A history response until B's view has loaded.
  let release,held;const holding=new Promise(resolve=>held=resolve),gate=new Promise(resolve=>release=resolve);let hold=true;
  await ctx.route('**/user/listReadHistoryByPage?**',async route=>{if(hold&&route.request().frame().page()===page){hold=false;const response=await route.fetch();held();await gate;await route.fulfill({response});}else await route.continue();});
  await page.locator('#pagination button').filter({hasText:'下一页'}).click();await holding;
  await login(other,accounts[1]);await expectedHistory(page,ctx,accounts[1]);release();await page.waitForTimeout(250);await expectedHistory(page,ctx,accounts[1]);await ctx.unroute('**/user/listReadHistoryByPage?**');record('01：真实旧账号历史延迟回包不会覆盖新账号视图');
  // Cookie change without any storage/BroadcastChannel notification is caught on focus.
  const aLogin=await request(ctx,'/user/login',{username:accounts[0].username,password:accounts[0].password});
  await ctx.addCookies([{name:'Authorization',value:aLogin.data.token,url:base}]);await page.evaluate(()=>window.dispatchEvent(new Event('focus')));await expectedHistory(page,ctx,accounts[0]);record('01：无通知的Cookie身份变化在焦点恢复时被识别');
  // Hold a real refresh response, switch accounts, then release the old token.
  let refreshRelease,refreshHeld;const refreshHolding=new Promise(resolve=>refreshHeld=resolve),refreshGate=new Promise(resolve=>refreshRelease=resolve);let holdRefresh=true;
  await ctx.route('**/user/refreshToken',async route=>{if(holdRefresh&&route.request().frame().page()===page){holdRefresh=false;const response=await route.fetch();refreshHeld();await refreshGate;await route.fulfill({response});}else await route.continue();});
  const navigation=page.reload();await refreshHolding;await login(other,accounts[1]);await expectedHistory(page,ctx,accounts[1]);refreshRelease();await navigation;await page.waitForTimeout(250);await expectedHistory(page,ctx,accounts[1]);assert.equal((await request(ctx,'/user/userInfo')).data.username,accounts[1].username);await ctx.unroute('**/user/refreshToken');record('01：真实旧账号刷新响应不能覆盖新账号会话');
  for(let i=0;i<2;i++)assert.equal(Number((await request(ctx,'/user/addReadHistory',{bookId:main.bookId,preContentId:main.chapterId,userId:'2107666245426266112'})).code),200);
  const saved=snapshot('valid-repeated');const row=saved.history.filter(h=>h.username===accounts[1].username&&h.bookId===main.bookId);assert.equal(row.length,1);assert.equal(row[0].chapterId,main.chapterId);assert.equal(row[0].chapterBookId,main.bookId);record('合法重复保存保持单条，忽略伪造userId，长ID精度正确');
  const concurrent=await Promise.all([0,1,2,0].map(()=>request(ctx,'/user/addReadHistory',{bookId:main.bookId,preContentId:main.chapterId})));assert.ok(concurrent.every(r=>Number(r.code)===200));assert.equal(snapshot('concurrent').history.filter(h=>h.username===accounts[1].username&&h.bookId===main.bookId).length,1);record('四个并发合法请求成功且同用户同书仍单条');
  await page.goto(base+'/user/read_history.html');await ready(page);await expectedHistory(page,ctx,accounts[1]);
  const resume=page.locator('#shelf-results article[data-book="'+main.bookId+'"] .button-primary');assert.equal(await resume.getAttribute('href'),'/book/'+main.bookId+'/'+main.chapterId+'.html');await resume.click();await page.waitForFunction(id=>window.novelReader?.chapter?.bookIndexId===id,main.chapterId);record('合法历史继续阅读到达正确章节');
  await ctx.close();
  const storage=await context(browser,true),old=await storage.newPage(),switcher=await storage.newPage();await login(old,accounts[0]);await login(switcher,accounts[1]);await expectedHistory(old,storage,accounts[1]);await shot(old,'storage-fallback-account-b');record('01：禁用BroadcastChannel后storage兼容方案正常');
  await old.setViewportSize({width:390,height:844});assert.ok(await old.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));await shot(old,'history-mobile-fixed');record('390px历史页面布局正常');
  await storage.close();assert.deepEqual(report.pageErrors,[]);record('业务页面未捕获JavaScript异常为0');
  snapshot('final');report.finished=new Date().toISOString();save();await browser.close();console.log(JSON.stringify({passed:report.checks.length,errors:report.pageErrors.length}));
})().catch(async error=>{report.failed={message:error.message,stack:error.stack};if(browser){report.diagnostics=[];for(const ctx of browser.contexts())for(const p of ctx.pages()){try{report.diagnostics.push(await p.evaluate(async()=>({url:location.href,ready:document.documentElement.dataset.ready,header:document.querySelector('[data-header-auth]')?.textContent,books:[...document.querySelectorAll('#shelf-results article')].map(e=>e.dataset.book),identity:(await import('/javascript/site/auth-state.js?v=20261007.4')).authSnapshot(),scripts:[...document.scripts].map(s=>s.src)})));}catch{}}}save();console.error(error.message);if(browser)await browser.close().catch(()=>{});process.exitCode=1;});
