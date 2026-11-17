/* Read real PCM and AudioContext playback through the owned agent-browser CDP connection. */
const path=require('node:path'),fs=require('node:fs'),assert=require('node:assert/strict');
const deps=path.join(process.env.USERPROFILE,'.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules');
const {chromium}=require(require.resolve('playwright',{paths:[deps,__dirname]}));
const phase=process.argv[3]||'candidate';
const base=phase==='runtime'?'http://127.0.0.1:18987':phase==='candidate'?'http://127.0.0.1:19287':'http://127.0.0.1:19387';
const out=path.join(__dirname,'../doc',fs.readFileSync(path.join(__dirname,'../doc/layui-jquery-upgrade-current.txt'),'utf8').trim());
const metrics=()=>({state:novelReader.state,engine:novelReader.engine.state,
  context:novelReader.player.context?.state,clock:novelReader.player.context?.currentTime,
  source:!!novelReader.player.source,pcm:window.__securityPcm,
  starts:novelReader.player.stats.starts,initializations:novelReader.engine.initializations,
  speechSynthesis:!!window.speechSynthesis?.speaking});
(async()=>{
  let browser;const report={phase,passed:false,frontJarSha256:phase==='source'?null:JSON.parse(fs.readFileSync(path.join(out,'package-verification.json'),'utf8')).front.sha256};
  try{
    let page;
    if(process.argv[2]==='launch'){
      console.log('Launching isolated reader browser');
      browser=await chromium.launch({headless:true,channel:'chrome'});
      const context=await browser.newContext({viewport:{width:1440,height:1000}});
      page=await context.newPage();
      await context.route('**/*',route=>{const q=route.request(),u=new URL(q.url());if(u.origin!==base||!['GET','HEAD','OPTIONS'].includes(q.method())||/addVisitCount|addReadHistory/.test(u.pathname))return route.abort();return route.continue();});
      const sample=JSON.parse(fs.readFileSync(path.join(out,'ui-samples.json'),'utf8'));
      const free={bookId:sample.bookId,indexId:sample.indexId};
      await page.goto(base+'/book/'+free.bookId+'/'+free.indexId+'.html');
      await page.waitForFunction(()=>window.novelReader,null,{timeout:25000});
      console.log('Real chapter loaded');
      await page.evaluate(fs.readFileSync(path.join(out,'reader-pcm-hook.js'),'utf8'));
      await page.getByRole('button',{name:/^展开听书播放器/}).click();
      await page.getByRole('button',{name:'固定播放器',exact:true}).click();
      await page.getByRole('button',{name:'开始朗读',exact:true}).click();
      console.log('Real speech synthesis requested');
      report.browser=browser.version();
    }else{
      browser=await chromium.connectOverCDP(process.argv[2]);
      page=browser.contexts().flatMap(context=>context.pages()).find(page=>page.url().startsWith(base+'/book/'));
    }
    assert.ok(page,'Owned reader page must already be open');
    page.setDefaultTimeout(25000);
    await Promise.race([page.waitForFunction(()=>window.__securityPcm?.length>0&&novelReader.state==='playing'),
      new Promise((_,reject)=>{const timer=setTimeout(()=>reject(new Error('PCM readiness timed out')),55000);timer.unref();})]);
    const before=await page.evaluate(metrics);
    await page.waitForTimeout(1500);
    const after=await page.evaluate(metrics);
    assert.equal(after.context,'running');assert.ok(after.clock-before.clock>0.8);
    assert.ok(after.source);assert.equal(after.engine,'ready');
    assert.ok(after.pcm.some(audio=>audio.samples>0&&audio.sampleRate===16000&&audio.rms>0.001));
    assert.equal(after.speechSynthesis,false);
    await page.screenshot({path:path.join(out,phase+'-reader.png')});
    await page.evaluate(()=>novelReader.pause());
    const paused=await page.evaluate(metrics);await page.waitForTimeout(700);
    const still=await page.evaluate(metrics);
    assert.equal(still.context,'suspended');assert.ok(Math.abs(still.clock-paused.clock)<0.06);
    await page.evaluate(()=>novelReader.stop());
    const next=await page.evaluate(()=>novelReader.chapter.nextChapterId);
    assert.ok(next);await page.evaluate(id=>novelReader.navigate(id,true,false),next);
    const crossed=await page.evaluate(()=>({chapter:novelReader.chapter.bookIndexId,access:novelReader.chapter.access,initializations:novelReader.engine.initializations,overflow:document.documentElement.scrollWidth-innerWidth}));
    assert.equal(crossed.chapter,next);assert.equal(crossed.access,'allowed');assert.equal(crossed.initializations,1);
    await page.setViewportSize({width:390,height:844});assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));
    await page.screenshot({path:path.join(out,phase+'-reader-mobile.png')});
    report.crossChapter=crossed;report.mobileWidth=390;
    report.passed=true;report.playback=after;report.clockAdvanced=after.clock-before.clock;
    report.pauseVerified=true;
    await page.evaluate(()=>novelReader.stop());
  }catch(error){report.error=error.message;process.exitCode=1;}
  finally{
    fs.writeFileSync(path.join(out,phase+'-reader-pcm.json'),JSON.stringify(report,null,2));
    console.log(JSON.stringify(report));
    if(browser)await browser.close();
  }
})();
