const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const deps=process.env.CODEX_NODE_MODULES||path.join(process.env.USERPROFILE,'.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules');
const {chromium}=require(require.resolve('playwright',{paths:[deps,__dirname]}));
const base=process.env.READER_TEST_URL||'http://127.0.0.1:18983',book='9007199254740993',first='9007199254741001',paid='9007199254741005';
const out=path.resolve(__dirname,'../../doc/reader-evidence'),report={checks:[],errors:[],startedAt:new Date().toISOString()};
function log(name,data={}){report.checks.push({name,...data});console.log(name,JSON.stringify(data));fs.writeFileSync(path.join(out,'resilience-validation.json'),JSON.stringify(report,null,2));}
const playing=p=>p.waitForFunction(()=>novelReader.state==='playing',null,{timeout:120000});
const error=p=>p.waitForFunction(()=>novelReader.state==='error',null,{timeout:30000});
(async()=>{
const browser=await chromium.launch({headless:true,channel:'chrome'}),context=await browser.newContext(),page=await context.newPage();
page.on('pageerror',e=>report.errors.push(e.message));
try{
  await page.goto(base+'/book/'+book+'/'+first+'.html');await page.waitForFunction(()=>window.novelReader);
  await page.evaluate(()=>novelReader.changeSetting('autoNext',false));
  await context.setOffline(true);
  await page.getByRole('button',{name:'开始朗读',exact:true}).click();await error(page);
  assert.equal(await page.evaluate(()=>!!novelReader.engine.worker),false);
  assert.ok((await page.locator('#reader-prose').textContent()).includes('清晨'));
  await context.setOffline(false);await page.getByRole('button',{name:'重试',exact:true}).click();await playing(page);
  log('尚无模型缓存时断网：保留可读正文，联网后真实重试');
  await page.waitForTimeout(600);await page.evaluate(()=>novelReader.pause());
  const offset=await page.evaluate(()=>novelReader.player.position());
  await context.setOffline(true);await page.getByRole('button',{name:'继续朗读',exact:true}).click();await error(page);
  assert.equal(await page.evaluate(()=>!!novelReader.player.source),false);
  assert.ok(await page.evaluate(t=>novelReader.storage.progress(novelReader.chapter).offset>=t-.1,offset));
  await context.setOffline(false);await page.getByRole('button',{name:'重试',exact:true}).click();await playing(page);
  assert.equal(await page.evaluate(()=>novelReader.engine.initializations),1);
  log('引擎已缓存时断网：暂停续读仍需授权，错误保存真实进度，恢复复用引擎');
  await page.evaluate(()=>novelReader.stop());
  await page.route('**/reader/tts-manifest.json',r=>r.fulfill({status:200,contentType:'application/json',body:JSON.stringify({engineVersion:'invalid',modelId:'invalid',baseUrl:'/reader/tts/'})}));
  await page.evaluate(()=>novelReader.engine.reset(new Error('版本故障探针')));
  await page.getByRole('button',{name:'开始朗读',exact:true}).click();await error(page);
  assert.ok((await page.locator('#error-text').textContent()).includes('版本'));
  await page.unroute('**/reader/tts-manifest.json');await page.getByRole('button',{name:'重试',exact:true}).click();await playing(page);
  log('资源版本不匹配拒绝初始化，修复后真实恢复');
  await page.evaluate(async()=>{novelReader.stop();if(novelReader.scheduler.inflight)await novelReader.scheduler.inflight.catch(()=>{});novelReader.settings.speed=9;novelReader.scheduler.setSpeed(9);});
  await page.getByRole('button',{name:'开始朗读',exact:true}).click();await error(page);
  assert.ok((await page.locator('#error-text').textContent()).includes('参数无效'));
  await page.evaluate(()=>novelReader.changeSetting('speed',1));await page.getByRole('button',{name:'重试',exact:true}).click();await playing(page);
  log('真实 Worker 拒绝非法合成参数，恢复参数后重新发声');
  await page.evaluate(()=>{
    novelReader.stop();const saved=novelReader.storage.progress(novelReader.chapter);
    novelReader.storage.saveProgress(novelReader.chapter,{...saved,contentVersion:'old-version',segmentId:novelReader.segments[1].segmentId,offset:3});
    novelReader.offerProgress();
  });
  await page.getByRole('button',{name:'继续上次朗读',exact:true}).click();await playing(page);
  assert.equal(await page.evaluate(()=>novelReader.index),0);assert.equal(await page.evaluate(()=>novelReader.player.offset),0);
  assert.ok((await page.locator('#reader-notice').textContent()).includes('正文版本'));
  log('正文版本变化时手动恢复从章首真实播放');
  await page.evaluate(()=>novelReader.stop());
  await page.evaluate(()=>novelReader.navigate('9007199254741999'));await error(page);
  assert.equal(await page.evaluate(()=>novelReader.chapter.bookIndexId),first);
  assert.ok((await page.locator('#error-text').textContent()).includes('不存在'));
  await page.evaluate(()=>novelReader.navigate('9007199254741001'));
  log('缺章明确报错，原授权正文可读，可重新选章');
  await context.addCookies([{name:'Authorization',value:'fixture-purchased',url:base}]);
  await page.evaluate(()=>{novelReader.changeSetting('autoNext',true);novelReader.sleep('off');return novelReader.navigate('9007199254741005');});
  assert.equal(await page.evaluate(()=>novelReader.chapter.nextChapterId),null);
  await page.getByRole('button',{name:'开始朗读',exact:true}).click();await playing(page);
  await page.waitForFunction(()=>novelReader.state==='ended'&&!novelReader.player.source,null,{timeout:30000});
  assert.equal(await page.evaluate(()=>novelReader.chapter.bookIndexId),paid);
  log('已购末章真实播完，自动下一章开启时仍正常结束');
  assert.deepEqual(report.errors,[]);report.success=true;report.completedAt=new Date().toISOString();
}catch(e){report.failure=e.stack;console.error(e.stack);process.exitCode=1;}
finally{fs.writeFileSync(path.join(out,'resilience-validation.json'),JSON.stringify(report,null,2));await browser.close();}
})();
