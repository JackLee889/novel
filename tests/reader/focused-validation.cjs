const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const deps=process.env.CODEX_NODE_MODULES||path.join(process.env.USERPROFILE,'.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules');
const {chromium}=require(require.resolve('playwright',{paths:[deps,__dirname]}));
const base=process.env.READER_TEST_URL||'http://127.0.0.1:18983',book='9007199254740993',first='9007199254741001',paid='9007199254741005';
const out=path.resolve(__dirname,'../../doc/reader-evidence'),report={checks:[],errors:[],startedAt:new Date().toISOString()};
const url=id=>base+'/book/'+book+'/'+id+'.html';
function log(name,data={}){report.checks.push({name,...data});console.log(name,JSON.stringify(data));fs.writeFileSync(path.join(out,'focused-validation.json'),JSON.stringify(report,null,2));}
const ready=p=>p.waitForFunction(()=>window.novelReader);
const playing=p=>p.waitForFunction(()=>novelReader.state==='playing',null,{timeout:120000});
(async()=>{
const browser=await chromium.launch({headless:true,channel:'chrome'}),context=await browser.newContext({viewport:{width:1440,height:1000}});
const page=await context.newPage();page.on('pageerror',e=>report.errors.push(e.message));
try{
  for(const [id,token,status,code] of [[first,null,200,200],[paid,null,401,1001],[paid,'fixture-unpaid',403,5002],[paid,'fixture-purchased',200,200]]) {
    const response=await context.request.get(base+'/book/reader/chapter?'+new URLSearchParams({bookId:book,bookIndexId:id}),{headers:token?{Authorization:token}:{}});
    const data=await response.json();assert.equal(response.status(),status);assert.equal(data.code,code);
    if(code!==200)assert.deepEqual(data.data.paragraphs,[]);
  }
  log('最终 Java 接口错误码与购买边界');
  await page.goto(url(first));await ready(page);
  await page.evaluate(()=>{novelReader.changeSetting('autoNext',false);novelReader.changeSetting('theme','light');});
  await page.getByRole('button',{name:'开始朗读',exact:true}).click();await playing(page);
  await page.screenshot({path:path.join(out,'desktop-playing-final.png')});
  const start=await page.evaluate(()=>novelReader.player.context.currentTime);await page.waitForTimeout(900);
  assert.ok(await page.evaluate(t=>novelReader.player.context.currentTime>t+.7,start));log('最终页面真实 PCM 播放');
  await page.evaluate(()=>novelReader.stop());
  await page.getByRole('button',{name:'目录',exact:true}).first().click();
  await page.waitForFunction(()=>document.querySelectorAll('.rd-catalog button').length===100);
  assert.equal(await page.locator('.rd-catalog button').count(),100);
  await page.getByRole('button',{name:'下一页',exact:true}).click();await page.waitForFunction(()=>document.querySelectorAll('.rd-catalog button').length===25);
  await page.keyboard.press('Escape');log('目录分页最多100项与焦点返回');
  await page.route('**/book/reader/catalog?**',async route=>{await new Promise(r=>setTimeout(r,300));try{await route.continue();}catch{}});
  await page.getByRole('button',{name:'目录',exact:true}).first().click();
  await page.getByRole('button',{name:'关闭面板',exact:true}).click();
  await page.getByRole('button',{name:'设置',exact:true}).click();
  await page.waitForTimeout(600);
  assert.equal(await page.locator('.rd-catalog').count(),0);assert.equal(await page.locator('#panel-body select').count(),5);
  await page.keyboard.press('Escape');await page.unroute('**/book/reader/catalog?**');log('旧目录响应不会写入新的设置面板');
  await page.getByRole('button',{name:'保存本地书签',exact:true}).click();
  await page.getByRole('button',{name:'保存当前位置',exact:true}).click();
  assert.ok(await page.evaluate(()=>novelReader.storage.read('bookmark.anonymous.'+novelReader.chapter.bookId)));
  await page.getByRole('button',{name:'保存本地书签',exact:true}).click();
  await page.getByRole('button',{name:'打开已保存的书签',exact:true}).click();log('本地书签实际保存和打开');
  for(const width of [1440,390,320]) {
    await page.setViewportSize({width,height:width===1440?1000:844});
    for(const theme of ['light','dark']) {
      await page.evaluate(t=>novelReader.changeSetting('theme',t),theme);
      assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));
      await page.screenshot({path:path.join(out,'final-'+width+'-'+theme+'.png')});
    }
  }
  log('最终图标与320/390/1440主题截图');
  await page.setViewportSize({width:1440,height:1000});
  await page.locator('.rd-paragraph').last().click();
  assert.equal(await page.getByRole('button',{name:'从这里朗读',exact:true}).isVisible(),true);
  await page.locator('[data-chapter="next"]').click();
  await page.waitForFunction(()=>novelReader.chapter.bookIndexId==='9007199254741002');
  await page.locator('.rd-paragraph').last().click();
  await page.getByRole('button',{name:'从这里朗读',exact:true}).click();await playing(page);
  assert.equal(await page.evaluate(()=>novelReader.segments[novelReader.index].paragraphId),'p1');
  assert.equal(await page.locator('[data-paragraph="p1"].is-current').count(),1);
  await page.evaluate(()=>{novelReader.stop();novelReader.jump(0);});
  assert.equal(await page.locator('.rd-paragraph.is-current').count(),0);
  await page.locator('[data-chapter="previous"]').click();
  await page.waitForFunction(()=>novelReader.chapter.bookIndexId==='9007199254741001');
  log('段落操作跨章保留，实际发声才高亮');
  // Genuine missing WASM then missing model data, using the real Worker.
  for(const asset of ['sherpa-onnx-wasm-main-tts.wasm','sherpa-onnx-wasm-main-tts.data']) {
    await page.evaluate(()=>novelReader.engine.reset(new Error('故障探针')));
    await context.route('**/'+asset,r=>r.fulfill({status:404,body:'missing'}));
    await page.getByRole('button',{name:'开始朗读',exact:true}).click();
    await page.waitForFunction(()=>novelReader.state==='error',null,{timeout:120000});
    assert.equal(await page.evaluate(()=>!!novelReader.player.source),false);
    await context.unroute('**/'+asset);
    await page.getByRole('button',{name:'重试',exact:true}).click();await playing(page);
    await page.evaluate(()=>novelReader.stop());log(asset+' 404 后真实恢复');
  }
  // Kill the worker while required audio is synthesizing. Timeout must be real.
  await page.getByRole('button',{name:'开始朗读',exact:true}).click();
  await page.waitForFunction(()=>[...novelReader.engine.pending.values()].some(x=>x.kind==='synthesize')&&!novelReader.player.source);
  const killTime=Date.now();await page.evaluate(()=>novelReader.engine.worker.terminate());
  await page.waitForFunction(()=>novelReader.state==='error',null,{timeout:60000});
  const timeoutElapsed=Date.now()-killTime;
  assert.ok(timeoutElapsed>=44000 && timeoutElapsed<55000);
  assert.equal(await page.evaluate(()=>!!novelReader.player.source),false);
  await page.getByRole('button',{name:'重试',exact:true}).click();await playing(page);
  log('Worker 终止、真实45秒超时与重建',{timeoutElapsedMs:timeoutElapsed,recoveryElapsedMs:Date.now()-killTime});await page.evaluate(()=>novelReader.stop());
  // Verify chapter-end timer takes precedence over automatic next chapter.
  await page.evaluate(()=>{novelReader.changeSetting('autoNext',true);novelReader.sleep('chapter');novelReader.index=0;novelReader.offset=0;});
  await page.getByRole('button',{name:'开始朗读',exact:true}).click();await playing(page);
  await page.waitForFunction(()=>novelReader.state==='idle'&&!novelReader.player.source,null,{timeout:30000});
  assert.equal(await page.evaluate(()=>novelReader.chapter.bookIndexId),first);log('本章结束定时优先于自动下一章');
  await context.addCookies([{name:'Authorization',value:'fixture-purchased',url:base}]);
  await page.goto(url(paid));await ready(page);assert.ok((await page.locator('#reader-prose').textContent()).includes('付费夹具专属正文'));
  await page.getByRole('button',{name:'开始朗读',exact:true}).click();await playing(page);await page.evaluate(()=>novelReader.pause());
  await context.clearCookies();await page.getByRole('button',{name:'继续朗读',exact:true}).click();
  await page.waitForFunction(()=>novelReader.chapter.access==='need_login');
  assert.equal(await page.locator('#reader-prose').textContent(),'');
  assert.equal(await page.evaluate(()=>!!novelReader.player.source),false);assert.equal(await page.evaluate(()=>novelReader.scheduler.usage().count),0);
  assert.equal(await page.locator('#reader-data').textContent(),'');log('付费播放暂停后登录失效，正文与缓存清除');
  const unsupported=await browser.newContext();
  await unsupported.addInitScript(()=>Object.defineProperty(window,'Worker',{value:undefined}));
  const u=await unsupported.newPage();await u.goto(url(first));await ready(u);
  assert.equal(await u.getByRole('button',{name:'开始朗读',exact:true}).isDisabled(),true);
  assert.ok((await u.locator('#reader-prose').textContent()).includes('清晨'));await unsupported.close();log('不支持 Worker 时正文继续可读');
  const blocked=await browser.newContext();
  await blocked.addInitScript(()=>Object.defineProperty(window,'localStorage',{get(){throw Error('storage disabled');}}));
  const s=await blocked.newPage();await s.goto(url(first));await ready(s);
  assert.ok((await s.locator('#reader-prose').textContent()).includes('清晨'));
  await s.getByRole('button',{name:'保存本地书签',exact:true}).click();await s.getByRole('button',{name:'保存当前位置',exact:true}).click();
  assert.ok((await s.locator('#reader-notice').textContent()).includes('未保存'));await blocked.close();log('存储不可用仍可阅读，书签明确失败');
  assert.deepEqual(report.errors,[]);report.success=true;report.completedAt=new Date().toISOString();
}catch(error){report.failure=error.stack;console.error(error.stack);try{await page.screenshot({path:path.join(out,'focused-failure.png')});}catch{}process.exitCode=1;}
finally{fs.writeFileSync(path.join(out,'focused-validation.json'),JSON.stringify(report,null,2));await browser.close();}
})();
