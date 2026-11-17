/* Real browser inference and AudioContext playback; no synthesized PCM mocks. */
const path=require('node:path'),fs=require('node:fs'),assert=require('node:assert/strict');
const deps=process.env.CODEX_NODE_MODULES || path.join(process.env.USERPROFILE,'.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules');
const {chromium}=require(require.resolve('playwright',{paths:[deps,__dirname]}));
const base=process.env.READER_TEST_URL||'http://127.0.0.1:18983';
const root=path.resolve(__dirname,'../..'),out=path.join(root,'doc/reader-evidence');
fs.mkdirSync(out,{recursive:true});
const book='9007199254740993',ids=['9007199254741001','9007199254741002','9007199254741003','9007199254741004','9007199254741005'];
const url=id=>base+'/book/'+book+'/'+id+'.html';
const report={startedAt:new Date().toISOString(),checks:[],errors:[],soak:[]};
function record(name,data={}) {report.checks.push({name,...data});fs.writeFileSync(path.join(out,'browser-validation.json'),JSON.stringify(report,null,2));console.log(name,JSON.stringify(data));}
async function metrics(page) {
  return page.evaluate(()=> {
    const r=novelReader;
    return {state:r.state,id:r.chapter.bookIndexId,index:r.index,offset:r.player.position(),usage:r.scheduler.usage(),
      stats:r.scheduler.stats,player:r.player.stats,engine:r.engine.state,initializations:r.engine.initializations,
      context:r.player.context?.state,clock:r.player.context?.currentTime,source:!!r.player.source,
      heap:performance.memory?.usedJSHeapSize,events:r.events.slice(),segments:r.segments.length};
  });
}
const waitPlaying=page=>page.waitForFunction(()=>novelReader.state==='playing',null,{timeout:120000});
async function captureWav(page,n,file) {
  const data=await page.evaluate(index=>{
    const a=window.__audioSamples[index],s=a.samples,buffer=new ArrayBuffer(44+s.length*2),v=new DataView(buffer);
    const txt=(o,t)=>{for(let i=0;i<t.length;i++)v.setUint8(o+i,t.charCodeAt(i));};
    txt(0,'RIFF');v.setUint32(4,36+s.length*2,true);txt(8,'WAVE');txt(12,'fmt ');v.setUint32(16,16,true);
    v.setUint16(20,1,true);v.setUint16(22,1,true);v.setUint32(24,a.sampleRate,true);v.setUint32(28,a.sampleRate*2,true);
    v.setUint16(32,2,true);v.setUint16(34,16,true);txt(36,'data');v.setUint32(40,s.length*2,true);
    let sum=0;
    for(let i=0;i<s.length;i++){sum+=s[i]*s[i];v.setInt16(44+i*2,Math.max(-32768,Math.min(32767,Math.round(s[i]*32767))),true);}
    const bytes=new Uint8Array(buffer);let binary='';
    for(let i=0;i<bytes.length;i+=8192)binary+=String.fromCharCode(...bytes.subarray(i,i+8192));
    return {base64:btoa(binary),sampleRate:a.sampleRate,length:s.length,duration:s.length/a.sampleRate,rms:Math.sqrt(sum/s.length),text:a.text};
  },n);
  assert.ok(data.length>0&&data.rms>0.001);assert.equal(data.sampleRate,16000);
  fs.writeFileSync(path.join(out,file),Buffer.from(data.base64,'base64'));delete data.base64;
  record('真实 WAV '+file,data);
}
(async()=>{
  const browser=await chromium.launch({headless:true,channel:process.env.READER_BROWSER_CHANNEL||'chrome'});
  report.browser=browser.version();
  const context=await browser.newContext({viewport:{width:1440,height:1000},deviceScaleFactor:1});
  const page=await context.newPage();
  page.on('pageerror',e=>report.errors.push(e.message));
  const requests=[];page.on('request',r=>requests.push({url:r.url(),type:r.resourceType()}));
  try {
    for (const [token,id,status,access] of [[null,ids[0],200,'allowed'],[null,ids[4],401,'need_login'],
      ['fixture-unpaid',ids[4],403,'need_purchase'],['fixture-purchased',ids[4],200,'allowed'],
      ['expired',ids[4],401,'need_login']]) {
      const response=await context.request.get(base+'/book/reader/chapter?'+new URLSearchParams({bookId:book,bookIndexId:id}),
        {headers:token?{Authorization:token}:{}});
      assert.equal(response.status(),status);assert.equal(response.headers()['cache-control'],'private, no-store');
      const body=await response.json();assert.equal(body.data.access,access);
      assert.equal(typeof body.data.bookIndexId,'string');
      if(access!=='allowed'){assert.deepEqual(body.data.paragraphs,[]);assert.equal(body.data.contentVersion,null);assert.ok(!JSON.stringify(body).includes('付费夹具专属正文'));}
    }
    for(const [bid,id] of [['9007199254740994',ids[0]],[book,'9007199254741999']]) {
      const response=await context.request.get(base+'/book/reader/chapter?'+new URLSearchParams({bookId:bid,bookIndexId:id}));assert.equal(response.status(),404);
    }
    const catalog=await(await context.request.get(base+'/book/reader/catalog?bookId='+book+'&page=1')).json();
    assert.equal(catalog.data.entries.length,100);assert.equal(catalog.data.hasMore,true);
    const wasm=await context.request.head(base+'/reader/tts/1.13.8-matcha-icefall-zh-en/sherpa-onnx-wasm-main-tts.wasm');
    assert.equal(wasm.status(),200);assert.match(wasm.headers()['content-type'],/application\/wasm/);
    assert.match(wasm.headers()['cache-control'],/immutable/);
    record('章节授权、字符串 ID、归属与目录分页、WASM MIME');
    await page.goto(url(ids[0]));await page.waitForFunction(()=>window.novelReader);
    assert.ok(!requests.some(r=>r.url.includes('/reader/tts/')));
    assert.equal(await page.evaluate(()=>!!window.speechSynthesis?.speaking),false);
    await page.evaluate(()=>{
      window.__audioSamples=[];const r=novelReader,play=r.player.play.bind(r.player);
      r.player.play=(audio,offset,ended)=>{
        if(window.__audioSamples.length<2) window.__audioSamples.push({samples:audio.samples.slice(),sampleRate:audio.sampleRate,text:r.segments[r.index].text});
        return play(audio,offset,ended);
      };
    });
    const cold=Date.now();await page.getByRole('button',{name:'开始朗读',exact:true}).click();await waitPlaying(page);
    record('冷加载首段实际播放',{ms:Date.now()-cold,crossOriginIsolated:await page.evaluate(()=>crossOriginIsolated)});
    await page.screenshot({path:path.join(out,'desktop-playing.png')});
    const clock=(await metrics(page)).clock;await page.waitForTimeout(1200);
    assert.ok((await metrics(page)).clock>clock+0.8);
    await page.waitForFunction(()=>novelReader.chapter.bookIndexId==='9007199254741004' && novelReader.state==='playing',null,{timeout:100000});
    const three=await metrics(page);
    for(const id of ids.slice(0,3)) assert.equal(three.events.filter(e=>e.type==='ended'&&e.chapterId===id).length,2);
    assert.equal(three.initializations,1);
    assert.equal(requests.filter(r=>r.type==='document').length,1);
    record('三个完整章节连续朗读并局部进入第四章',three);
    await captureWav(page,0,'reader-chinese.wav');await captureWav(page,1,'reader-mixed.wav');
    await page.evaluate(()=>novelReader.stop());
    const stopped=await metrics(page);await page.waitForTimeout(3000);
    const afterStop=await metrics(page);assert.equal(afterStop.player.starts,stopped.player.starts);assert.equal(afterStop.source,false);
    assert.equal(afterStop.usage.count,0);record('停止后晚到音频不能再发声',{discarded:afterStop.stats.discarded});
    await page.evaluate(()=>{novelReader.settings.autoNext=false;novelReader.changeSetting('follow',false);});
    await page.evaluate(id=>novelReader.navigate(id),ids[0]);
    const hotStart=Date.now();
    await page.getByRole('button',{name:'开始朗读',exact:true}).click();await waitPlaying(page);
    record('热引擎重新生成首段实际播放',{ms:Date.now()-hotStart});
    await page.waitForTimeout(800);await page.evaluate(()=>novelReader.pause());
    const paused=await metrics(page);await page.waitForTimeout(700);
    assert.ok(Math.abs((await metrics(page)).clock-paused.clock)<0.06);
    await page.getByRole('button',{name:'继续朗读',exact:true}).click();await waitPlaying(page);
    assert.equal((await metrics(page)).player.starts,paused.player.starts);
    record('真实音频暂停和原节点继续',{offset:paused.offset,clock:paused.clock});
    await page.evaluate(()=>novelReader.changeSetting('speed',1.5));
    assert.equal(await page.evaluate(()=>novelReader.active.speed),1);
    await page.waitForFunction(()=>novelReader.active?.speed===1.5,null,{timeout:30000});
    record('语速在下一片段生效');
    await page.evaluate(()=>novelReader.jump(0,true));await waitPlaying(page);
    const marker=await page.evaluate(()=>performance.now());
    await page.route('**/book/reader/chapter?**',async route=>{
      if(new URL(route.request().url()).searchParams.get('bookIndexId')===ids[1])await new Promise(r=>setTimeout(r,500));
      try{await route.continue();}catch{}
    });
    await page.evaluate(id=>{void novelReader.navigate(id);},ids[1]);
    await page.waitForTimeout(50);await page.evaluate(id=>{void novelReader.navigate(id);},ids[2]);
    await page.waitForFunction(()=>novelReader.chapter.bookIndexId==='9007199254741003'&&novelReader.state==='playing',null,{timeout:30000});
    await page.waitForTimeout(800);await page.unroute('**/book/reader/chapter?**');
    const raced=await metrics(page);
    assert.ok(!raced.events.some(e=>e.type==='started'&&e.at>marker&&e.chapterId===ids[1]));
    assert.equal(new URL(page.url()).pathname,'/book/'+book+'/'+ids[2]+'.html');
    record('快速跳章和乱序响应无旧音频',raced);
    await page.evaluate(()=>novelReader.pause());
    await page.evaluate(id=>novelReader.navigate(id),ids[1]);assert.equal((await metrics(page)).state,'paused');
    await page.goBack();await page.waitForFunction(()=>novelReader.chapter.bookIndexId==='9007199254741003');
    assert.equal((await metrics(page)).state,'paused');record('暂停翻章与浏览器后退保持暂停');
    await page.getByRole('button',{name:'继续朗读',exact:true}).click();await waitPlaying(page);
    await page.waitForTimeout(700);await page.evaluate(()=>novelReader.stop());
    const saved=await page.evaluate(()=>novelReader.storage.progress(novelReader.chapter));
    await page.reload();await page.waitForFunction(()=>window.novelReader);
    assert.equal((await metrics(page)).source,false);assert.equal((await metrics(page)).engine,'idle');
    await page.getByRole('button',{name:'继续上次朗读',exact:true}).click();await waitPlaying(page);
    const restored=await metrics(page);assert.ok(restored.offset>=Math.min(saved.offset,restored.player.duration||Infinity)-.2);
    record('刷新保持静音，手动恢复本地进度',{saved,restoredOffset:restored.offset});
    await page.evaluate(()=>{novelReader.sleep('15');novelReader.deadline=Date.now()+600;});
    await page.waitForFunction(()=>novelReader.state==='idle'&&!novelReader.player.source);
    record('实际截止时间触发停止',{shortenedDeadlineForTest:true});
    await page.evaluate(()=>{novelReader.changeSetting('speed',1);novelReader.changeSetting('follow',false);novelReader.changeSetting('autoNext',false);});
    await page.evaluate(id=>novelReader.navigate(id),ids[3]);
    await page.getByRole('button',{name:'开始朗读',exact:true}).click();await waitPlaying(page);
    const soakStart=Date.now(),soakClock=(await metrics(page)).clock;
    while(Date.now()-soakStart<305000) {
      await page.waitForTimeout(10000);const m=await metrics(page);
      assert.ok(['playing','buffering'].includes(m.state));
      assert.ok(m.usage.count<=2&&m.usage.seconds<=30&&m.usage.bytes<=16*1024*1024);
      report.soak.push({wallSeconds:(Date.now()-soakStart)/1000,...m,events:undefined});
      if(report.soak.length%3===0)console.log('真实播放队列观测',JSON.stringify(report.soak.at(-1)));
      fs.writeFileSync(path.join(out,'browser-validation.json'),JSON.stringify(report,null,2));
    }
    const soaked=await metrics(page);assert.ok(soaked.clock-soakClock>=300);assert.ok(soaked.stats.generated>=10);
    const chapterEvents=soaked.events.filter(e=>e.chapterId===ids[3]),gaps=[];
    for(let i=1;i<chapterEvents.length;i++) {
      if(chapterEvents[i].type==='started'&&chapterEvents[i-1].type==='ended')
        gaps.push(chapterEvents[i].at-chapterEvents[i-1].at);
    }
    record('五分钟真实循环合成播放队列有界',{wallSeconds:(Date.now()-soakStart)/1000,audioClockSeconds:soaked.clock-soakClock,
      segmentGapsMs:gaps,gapsOver100Ms:gaps.filter(x=>x>100).length,...soaked});
    await page.evaluate(()=>novelReader.stop());
    await page.evaluate(id=>novelReader.navigate(id),ids[4]);
    assert.equal(await page.locator('#reader-prose').textContent(),'');assert.equal((await metrics(page)).source,false);
    record('付费章节边界停止且无正文');
    await page.evaluate(id=>novelReader.navigate(id),ids[0]);
    await page.getByRole('button',{name:'设置',exact:true}).click();await page.selectOption('#setting-theme','dark');
    await page.screenshot({path:path.join(out,'desktop-dark.png')});
    await page.keyboard.press('Escape');assert.equal(await page.evaluate(()=>document.activeElement.dataset.action),'reading');
    for(const width of [390,320]) {
      await page.setViewportSize({width,height:844});
      for(const theme of ['light','dark']) {
        await page.evaluate(t=>novelReader.changeSetting('theme',t),theme);
        const sizes=await page.evaluate(()=>({w:innerWidth,document:document.documentElement.scrollWidth,player:document.querySelector('.rd-player').getBoundingClientRect().toJSON()}));
        assert.ok(sizes.document<=sizes.w+1);assert.ok(sizes.player.right<=sizes.w);
        await page.screenshot({path:path.join(out,'mobile-'+width+'-'+theme+'.png')});
      }
    }
    record('320/390 px 与日夜主题、模态焦点');
    // Real initialization failure and real retry, with browser routing applied to Worker requests.
    await page.setViewportSize({width:1440,height:1000});
    await page.evaluate(()=>novelReader.engine.reset(new Error('资源故障验收')));
    await context.route('**/sherpa-onnx-wasm-main-tts.wasm',route=>route.fulfill({status:404,body:'missing'}));
    await page.getByRole('button',{name:'开始朗读',exact:true}).click();
    await page.waitForFunction(()=>novelReader.state==='error',null,{timeout:120000});
    assert.equal((await metrics(page)).source,false);
    await context.unroute('**/sherpa-onnx-wasm-main-tts.wasm');
    await page.getByRole('button',{name:'重试',exact:true}).click();await waitPlaying(page);
    record('WASM 404 初始化失败后真实重试合成播放');
    await page.evaluate(()=>novelReader.stop());
    assert.deepEqual(report.errors,[]);
    record('前台无 JavaScript 未处理异常');
    report.completedAt=new Date().toISOString();report.success=true;
  } catch(error) {
    report.failure=error.stack;console.error(error.stack);
    try{report.last=await metrics(page);await page.screenshot({path:path.join(out,'failure.png')});}catch{}
    process.exitCode=1;
  } finally {
    fs.writeFileSync(path.join(out,'browser-validation.json'),JSON.stringify(report,null,2));await browser.close();
  }
})();
