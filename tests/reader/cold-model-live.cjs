// Three isolated browser sessions initialize real models together; collect the actual error text if any fail.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {chromium}=require('C:/Users/lylboy/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const out=path.resolve(process.env.NOVEL_TEST_EVIDENCE_DIR),base=process.env.READER_TEST_URL||'http://127.0.0.1:18987';
assert.ok(out.startsWith(path.resolve('doc')+path.sep)&&fs.statSync(out).isDirectory());assert.equal(new URL(base).hostname,'127.0.0.1');
const report={started:new Date().toISOString(),base,checks:[],pageErrors:[],httpErrors:[]};
const save=()=>fs.writeFileSync(path.join(out,'cold-model-results.json'),JSON.stringify(report,null,2));
(async()=>{
 const browser=await chromium.launch({headless:true,channel:'chrome'});
 try{
  const results=await Promise.allSettled([1,2,3].map(async session=>{
   const context=await browser.newContext({viewport:{width:1440,height:1000}});context.setDefaultTimeout(30000);
   try{
    await context.route('**/*',r=>new URL(r.request().url()).origin===base&&['GET','HEAD','OPTIONS'].includes(r.request().method())?r.continue():r.abort());
    const page=await context.newPage();page.on('pageerror',e=>report.pageErrors.push({session,error:e.message}));
    page.on('response',r=>{if(r.status()>=500)report.httpErrors.push({session,url:r.url(),status:r.status()});});
    await page.goto(base+'/book/2106695344219004928/2106695391920824320.html');
    await page.waitForFunction(()=>window.novelReader&&window.novelGlobalPlayer?.engine.clientId);
    await page.locator('.player-orb').click();await page.getByRole('button',{name:'固定播放器',exact:true}).click();
    await page.evaluate(()=>novelReader.changeSetting('autoNext',false));
    const started=Date.now();await page.locator('[data-action=play]').click();
    await page.waitForFunction(()=>['playing','error'].includes(novelReader.state),null,{timeout:150000});
    const state=await page.evaluate(()=>({state:novelReader.state,message:novelReader.message,shared:novelReader.engine.shared,
     engineId:novelReader.engine.engineId,initializations:novelReader.engine.initializations,resources:novelReader.engine.resourceStats,
     rate:novelReader.active?.sampleRate,nonzero:novelReader.active?.samples.some(x=>x!==0)}));
    report.checks.push({session,ms:Date.now()-started,...state});save();
    assert.equal(state.state,'playing',state.message);assert.equal(state.rate,16000);assert.equal(state.nonzero,true);
    assert.equal(state.initializations,1);assert.equal(state.resources.downloadedAssets,4);assert.equal(state.resources.downloadedBytes,163103220);
    console.log('PASS cold session',session,Date.now()-started,'ms');await page.evaluate(()=>novelReader.pause());
   }finally{await context.close();}
  }));
  for(const result of results)if(result.status==='rejected')throw result.reason;
  assert.deepEqual(report.pageErrors,[]);assert.deepEqual(report.httpErrors,[]);report.passed=true;
 }catch(error){report.passed=false;report.failure=error.stack;console.error(error.message);process.exitCode=1;}
 finally{report.completed=new Date().toISOString();save();await browser.close();}
})();
