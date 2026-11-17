// Verify real chapter-link clicks while the unpinned player is expanded near the page bottom.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {chromium}=require('C:/Users/lylboy/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const out=path.resolve(process.env.NOVEL_TEST_EVIDENCE_DIR),base=process.env.READER_TEST_URL||'http://127.0.0.1:18987';
assert.ok(out.startsWith(path.resolve('doc')+path.sep)&&fs.statSync(out).isDirectory());
assert.equal(new URL(base).hostname,'127.0.0.1');
const reproduce=process.argv.includes('--reproduce'),label=reproduce?'dock-navigation-before':'dock-navigation';
const report={base,started:new Date().toISOString(),checks:[],errors:[],blockedWrites:[],reproduce};
const save=()=>fs.writeFileSync(path.join(out,label+'-results.json'),JSON.stringify(report,null,2));
(async()=>{
 const browser=await chromium.launch({headless:true,channel:'chrome'});
 try{
  for(const width of reproduce?[1440]:[1440,390,320]){
   const context=await browser.newContext({viewport:{width,height:1000}});
   await context.route('**/*',route=>{
    const r=route.request(),url=new URL(r.url());
    if(url.origin!==base)return route.abort();
    if(!['GET','HEAD','OPTIONS'].includes(r.method())){report.blockedWrites.push(url.pathname);return route.abort();}
    return route.continue();
   });
   const page=await context.newPage();page.on('pageerror',e=>report.errors.push(e.message));
   await page.goto(base+'/book/2106695344609075200/2106695397037875200.html');
   await page.waitForFunction(()=>window.novelReader&&window.novelGlobalPlayer?.engine.clientId);
   const first=await page.evaluate(()=>({id:novelReader.chapter.bookIndexId,next:novelReader.chapter.nextChapterId}));
   await page.locator('.player-orb').click();
   await page.getByRole('button',{name:'固定播放器',exact:true}).focus();
   await page.locator('[data-chapter=next]').scrollIntoViewIfNeeded();
   await page.evaluate(()=>{
    window.__dockClicks=[];
    for(const type of ['pointerdown','pointerup','click'])document.addEventListener(type,e=>{
     window.__dockClicks.push({type,target:e.target.tagName,chapter:e.target.closest('[data-chapter]')?.dataset.chapter,
       x:e.clientX,y:e.clientY,expanded:novelGlobalPlayer.dock.expanded});
    },{capture:true});
   });
   await page.locator('[data-chapter=next]').click();
   let navigated=true;
   try{await page.waitForFunction(id=>novelReader.chapter.bookIndexId===id,first.next,{timeout:4000});}catch{navigated=false;}
   const state=await page.evaluate(()=>({id:novelReader.chapter.bookIndexId,expanded:novelGlobalPlayer.dock.expanded,
    engine:novelReader.engine.state,events:window.__dockClicks}));
   report.checks.push({width,first,navigated,...state});save();
   await page.screenshot({path:path.join(out,label+'-'+width+'.png')});
   if(reproduce){assert.equal(navigated,false,'The original pointer-down layout bug was not reproduced');}
   else{
    assert.equal(navigated,true,'Next chapter must receive the original click');assert.equal(state.expanded,false);
    await page.locator('.player-orb').click();
    await page.getByRole('button',{name:'固定播放器',exact:true}).focus();
    await page.locator('[data-chapter=previous]').click();
    await page.waitForFunction(id=>novelReader.chapter.bookIndexId===id,first.id,{timeout:5000});
    assert.equal(await page.evaluate(()=>novelGlobalPlayer.dock.expanded),false);
   }
   console.log(reproduce?'REPRODUCED':'PASS',width,'next and previous chapter clicks');
   await context.close();
  }
  assert.deepEqual(report.errors,[]);report.passed=true;
 }catch(error){report.passed=false;report.failure=error.stack;console.error(error.message);process.exitCode=1;}
 finally{report.completed=new Date().toISOString();save();await browser.close();}
})();
