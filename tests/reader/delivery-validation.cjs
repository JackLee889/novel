const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const deps=process.env.CODEX_NODE_MODULES||path.join(process.env.USERPROFILE,'.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules');
const {chromium}=require(require.resolve('playwright',{paths:[deps,__dirname]}));
const base=process.env.READER_TEST_URL||'http://127.0.0.1:18983',route='/book/9007199254740993/9007199254741001.html';
const out=path.resolve(__dirname,'../../doc/reader-evidence');
(async()=>{
 const browser=await chromium.launch({headless:true,channel:'chrome'}),context=await browser.newContext(),page=await context.newPage();
 const report={startedAt:new Date().toISOString(),errors:[],viewports:[]},requests=[];
 page.on('pageerror',e=>report.errors.push(e.message));page.on('request',r=>requests.push(r.url()));
 try{
  await page.goto(base+route);await page.waitForFunction(()=>window.novelReader);
  assert.equal(await page.locator('link[rel="canonical"]').getAttribute('href'),base+route);
  assert.equal(await page.locator('.rd-book').getAttribute('aria-label'),'雨港手记，打开目录');
  for(const width of [1440,390,320]){
   await page.setViewportSize({width,height:width===1440?1000:844});
   for(const theme of ['light','dark']){
    await page.evaluate(t=>novelReader.changeSetting('theme',t),theme);
    const dimensions=await page.evaluate(()=>({page:document.documentElement.scrollWidth,viewport:innerWidth,
     targets:[...document.querySelectorAll('button,#segment-progress,.rd-brand,.rd-book-page,.rd-chapter-nav a,.rd-business a,.rd-paragraph')]
       .filter(e=>e.getBoundingClientRect().width&&e.getBoundingClientRect().height).map(e=>({name:e.getAttribute('aria-label')||e.textContent.trim().slice(0,30),height:e.getBoundingClientRect().height}))}));
    assert.ok(dimensions.page<=dimensions.viewport+1);
    assert.deepEqual(dimensions.targets.filter(x=>x.height<43.9),[]);
    await page.screenshot({path:path.join(out,'final-'+width+'-'+theme+'.png')});
    report.viewports.push({width,theme,...dimensions});
   }
  }
  assert.equal(requests.some(url=>url.includes('/reader/tts/')),false);
  assert.equal(await page.evaluate(()=>novelReader.engine.state),'idle');
  const unknown=await context.request.get(base+'/book/reader/catalog?bookId=9007199254741999');
  assert.equal(unknown.status(),404);assert.equal((await unknown.json()).code,5003);
  assert.deepEqual(report.errors,[]);report.success=true;report.completedAt=new Date().toISOString();
 }catch(e){report.failure=e.stack;console.error(e.stack);process.exitCode=1;}
 finally{fs.writeFileSync(path.join(out,'delivery-validation.json'),JSON.stringify(report,null,2));await browser.close();}
 console.log(JSON.stringify({success:report.success,viewports:report.viewports.length,errors:report.errors}));
})();
