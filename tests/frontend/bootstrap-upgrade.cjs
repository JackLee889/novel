// Exercise retained plugins and actual authenticated Thymeleaf pages in isolated services.
const fs=require('node:fs'), path=require('node:path'), assert=require('node:assert/strict'), cp=require('node:child_process');
const {chromium}=require('C:/Users/lylboy/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const root=path.resolve(__dirname,'../..'), run=fs.readFileSync(path.join(root,'doc/bootstrap-upgrade-current.txt'),'utf8').trim();
assert.match(run,/^bootstrap-upgrade-\d{8}-\d{6}$/);
const out=path.join(root,'doc',run), mode=process.argv[2]||'source';
assert.ok(['source','built'].includes(mode));
const config=JSON.parse(fs.readFileSync(path.join(out,'ui-sandbox.private.json'),'utf8')),
  samples=JSON.parse(fs.readFileSync(path.join(out,'ui-samples.json'),'utf8'));
const base='http://127.0.0.1:19388', component='http://127.0.0.1:19390', crawlBase='http://127.0.0.1:19281';
const report={mode,started:new Date().toISOString(),checks:[],pages:[],pageErrors:[],httpErrors:[],componentErrors:[],blockedWrites:[],isolatedDatabase:true};
if(mode==='built'){
  const packages=JSON.parse(fs.readFileSync(path.join(out,'package-verification.json'),'utf8'));
  report.adminJarSha256=cp.execFileSync('docker',['exec',config.admin,'sha256sum','/app/app.jar'],{encoding:'utf8'}).split(/\s/)[0];
  assert.equal(report.adminJarSha256,packages.admin.sha256);
}
const save=()=>fs.writeFileSync(path.join(out,'browser-'+mode+'-acceptance.json'),JSON.stringify(report,null,2));
async function check(name,fn){try{const detail=await fn();report.checks.push({name,passed:true,...detail});console.log('PASS',name);}catch(e){report.checks.push({name,passed:false,error:e.stack});console.log('FAIL',name,e.message);}save();}
function observe(page){page.on('pageerror',e=>report.pageErrors.push({path:new URL(page.url()).pathname,error:e.message}));page.on('console',e=>{if(e.type()==='error'&&e.text().startsWith('Bootstrap'))report.componentErrors.push(e.text());});page.on('response',r=>{if(r.status()>=500)report.httpErrors.push({url:r.url(),status:r.status()});});}
async function go(page,route){const response=await page.goto(base+route,{waitUntil:'domcontentloaded'});assert.equal(response.status(),200,route);await page.waitForTimeout(300);return response;}

(async()=>{
const browser=await chromium.launch({headless:true,channel:'chrome'});
const context=await browser.newContext({viewport:{width:1440,height:1000}}); context.setDefaultTimeout(10000);
// Direct-page layout probes receive the same dictionary cache as the real parent iframe shell.
const dicts=JSON.parse(fs.readFileSync(path.join(out,'ui-dicts.json'),'utf8'));
await context.addInitScript(data=>{window.dictList=data;},dicts);
await context.route('**/*',route=>{
  const request=route.request(),url=new URL(request.url());
  if(![base,component,crawlBase].includes(url.origin))return route.abort();
  if(!['GET','HEAD','OPTIONS'].includes(request.method())&&!(url.origin===crawlBase&&url.pathname==='/login')){report.blockedWrites.push({path:url.pathname,method:request.method()});return route.abort();}
  return route.continue();
});
const page=await context.newPage();observe(page);
try {
await check('actual isolated login and permissions',async()=>{
  const response=await context.request.get(base+'/login');assert.equal(response.status(),200);
  const html=await response.text(),token=html.match(/name="_csrf"\s+content="([^"]+)"/)[1];
  assert.equal((await context.request.get(base+'/getVerify')).status(),200);
  const log=cp.execFileSync('docker',['logs','--tail','25',config.admin],{encoding:'utf8',stdio:['ignore','pipe','pipe']});
  // Observe CAPTCHA only on this owned isolated test server; live auth is unchanged.
  const matches=[...log.matchAll(/RandomValidateCodeUtil\s*:\s*(\d{4})/g)]; assert.ok(matches.length);
  const login=await context.request.post(base+'/login',{headers:{'X-CSRF-TOKEN':token},form:{username:config.username,password:config.password,verify:matches.at(-1)[1]}});
  assert.equal(login.status(),200);assert.equal((await login.json()).code,0);
  await context.storageState({path:path.join(out,'ui-full-browser-auth.private.json')});
  return {account:'isolated acceptance account',serverAuth:true};
});
await check('Bootstrap 5 sidebar accordion and top dropdown',async()=>{
  await go(page,'/index');assert.equal(await page.evaluate(()=>bootstrap.Modal.VERSION),'5.3.8');
  const menus=page.locator('#side-menu > li:has(ul)');assert.ok(await menus.count()>=8);
  assert.equal(await page.locator('#side-menu ul.show').count(),0);
  await menus.nth(0).locator(':scope > a').click();await menus.nth(0).locator('ul').waitFor({state:'visible'});
  await page.waitForFunction(()=>document.querySelector('#side-menu >li:nth-child(2)>ul').classList.contains('in'));
  await menus.nth(1).locator(':scope > a').click();await menus.nth(0).locator('ul').waitFor({state:'hidden'});
  await page.waitForFunction(()=>document.querySelectorAll('#side-menu ul.show').length===1);
  await page.getByRole('button',{name:'关闭操作',exact:true}).click();await page.locator('.content-tabs .dropdown-menu.show').waitFor({state:'visible'});
  await page.keyboard.press('Escape');await page.locator('.content-tabs .dropdown-menu').waitFor({state:'hidden'});
  await page.screenshot({path:path.join(out,'admin-shell-'+mode+'.png')});
  return {bootstrap:'5.3.8',accordion:true,keyboardDismiss:true};
});
const routes=await page.locator('#side-menu .J_menuItem').evaluateAll(elements=>elements.map(e=>new URL(e.href).pathname));
await check('all active admin menu pages render',async()=>{
  assert.ok(routes.length>=20);
  for(const route of routes){await go(page,route);assert.ok(!page.url().endsWith('/login'),route);const version=await page.evaluate(()=>window.bootstrap&&bootstrap.Modal.VERSION);assert.equal(version,'5.3.8',route);report.pages.push({route,status:200,bootstrap:version});save();}
  return {pages:routes.length};
});
await check('admin forms and existing record edit pages render',async()=>{
  const forms=['/sys/user/add','/sys/user/edit/'+config.userId,'/sys/user/resetPwd/'+config.userId,
    '/sys/role/add','/sys/role/edit/1','/sys/menu/add/0','/system/sysDept/add/'+samples.deptId,
    '/novel/book/add','/novel/book/edit/'+samples.bookId,'/novel/book/detail/'+samples.bookId,
    '/novel/bookIndex/edit/'+samples.indexId,'/novel/bookContent/edit/'+samples.contentId,
    '/novel/category/add','/novel/news/add','/novel/friendLink/add','/novel/user/add','/novel/author/add','/novel/authorCode/add',
    '/novel/bookComment/add','/novel/bookSetting/add','/novel/userFeedback/add','/novel/pay/add'];
  for(const route of forms){await go(page,route);assert.equal(await page.evaluate(()=>bootstrap.Modal.VERSION),'5.3.8');assert.ok(await page.locator('form').count(),route);report.pages.push({route,status:200,form:true});save();}
  await go(page,'/novel/book/edit/'+samples.bookId);await page.screenshot({path:path.join(out,'admin-book-edit-'+mode+'.png'),fullPage:true});
  return {pages:forms.length};
});
await check('tree table icons and collapse use retained Font Awesome',async()=>{
  await go(page,'/sys/menu');await page.locator('.treegrid-expander.fa').first().waitFor();
  const first=page.locator('.treegrid-expander.fa').first();await first.click();assert.ok(await page.locator('.treegrid-collapsed').count()>0);
  await first.click();assert.equal(await page.locator('.treegrid-expander.glyphicon').count(),0);return {icons:'Font Awesome existing version'};
});
await check('personal tabs, distpicker, avatar cropper and rotation',async()=>{
  await go(page,'/sys/user/personal');assert.ok(await page.locator('[data-toggle="distpicker"] select option').count()>30);
  await page.getByRole('tab',{name:'头像修改'}).click();await page.locator('#photo_info').waitFor({state:'visible'});assert.ok(!await page.locator('#basic_info').isVisible());
  const buffer=await page.evaluate(()=>{const canvas=document.createElement('canvas');canvas.width=200;canvas.height=200;const c=canvas.getContext('2d');c.fillStyle='#139c7c';c.fillRect(0,0,200,200);return canvas.toDataURL().split(',')[1];});
  await page.locator('.avatar-input').setInputFiles({name:'bootstrap-crop-test.png',mimeType:'image/png',buffer:Buffer.from(buffer,'base64')});
  await page.locator('.cropper-container').waitFor({state:'visible'});
  await page.waitForFunction(()=>{const data=jQuery('.avatar-wrapper > img').data('cropper');return data&&data.built;});
  await page.getByRole('button',{name:'右旋转',exact:true}).click();
  const rotation=await page.evaluate(()=>jQuery('.avatar-wrapper > img').cropper('getData').rotate);assert.equal(rotation,90);
  await page.screenshot({path:path.join(out,'admin-avatar-'+mode+'.png')});
  await page.getByRole('tab',{name:'修改密码'}).click();await page.locator('#pwd_info').waitFor({state:'visible'});
  assert.ok(!await page.locator('#photo_info').isVisible());return {cropper:true,rotation:90,uploadSubmitted:false};
});
await check('iframe navigation and mobile sidebar',async()=>{
  await go(page,'/index');const novels=page.locator('#side-menu > li').filter({hasText:'小说管理'});await novels.locator(':scope > a').click();
  await page.locator('#side-menu .J_menuItem').filter({hasText:'小说列表'}).click();const frame=page.frameLocator('#content-main iframe:last-child');await frame.locator('#exampleTable').waitFor();
  await page.setViewportSize({width:390,height:844});await page.waitForFunction(()=>document.body.classList.contains('mini-navbar'));
  await page.locator('.navbar-minimalize').click();assert.ok(!await page.evaluate(()=>document.body.classList.contains('mini-navbar')));
  await page.screenshot({path:path.join(out,'admin-mobile-'+mode+'.png')});await page.setViewportSize({width:1440,height:1000});return {iframe:true,mobileWidth:390};
});

const fixture=await context.newPage();observe(fixture);
await check('retained component startup and early jQuery bridge',async()=>{
  await fixture.goto(component,{waitUntil:'domcontentloaded'});await fixture.waitForFunction(()=>window.componentReady);
  assert.equal(await fixture.evaluate(()=>bootstrap.Modal.VERSION),'5.3.8');assert.equal(await fixture.evaluate(()=>jQuery.fn.jquery),'2.1.4');
  await fixture.locator('#tip').hover();await fixture.getByRole('tooltip').waitFor();assert.match(await fixture.getByRole('tooltip').innerText(),/Tooltip OK/);
  await fixture.locator('h2').first().click();return {jquery:'2.1.4',earlyTooltip:true};
});
await check('v5 modal focus, Escape, backdrop cleanup and alert dismissal',async()=>{
  await fixture.locator('#modal-open').click();await fixture.locator('#test-modal').waitFor({state:'visible'});
  await fixture.locator('#modal-input').fill('Bootstrap 5');await fixture.waitForFunction(()=>!bootstrap.Modal.getInstance(document.querySelector('#test-modal'))._isTransitioning);
  await fixture.keyboard.press('Escape');await fixture.locator('#test-modal').waitFor({state:'hidden'});
  await fixture.locator('.modal-backdrop').waitFor({state:'hidden'});await fixture.locator('#test-alert .btn-close').click();assert.equal(await fixture.locator('#test-alert').count(),0);return {keyboard:true,backdropRemoved:true};
});
await check('Bootstrap Table columns dropdown stays open and dismisses outside',async()=>{
  const toggle=fixture.locator('.keep-open >button');await toggle.click();const menu=fixture.locator('.keep-open .dropdown-menu');await menu.waitFor({state:'visible'});
  await menu.getByLabel('状态',{exact:true}).uncheck();assert.ok(await menu.isVisible());assert.equal(await fixture.locator('#test-table th[data-field="status"]').count(),0);
  await fixture.locator('h2').first().click();await menu.waitFor({state:'hidden'});await toggle.click();await menu.getByLabel('状态',{exact:true}).check();await fixture.locator('h2').first().click();
  assert.equal(await fixture.locator('.fixed-table-toolbar .glyphicon').count(),0);return {columnsToggle:true,keepOpen:true};
});
await check('Bootstrap Table page size, sort, selection and card view',async()=>{
  const table=fixture.locator('.bootstrap-table');await table.locator('.page-next a').click();
  assert.equal(await fixture.evaluate(()=>jQuery('#test-table').bootstrapTable('getOptions').pageNumber),2);
  await table.locator('.pagination-detail .dropdown-toggle').click();await table.locator('.pagination-detail .dropdown-menu a').filter({hasText:/^20$/}).click();
  assert.equal(await fixture.evaluate(()=>jQuery('#test-table').bootstrapTable('getOptions').pageSize),20);
  await fixture.locator('#test-table th[data-field="id"]').click();assert.equal(await fixture.evaluate(()=>jQuery('#test-table').bootstrapTable('getOptions').sortName),'id');
  await fixture.locator('#test-table tbody input[type=checkbox]').first().check();assert.equal(await fixture.evaluate(()=>jQuery('#test-table').bootstrapTable('getSelections').length),1);
  await table.locator('button[name=toggle]').click();assert.ok(await fixture.locator('.card-view').count()>0);await table.locator('button[name=toggle]').click();
  return {pageSize:20,sort:true,selection:true,cardView:true};
});
await check('retained paginator and tree-grid',async()=>{
  await fixture.locator('#pager a').filter({hasText:/^2$/}).click();assert.equal(await fixture.evaluate(()=>window.pagerPage),2);
  assert.ok(await fixture.locator('#pager li.page-item a.page-link').count()>0);
  await fixture.locator('#tree .treegrid-expander.fa').first().waitFor();await fixture.locator('#tree .treegrid-expander.fa').first().click();
  assert.ok(!await fixture.getByText('子节点',{exact:true}).isVisible());return {paginator:true,treeGrid:true};
});
await check('retained Summernote menu and link modal',async()=>{
  await fixture.locator('.note-editable').fill('Bootstrap compatibility');await fixture.locator('.note-toolbar .note-style .dropdown-toggle').click();
  await fixture.locator('.note-style .dropdown-menu').waitFor({state:'visible'});await fixture.locator('h2').first().click();
  await fixture.locator('.note-style .dropdown-menu').waitFor({state:'hidden'});
  await fixture.locator('.note-editable').click();await fixture.getByRole('button',{name:'Link (CTRL+K)',exact:true}).click();const dialog=fixture.locator('.link-dialog.modal');await dialog.waitFor({state:'visible'});
  assert.ok(await dialog.locator('.btn-close').count()>0);await fixture.waitForFunction(()=>!bootstrap.Modal.getInstance(document.querySelector('.link-dialog.modal'))._isTransitioning);
  await dialog.locator('.btn-close').click();await dialog.waitFor({state:'hidden'});
  await fixture.locator('.modal-backdrop').waitFor({state:'hidden'});await fixture.screenshot({path:path.join(out,'components-'+mode+'.png'),fullPage:true});return {summernote:'0.8.8',modal:true};
});
await check('responsive form and component layout',async()=>{
  await go(page,'/sys/user/add');await page.setViewportSize({width:390,height:844});
  const overflow=await page.evaluate(()=>document.documentElement.scrollWidth-document.documentElement.clientWidth);assert.ok(overflow<=2,'form horizontal overflow '+overflow);
  await page.screenshot({path:path.join(out,'admin-form-mobile-'+mode+'.png'),fullPage:true});
  await fixture.setViewportSize({width:390,height:844});assert.ok(await fixture.evaluate(()=>document.documentElement.scrollWidth-document.documentElement.clientWidth)<=2);
  return {width:390,overflow};
});
if(mode==='built')await check('actual crawler login desktop/mobile and existing credentials',async()=>{
  const crawl=await context.newPage();observe(crawl);
  await crawl.goto(crawlBase+'/login.html',{waitUntil:'domcontentloaded'});
  assert.ok(await crawl.locator('button.w-100').isVisible());
  await crawl.screenshot({path:path.join(out,'crawl-login-desktop.png')});
  await crawl.setViewportSize({width:390,height:844});
  assert.ok(await crawl.evaluate(()=>document.documentElement.scrollWidth-document.documentElement.clientWidth)<=2);
  const widths=await crawl.evaluate(()=>({button:document.querySelector('button').getBoundingClientRect().width,input:document.querySelector('#username').getBoundingClientRect().width}));assert.equal(widths.button,widths.input);
  await crawl.screenshot({path:path.join(out,'crawl-login-mobile.png')});
  const baseline=JSON.parse(fs.readFileSync(path.join(out,'runtime-before.private.json'),'utf8'));
  const env=Object.fromEntries(baseline.find(row=>row.name==='novel-plus-local-crawl-1').env.map(item=>{const i=item.indexOf('=');return [item.slice(0,i),item.slice(i+1)];}));
  await crawl.locator('#username').fill(env.ADMIN_USERNAME);await crawl.locator('#password').fill(env.ADMIN_PASSWORD);
  await crawl.getByRole('button',{name:'登录',exact:true}).click();await crawl.waitForURL(crawlBase+'/');
  assert.ok(!crawl.url().includes('error'));return {mobileWidth:390,credentialsRetained:true,collectionStarted:false};
});
await check('no uncaught page errors, Bootstrap conflicts or server 5xx',async()=>{assert.deepEqual(report.pageErrors,[]);assert.deepEqual(report.httpErrors,[]);assert.deepEqual(report.componentErrors,[]);return {pageErrors:0,componentErrors:0,server5xx:0};});
report.passed=report.checks.every(row=>row.passed);report.finished=new Date().toISOString();save();
console.log(JSON.stringify({mode,passed:report.passed,checks:report.checks.length,pages:report.pages.length,pageErrors:report.pageErrors.length}));
process.exitCode=report.passed?0:1;
} finally {await browser.close();}
})().catch(e=>{report.fatal=e.stack;report.passed=false;save();console.error(e.message);process.exitCode=1;});
