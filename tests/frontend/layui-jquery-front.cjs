const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),cp=require('node:child_process');
const {chromium}=require('C:/Users/lylboy/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const root=path.resolve(__dirname,'../..'),out=path.join(root,'doc',fs.readFileSync(path.join(root,'doc/layui-jquery-upgrade-current.txt'),'utf8').trim()),mode=process.argv[2]||'built';
const samples=JSON.parse(fs.readFileSync(path.join(out,'ui-samples.json'),'utf8')),base=mode==='runtime'?'http://127.0.0.1:18987':'http://127.0.0.1:19387';
const report={mode,passed:false,pages:[],checks:[],errors:[],httpErrors:[]},save=()=>fs.writeFileSync(path.join(out,'front-'+mode+'-acceptance.json'),JSON.stringify(report,null,2));
if(mode==='built'){const cfg=JSON.parse(fs.readFileSync(path.join(out,'ui-sandbox.private.json'),'utf8'));report.frontJarSha256=cp.execFileSync('docker',['exec',cfg.front,'sha256sum','/app/app.jar'],{encoding:'utf8'}).split(/\s/)[0];assert.equal(report.frontJarSha256,JSON.parse(fs.readFileSync(path.join(out,'package-verification.json'),'utf8')).front.sha256);}
async function check(name,fn){try{report.checks.push({name,passed:true,detail:await fn()});console.log('PASS',name);}catch(e){report.checks.push({name,passed:false,error:e.stack});console.log('FAIL',name,e.message);}save();}
(async()=>{const browser=await chromium.launch({headless:true,channel:'chrome'});
try{for(const mobile of [false,true]){
const ctx=await browser.newContext(mobile?{viewport:{width:390,height:844},isMobile:true,hasTouch:true,userAgent:'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Version/18.0 Mobile/15E148 Safari/604.1'}:{viewport:{width:1440,height:1000}});
await ctx.route('**/*',route=>{const q=route.request(),u=new URL(q.url());if(u.origin!==base||!['GET','HEAD','OPTIONS'].includes(q.method())||/addVisitCount|addReadHistory/.test(u.pathname))return route.abort();return route.continue();});
const page=await ctx.newPage();page.on('pageerror',e=>report.errors.push({url:page.url(),error:e.message}));page.on('response',r=>{if(r.status()>=500)report.httpErrors.push({url:r.url(),status:r.status()});});
await check((mobile?'mobile':'desktop')+' active public routes render without overflow',async()=>{
const routes=['/','/book/bookclass.html','/book/bookclass.html?k='+encodeURIComponent('深空彼岸'),'/book/book_ranking.html','/user/login.html','/user/register.html','/about/news.html','/book/'+samples.bookId+'.html','/book/indexList-'+samples.bookId+'.html','/book/comment-'+samples.bookId+'.html','/book/'+samples.bookId+'/'+samples.indexId+'.html','/mobile/user/login.html'];
for(const route of routes){const r=await page.goto(base+route,{waitUntil:'domcontentloaded'});assert.equal(r.status(),200,route);await page.waitForTimeout(150);const overflow=await page.evaluate(()=>document.documentElement.scrollWidth-innerWidth);assert.ok(overflow<=2,route+' overflow '+overflow);const versions=await page.evaluate(()=>({jquery:window.jQuery?.fn.jquery,layui:window.layui?.v,same:!window.layui||!window.jQuery||layui.$===jQuery}));if(versions.jquery)assert.equal(versions.jquery,'4.0.0');if(versions.layui)assert.equal(versions.layui,'2.13.9');assert.ok(versions.same);report.pages.push({route,mobile,status:200,overflow,versions});save();}
await page.screenshot({path:path.join(out,'front-'+mode+'-'+(mobile?'mobile':'desktop')+'.png')});return{routes:routes.length};
});
await check((mobile?'mobile':'desktop')+' search, theme and password controls still respond',async()=>{
await page.goto(base+'/');await page.getByRole('button',{name:'切换夜间模式',exact:true}).click();assert.equal(await page.locator('html').getAttribute('data-theme'),'dark');
await page.locator('#searchKey').fill('深空彼岸');await page.getByRole('button',{name:'搜索',exact:true}).click();await page.waitForURL(/bookclass\.html\?k=/);assert.equal(new URL(page.url()).searchParams.get('k'),'深空彼岸');
await page.goto(base+'/user/login.html');await page.getByRole('button',{name:'显示密码',exact:true}).click();assert.equal(await page.locator('#txtPassword').getAttribute('type'),'text');
return{search:true,theme:true,passwordToggle:true};
});
await ctx.close();}
await check('jQuery and Layui canonical and legacy resources have final versions',async()=>{
 const ctx=await browser.newContext();await ctx.route('**/*',route=>{const q=route.request(),u=new URL(q.url());if(u.origin!==base||!['GET','HEAD'].includes(q.method()))return route.abort();return route.continue();});const page=await ctx.newPage();await page.goto(base+'/');
 await page.addScriptTag({url:base+'/vendor/jquery/jquery-4.0.0.min.js'});await page.addScriptTag({url:base+'/vendor/jquery/jquery-migrate-4.0.2.min.js'});await page.addScriptTag({url:base+'/vendor/jquery/jquery-legacy-compat.js'});await page.addScriptTag({url:base+'/vendor/layui/layui.js?v=2.13.9'});await page.addScriptTag({url:base+'/vendor/layui/layui-compat.js'});
 const result=await page.evaluate(()=>({jquery:jQuery.fn.jquery,layui:layui.v,same:layui.$===jQuery,migrate:jQuery.migrateVersion}));assert.deepEqual(result,{jquery:'4.0.0',layui:'2.13.9',same:true,migrate:'4.0.2'});await ctx.close();return result;
});
await check('no uncaught errors or server errors on active public pages',async()=>{assert.deepEqual(report.errors,[]);assert.deepEqual(report.httpErrors,[]);return{errors:0,server5xx:0};});
report.passed=report.checks.every(r=>r.passed);save();process.exitCode=report.passed?0:1;console.log(JSON.stringify({mode,passed:report.passed,checks:report.checks.length,pages:report.pages.length}));
}finally{await browser.close();}
})().catch(e=>{report.fatal=e.stack;save();console.error(e.message);process.exitCode=1;});
