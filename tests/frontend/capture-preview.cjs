const fs=require('node:fs'),path=require('node:path');
const {chromium}=require('C:/Users/lylboy/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const base=process.env.FRONTEND_BASE||'http://127.0.0.1:18984';
const cases=[['final-home-desktop','/',1440,1000,'light',false],['final-home-mobile','/',390,844,'light',false],
  ['final-author-desktop','/author/index.html',1440,1000,'light',true],['final-account-desktop','/user/userinfo.html',1440,1000,'light',true],
  ['final-detail-mobile','/book/9007199254740993.html',390,844,'light',false],
  ['final-author-night','/author/content_add.html?bookId=9007199254740993',1440,1000,'dark',true]];
(async()=>{const browser=await chromium.launch({headless:true,channel:'chrome'});try{for(const[name,route,width,height,theme,auth]of cases){
  const context=await browser.newContext({viewport:{width,height},userAgent:width<800?'Mozilla/5.0 iPhone Mobile':'Mozilla/5.0 Chrome/154.0.0.0 Safari/537.36'});
  await context.route('**/hm.baidu.com/**',r=>r.abort());
  if(auth)await context.addCookies([{name:'Authorization',value:'fixture-purchased',url:base}]);
  await context.addInitScript(t=>localStorage.setItem('novel.site.theme',t),theme);
  const p=await context.newPage();await p.goto(base+route);await p.waitForFunction(()=>document.documentElement.dataset.ready==='true');await p.evaluate(()=>document.fonts.ready);
  await p.screenshot({path:path.join('doc/frontend-evidence',name+'.png')});await context.close();console.log(name);
}}finally{await browser.close();}})().catch(e=>{console.error(e);process.exitCode=1;});
