// Bounded real Cloudflare acceptance: stop immediately after the first blocked batch.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),crypto=require('node:crypto');
const {chromium}=require('C:/Users/lylboy/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const root=path.resolve(__dirname,'../..'),out=path.join(root,'doc/cloudflare-rate-limit-20261020');
const base='https://www.xs001.xyz',bookId='2108671816092606464',chapterId='2111556607492304896';
const chapterPath=`/book/${bookId}/${chapterId}.html`,jsonPath=`/book/reader/chapter?bookId=${bookId}&bookIndexId=${chapterId}`;
const sha=v=>crypto.createHash('sha256').update(v).digest('hex');
(async()=>{
  const report={started:new Date().toISOString(),realEdge:true,maxBurstRequests:60,checks:[],errors:[]};
  const browser=await chromium.launch({headless:true,channel:'chrome'}),context=await browser.newContext({viewport:{width:390,height:844}});
  let page,requests=0;
  try{
    await context.route('**/hm.baidu.com/**',r=>r.abort());
    await context.route('**/reader/tts/**',r=>r.abort());
    page=await context.newPage();page.on('pageerror',e=>report.errors.push(e.message));
    page.on('request',r=>{if(['/book/reader/chapter','/book/searchByPage'].includes(new URL(r.url()).pathname))requests++;});
    page.on('response',r=>{if(['/book/reader/chapter','/book/searchByPage'].includes(new URL(r.url()).pathname))(report.responses||=[]).push({status:r.status(),at:new Date().toISOString(),retryAfter:r.headers()['retry-after']});});
    assert.equal((await page.goto(base+chapterPath,{waitUntil:'domcontentloaded'})).status(),200);
    await page.waitForFunction(()=>window.novelReader?.chapter);
    const original=await page.evaluate(()=>({id:novelReader.chapter.bookIndexId,next:novelReader.chapter.nextChapterId,text:document.querySelector('#reader-prose').textContent}));
    assert.ok(original.next);report.originalTextSha256=sha(original.text);
    const manifest=JSON.parse(fs.readFileSync(path.join(out,'candidate.sha256.json'),'utf8'));
    report.assets=await page.evaluate(async expected=>{
      const result=[];
      for(const [name,digest]of Object.entries(expected)){
        if(!name.startsWith('static/'))continue;
        const response=await fetch('/'+name.slice(7)+'?v=20261020.1',{cache:'no-store'}),bytes=await response.arrayBuffer();
        const actual=[...new Uint8Array(await crypto.subtle.digest('SHA-256',bytes))].map(b=>b.toString(16).padStart(2,'0')).join('');
        result.push({path:name,status:response.status,matches:actual===digest});
      }
      return result;
    },manifest);
    assert.ok(report.assets.every(v=>v.status===200&&v.matches));report.checks.push('public JavaScript assets match the tested candidate');
    report.burst=await page.evaluate(async({jsonPath})=>{
      const results=[];
      for(let batch=0;batch<20;batch++){
        const values=await Promise.all([0,1,2].map(async n=>{
          const index=batch*3+n,kind=index%2?'chapter':'search';
          const url=kind==='chapter'?jsonPath:'/book/searchByPage?curr=1&limit=1&sort=word_count';
          const started=performance.now(),response=await fetch(url,{cache:'no-store'});
          await response.arrayBuffer();
          return {index,kind,status:response.status,ray:response.headers.get('cf-ray'),server:response.headers.get('server'),elapsedMs:Math.round(performance.now()-started)};
        }));
        results.push(...values);if(values.some(v=>v.status===429))break;
        await new Promise(r=>setTimeout(r,60));
      }
      return results;
    },{jsonPath});
    assert.ok(report.burst.some(v=>v.status===429),'The real Cloudflare rule must block the bounded burst');
    assert.ok(report.burst.filter(v=>v.kind==='search'&&v.status===200).length<30);
    assert.ok(report.burst.filter(v=>v.kind==='chapter'&&v.status===200).length<30);
    report.checks.push('search and chapter traffic reaches a shared per-IP limit and Cloudflare returns 429');
    report.duringBlock=await page.evaluate(async({jsonPath,chapterPath})=>{
      const paths=['/book/searchByPage?curr=1&limit=1',jsonPath,chapterPath,'/book/bookclass.html?k=test','/','/book/listBookCategory','/javascript/site/rate-limit.js?v=20261020.1'];
      return await Promise.all(paths.map(async url=>{const response=await fetch(url,{cache:'no-store'});const text=await response.text();return {path:url,status:response.status,ray:response.headers.get('cf-ray'),friendly:response.status===429&&text.includes('访问过于频繁')};}));
    },{jsonPath,chapterPath});
    for(const value of report.duringBlock.slice(0,4))assert.ok(value.status===429&&value.friendly);
    for(const value of report.duringBlock.slice(4))assert.equal(value.status,200);
    report.checks.push('both HTML and JSON entrances are blocked while home, categories and static assets remain available');
    await page.locator('[data-chapter="next"]').click();
    await page.waitForFunction(()=>novelReader.state==='paused'&&document.querySelector('[data-rate-limit-notice]')?.textContent.includes('访问过于频繁'));
    assert.equal(await page.locator('#reader-prose').textContent(),original.text);
    const before=requests;
    await page.evaluate(async()=>{
      novelReader.repository.prefetch(novelReader.chapter);await novelReader.navigate(novelReader.chapter.nextChapterId);
      try{await(await import('/javascript/site/api.js?v=20261020.1')).api('/book/searchByPage');}catch(e){if(e.code!==429)throw e;}
    });
    assert.equal(requests,before);report.checks.push('a real edge 429 pauses frontend retries and prefetch while current text remains readable');
    await page.screenshot({path:path.join(out,'edge-429-reader-mobile.png')});
    const blockedPage=await context.newPage(),blockedResponse=await blockedPage.goto('https://xs001.xyz/book/bookclass.html?k=test',{waitUntil:'domcontentloaded'});
    report.apexBlockStatus=blockedResponse.status();report.apexFinalUrl=blockedPage.url();assert.equal(report.apexBlockStatus,429);
    assert.ok((await blockedPage.locator('h1').textContent()).includes('访问过于频繁'));
    await blockedPage.screenshot({path:path.join(out,'edge-429-page-mobile.png')});
    report.checks.push('the apex hostname shares the IP limit and displays the custom waiting page');
    await page.bringToFront();await page.waitForFunction(()=>document.querySelector('[data-rate-limit-notice]')?.textContent==='现在可以重试搜索或章节加载。',null,{timeout:16000});
    assert.equal(requests,before);assert.equal(await page.evaluate(()=>novelReader.wantRun),false);
    report.beforeManual=await page.evaluate(async()=>({remaining:(await import('/javascript/site/rate-limit.js?v=20261020.1')).remainingRetrySeconds(),notice:document.querySelector('[data-rate-limit-notice]')?.textContent,now:Date.now(),stored:localStorage.getItem('novel.search-chapter.retry-at.v1'),modules:performance.getEntriesByType('resource').filter(v=>v.name.includes('rate-limit.js')).map(v=>v.name)}));
    const manualResponse=page.waitForResponse(r=>new URL(r.url()).pathname==='/book/reader/chapter',{timeout:5000}).catch(()=>null);
    await page.locator('[data-chapter="next"]').click();
    const manual=await manualResponse;report.manualStatus=manual?.status();report.requestsAfterManual=requests;
    await page.waitForFunction(id=>novelReader.chapter.bookIndexId===id,original.next,{timeout:25000});
    assert.equal(await page.evaluate(()=>novelReader.wantRun),false);report.checks.push('manual chapter navigation succeeds after the ten-second block without automatic playback');
    assert.deepEqual(report.errors,[]);report.success=true;
  }catch(error){report.error=error.stack;process.exitCode=1;if(page)report.lastState=await page.evaluate(()=>({state:window.novelReader?.state,message:document.querySelector('#audio-state')?.textContent})).catch(()=>null);}
  finally{report.completed=new Date().toISOString();fs.writeFileSync(path.join(out,'edge-acceptance.json'),JSON.stringify(report,null,2));await browser.close();}
  console.log(JSON.stringify({success:report.success,checks:report.checks,burstRequests:report.burst?.length,firstBlocked:report.burst?.find(v=>v.status===429)?.index,apexBlockStatus:report.apexBlockStatus,apexFinalUrl:report.apexFinalUrl,error:report.error,lastState:report.lastState,beforeManual:report.beforeManual,manualStatus:report.manualStatus,requestsAfterManual:report.requestsAfterManual,lastResponses:report.responses?.slice(-3)}));
})().catch(error=>{console.error(error.message);process.exitCode=1;});
