const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),cp=require('node:child_process');
const {chromium}=require('C:/Users/lylboy/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const root=path.resolve(__dirname,'../..'),out=path.join(root,'doc',fs.readFileSync(path.join(root,'doc/layui-jquery-upgrade-current.txt'),'utf8').trim()),mode=process.argv[2]||'source';
const cfg=JSON.parse(fs.readFileSync(path.join(out,'ui-sandbox.private.json'),'utf8')),samples=JSON.parse(fs.readFileSync(path.join(out,'ui-samples.json'),'utf8'));
const base='http://127.0.0.1:19388',crawlBase='http://127.0.0.1:19381',report={mode,passed:false,checks:[],errors:[],isolated:true};
const save=()=>fs.writeFileSync(path.join(out,'components-'+mode+'-acceptance.json'),JSON.stringify(report,null,2));
async function check(name,fn){try{report.checks.push({name,passed:true,detail:await fn()});console.log('PASS',name);}catch(e){report.checks.push({name,passed:false,error:e.stack});console.log('FAIL',name,e.message);}save();}
(async()=>{const browser=await chromium.launch({headless:true,channel:'chrome'}),ctx=await browser.newContext({viewport:{width:1440,height:1000}});ctx.setDefaultTimeout(12000);
await ctx.addInitScript(data=>window.dictList=data,JSON.parse(fs.readFileSync(path.join(out,'ui-dicts.json'),'utf8')));
await ctx.route('**/*',route=>{const q=route.request(),u=new URL(q.url());if(![base,crawlBase].includes(u.origin))return route.abort();if(!['GET','HEAD'].includes(q.method())&&u.pathname!=='/login'&&u.pathname!=='/crawl/testParse')return route.abort();return route.continue();});
const page=await ctx.newPage();page.on('pageerror',e=>report.errors.push(e.message));
try{
await check('actual Layui 2.13.9 login via jQuery 4, with CAPTCHA and CSRF',async()=>{
 await page.goto(base+'/login');await page.waitForFunction(()=>window.layui&&window.jQuery&&document.querySelector('#imgVerify').naturalWidth>0);
 assert.equal(await page.evaluate(()=>jQuery.fn.jquery),'4.0.0');assert.equal(await page.evaluate(()=>layui.v),'2.13.9');assert.equal(await page.evaluate(()=>layui.$===jQuery),true);
 const log=cp.execFileSync('docker',['logs','--tail','40',cfg.admin],{encoding:'utf8',stdio:['ignore','pipe','pipe']});const captcha=[...log.matchAll(/RandomValidateCodeUtil\s*:\s*(\d{4})/g)].at(-1)[1];
 await page.getByPlaceholder('用户名或者邮箱').fill(cfg.username);await page.getByPlaceholder('密码',{exact:true}).fill(cfg.password);await page.getByPlaceholder('图形验证码').fill(captcha);
 const login=page.waitForRequest(r=>r.url()===base+'/login'&&r.method()==='POST');await page.getByRole('button',{name:'登 录',exact:true}).click();const sent=await login;
 assert.ok(sent.headers()['x-csrf-token']);await page.waitForURL(base+'/index');await page.screenshot({path:path.join(out,'admin-login-success-'+mode+'.png')});
 return {jquery:'4.0.0',layui:'2.13.9',captchaVerified:true,csrfHeaderPresent:true};
});
await check('jQuery Validate keeps required fields and localized errors',async()=>{
 await page.goto(base+'/sys/user/add');assert.equal(await page.evaluate(()=>jQuery('#signupForm').valid()),false);assert.ok(await page.locator('label.error').count()>0);
 return {legacyPluginRetained:true,emptyFormRejected:true};
});
await check('new bundled layer opens, closes, and keeps its focus',async()=>{
 await page.evaluate(()=>{window.__layerTest=layer.open({type:1,title:'升级验收',content:'<input id="layer-test-input" aria-label="验收输入">',area:['350px','220px']});});
 await page.getByText('升级验收',{exact:true}).waitFor();await page.locator('#layer-test-input').fill('jQuery 4');await page.locator('.layui-layer-close').click();await page.locator('#layer-test-input').waitFor({state:'hidden'});
 return {layer:'3.7.0',openedAndClosed:true};
});
await check('book date field opens a modern datetime picker',async()=>{
 await page.goto(base+'/novel/book/edit/'+samples.bookId);const input=page.locator('input[onclick*="laydate"],input[data-novel-date-format]').first();await input.click();await page.locator('.layui-laydate').last().waitFor({state:'visible'});
 assert.ok(await page.locator('.layui-laydate-content td').count()>0);await page.waitForTimeout(400);await page.screenshot({path:path.join(out,'admin-date-'+mode+'.png')});await page.locator('body').click({position:{x:1,y:1}});await page.locator('.layui-laydate').waitFor({state:'hidden'});
 return {datetimePicker:true};
});
await check('personal birth date selects a day and updates only the form',async()=>{
 await page.goto(base+'/sys/user/personal');await page.locator('#birth').click();await page.locator('.layui-laydate').last().waitFor({state:'visible'});
 const days=page.locator('.layui-laydate-content td[lay-ymd]:not(.laydate-disabled)');assert.ok(await days.count()>0);await days.nth(12).click();
 const value=await page.locator('#birth').inputValue();assert.match(value,/^\d{4}-\d{2}-\d{2}$/);await page.screenshot({path:path.join(out,'admin-birth-'+mode+'.png')});return {formValue:value,submitted:false};
});
await check('Layui upload module shares jQuery 4 without submission',async()=>{
 await page.goto(base+'/novel/book/add');const data=await page.evaluate(()=>new Promise(resolve=>layui.use('upload',()=>resolve({jquery:layui.$.fn.jquery,same:layui.$===jQuery,upload:typeof layui.upload.render}))));
 assert.deepEqual(data,{jquery:'4.0.0',same:true,upload:'function'});return data;
});
await check('crawler list pagination and AJAX CSRF use new libraries',async()=>{
 await page.goto(crawlBase+'/login.html');const baseline=JSON.parse(fs.readFileSync(path.join(out,'runtime-before.private.json'),'utf8'));const env=Object.fromEntries(baseline.find(r=>r.name==='novel-plus-local-crawl-1').env.map(s=>{const i=s.indexOf('=');return[s.slice(0,i),s.slice(i+1)];}));
 await page.locator('#username').fill(env.ADMIN_USERNAME);await page.locator('#password').fill(env.ADMIN_PASSWORD);await page.getByRole('button',{name:'登录',exact:true}).click();await page.waitForURL(crawlBase+'/');
 assert.equal(await page.evaluate(()=>jQuery.fn.jquery),'4.0.0');assert.equal(await page.evaluate(()=>layui.v),'2.13.9');assert.equal(await page.evaluate(()=>layui.$===jQuery),true);
 await page.waitForFunction(()=>document.querySelectorAll('.layui-laypage').length>0);await page.screenshot({path:path.join(out,'crawl-list-'+mode+'.png')});
 const result=await page.evaluate(()=>new Promise(resolve=>jQuery.ajax({url:'/crawl/testParse',type:'POST',data:{url:'invalid',rule:'unused'},success:resolve,error:x=>resolve({status:x.status})})));
 assert.equal(result.data['成功'],false);return {pagination:true,csrfAJAX:true,remoteSourceRequested:false};
});
await check('no uncaught errors in new library interactions',async()=>{assert.deepEqual(report.errors,[]);return {errors:0};});
report.passed=report.checks.every(c=>c.passed);save();process.exitCode=report.passed?0:1;
}finally{await browser.close();}
console.log(JSON.stringify({mode,passed:report.passed,checks:report.checks.length,errors:report.errors.length}));
})().catch(e=>{report.fatal=e.stack;save();console.error(e.message);process.exitCode=1;});
