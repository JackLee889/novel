const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),crypto=require('node:crypto');
const {chromium}=require('C:/Users/lylboy/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const run=fs.readFileSync('doc/frontend-realtest-current.txt','utf8').replace(/^\uFEFF/,'').trim();
assert.match(run,/^frontend-realtest-\d{8}-\d{6}$/);
const out=process.env.NOVEL_TEST_EVIDENCE_DIR?path.resolve(process.env.NOVEL_TEST_EVIDENCE_DIR):path.resolve('doc',run);
assert.ok(out.startsWith(path.resolve('doc')+path.sep)&&fs.statSync(out).isDirectory(),'Evidence directory must exist under doc.');
const runtime=JSON.parse(fs.readFileSync(path.join(out,'test-runtime.json'),'utf8').replace(/^\uFEFF/,''));
const proof=JSON.parse(fs.readFileSync(path.join(out,'actual-data-proof.json'),'utf8'));
const base=`http://127.0.0.1:${runtime.port}`,book=proof.books[0],first=proof.samples.find(s=>s.id===book.firstId);
const bookIds=new Set(proof.books.map(b=>b.id)),catalogs=new Map();
const report={started:new Date().toISOString(),base,image:runtime.image,profile:'prod',database:'novel_plus',
  realData:{books:proof.books.length,chapters:proof.books.reduce((n,b)=>n+b.chapterCount,0),categories:proof.categories.length},
  checks:[],layouts:[],blockedWrites:[],pageErrors:[],httpErrors:[],limitations:[
    '本组仅验证公开前端和未登录分支；已存在专用测试账号，登录及历史验证见独立账号回归记录。',
    '原部署关闭充值、购买和外部 AI；本次验证这些路由的 403 限制。',
    '浏览器拦截业务写请求；读取接口和业务数据均来自真实生产配置的服务。',
    '语音验收覆盖浏览器 PCM 合成及播放状态，没有验证实体扬声器听感。'
  ]};
const save=()=>fs.writeFileSync(path.join(out,'live-test-results.json'),JSON.stringify(report,null,2));
async function check(name,body){const start=Date.now();try{const detail=await body();report.checks.push({name,passed:true,ms:Date.now()-start,...(detail||{})});console.log('PASS',name);}catch(e){report.checks.push({name,passed:false,ms:Date.now()-start,error:e.stack});console.log('FAIL',name,e.message);}save();}
async function guard(context){await context.route('**/*',route=>{
  const request=route.request(),url=new URL(request.url());
  if(url.hostname==='hm.baidu.com')return route.abort();
  if(url.origin===base&&!['GET','HEAD','OPTIONS'].includes(request.method())&&url.pathname!=='/user/login'){
    report.blockedWrites.push({method:request.method(),path:url.pathname});return route.abort();
  }return route.continue();
});}
async function go(page,route,reader=false){const response=await page.goto(base+route,{waitUntil:'domcontentloaded'});assert.equal(response.status(),200,route);
  await page.waitForFunction(reader?()=>!!window.novelReader:()=>document.documentElement.dataset.ready==='true',null,{timeout:30000});return response;}
function observe(page){page.on('pageerror',e=>report.pageErrors.push({url:page.url(),error:e.message}));page.on('response',r=>{
  if(r.status()>=500)report.httpErrors.push({url:r.url(),status:r.status()});
});}
(async()=>{const browser=await chromium.launch({headless:true,channel:'chrome'}),context=await browser.newContext({viewport:{width:1440,height:1000}});context.setDefaultTimeout(30000);await guard(context);
async function get(route,status=200){const response=await context.request.get(base+route);assert.equal(response.status(),status,route);return {response,json:await response.json()};}
async function data(route){const {json}=await get(route);assert.equal(json.code,200,route);return json.data;}
const page=await context.newPage();observe(page);
try{
await check('数据库与搜索三页结果、总数和长整数 ID 一致',async()=>{let books=[];for(let curr=1;curr<=3;curr++){
 const p=await data(`/book/searchByPage?curr=${curr}&limit=12&sort=word_count`);assert.equal(Number(p.total),proof.books.length);assert.equal(Number(p.pageNum),curr);books.push(...p.list);
}assert.equal(books.length,26);assert.deepEqual([...new Set(books.map(b=>b.id))].sort(),[...bookIds].sort());
 books.forEach(b=>assert.equal(typeof b.id,'string'));for(let i=1;i<books.length;i++)assert.ok(books[i].wordCount<=books[i-1].wordCount);return {pages:3,bookIdsExact:true};});
await check('七个真实分类及筛选数量与 SQL 一致',async()=>{const cats=await data('/book/listBookCategory');assert.equal(cats.length,proof.categories.length);
 for(const c of proof.categories){assert.ok(cats.some(x=>String(x.id)===String(c.id)&&x.name===c.name));const result=await data(`/book/searchByPage?curr=1&limit=100&catId=${c.id}`);
 assert.equal(Number(result.total),c.books);assert.ok(result.list.every(b=>String(b.catId)===String(c.id)));}return {categories:cats.length};});
await check('真实中文书名搜索及无结果状态',async()=>{const found=await data('/book/searchByPage?curr=1&limit=12&keyword='+encodeURIComponent(book.name));assert.ok(found.list.some(b=>b.id===book.id));
 const absent=await data('/book/searchByPage?keyword='+encodeURIComponent('不存在的作品_'+run));assert.equal(Number(absent.total),0);});
await check('四种榜单均返回真实书籍',async()=>{for(let type=0;type<4;type++){const books=await data(`/book/listRank?type=${type}&limit=30`);assert.ok(books.length>0);assert.ok(books.every(b=>bookIds.has(b.id)));}return {types:4};});
await check('全部二十六本书的详情与数据库一致',async()=>{for(const b of proof.books){const d=await data('/book/queryBookDetail/'+b.id);assert.equal(d.id,b.id);assert.equal(d.bookName,b.name);assert.equal(d.authorName,b.author);assert.equal(d.wordCount,b.wordCount);}return {books:26};});
await check('全部真实目录分页覆盖 8482 章节',async()=>{let pages=0,chapters=0;for(const b of proof.books){let list=[];for(let n=1;;n++){
 const {response,json}=await get(`/book/reader/catalog?bookId=${b.id}&page=${n}`);assert.equal(json.code,200);assert.match(response.headers()['cache-control'],/private.*no-store/);
 const p=json.data;assert.equal(p.page,n);assert.ok(p.entries.length<=100);assert.equal(p.hasMore,n*100<b.chapterCount);pages++;list.push(...p.entries);if(!p.hasMore)break;
}assert.equal(list.length,b.chapterCount,b.name);assert.equal(new Set(list.map(i=>i.id)).size,list.length);list.forEach(i=>assert.equal(typeof i.id,'string'));
 for(let n=1;n<list.length;n++)assert.ok(list[n].number>list[n-1].number,b.name+' catalog order');assert.equal(list[0].id,b.firstId);assert.equal(list.at(-1).id,b.lastId);
 catalogs.set(b.id,list);chapters+=list.length;}assert.equal(chapters,report.realData.chapters);return {books:26,pages,chapters};});
await check('52 个真实首末章正文和数据库内容指纹一致',async()=>{let allowed=0;for(const s of proof.samples){const {response,json}=await get(`/book/reader/chapter?bookId=${s.bookId}&bookIndexId=${s.id}`,s.vip===1?401:200);
 const d=json.data;assert.equal(d.bookId,s.bookId);assert.equal(d.bookIndexId,s.id);assert.equal(d.chapterName,s.name);assert.match(response.headers()['cache-control'],/private.*no-store/);assert.match(response.headers().vary,/Cookie.*Authorization/);
 if(s.vip===1){assert.equal(d.access,'need_login');assert.deepEqual(d.paragraphs,[]);continue;}
 assert.equal(d.access,'allowed');assert.equal(d.contentVersion,s.contentVersion,s.name);assert.equal(d.paragraphs.length,s.paragraphCount,s.name);
 const text=d.paragraphs.map(p=>p.text).join('\n');assert.equal(Buffer.byteLength(text,'utf8'),s.textBytes);assert.equal(crypto.createHash('sha256').update('reader-text-v1\n'+text).digest('hex'),d.contentVersion);allowed++;
}return {samples:proof.samples.length,allowed};});
await check('错误书籍章节组合、无效目录和不存在书籍被拒绝',async()=>{const wrong=await get(`/book/reader/chapter?bookId=${proof.books[1].id}&bookIndexId=${book.firstId}`,404);assert.equal(wrong.json.code,5003);
 assert.ok(wrong.json.data==null);await get(`/book/reader/catalog?bookId=${book.id}&page=0`,400);await get('/book/reader/catalog?bookId=1',404);});
await check('充值、购买和外部 AI 沿用原部署限制',async()=>{for(const route of ['/pay/index.html','/user/buyBookIndex','/author/ai/polish']){const r=await context.request.get(base+route);assert.equal(r.status(),403);}});
await check('匿名书架与个人资料接口拒绝访问',async()=>{for(const route of ['/user/listBookShelfByPage?curr=1&limit=10','/user/userInfo']){const result=await get(route);assert.equal(result.json.code,1001);}});
await check('首页缺少推荐配置时展示真实长篇作品',async()=>{await go(page,'/');assert.equal(await page.locator('#home-featured .book-card').count(),6);
 assert.equal(await page.locator('[data-home-discover] h2 a').textContent(),book.name);assert.equal(await page.locator('[data-home-discover] h2 a').getAttribute('href'),'/book/'+book.id+'.html');
 assert.equal(await page.locator('#home-long-rail li').count(),5);assert.equal(await page.locator('#home-rank li').count(),5);await page.screenshot({path:path.join(out,'real-home-desktop.png'),fullPage:true});});
await check('浏览器真实分页、中文搜索、分类与排序',async()=>{await go(page,'/book/bookclass.html');assert.equal(await page.locator('#book-results .book-card').count(),12);
 await page.getByRole('button',{name:'下一页',exact:true}).click();await page.waitForFunction(()=>new URLSearchParams(location.search).get('page')==='2'&&document.querySelector('#book-results').getAttribute('aria-busy')===null);
 assert.equal(await page.locator('#book-results .book-card').count(),12);await page.getByRole('button',{name:'下一页',exact:true}).click();await page.waitForFunction(()=>document.querySelectorAll('#book-results .book-card').length===2);
 await page.locator('#searchKey').fill(book.name);await page.locator('#btnSearch').click();await page.waitForFunction(()=>document.documentElement.dataset.ready==='true');assert.equal(await page.locator('#book-results h3 a').first().getAttribute('href'),'/book/'+book.id+'.html');
 await go(page,'/book/bookclass.html');const cat=proof.categories.find(c=>c.id===book.catId);await page.locator('#category-options').getByRole('button',{name:cat.name,exact:true}).click();
 await page.waitForFunction(n=>document.querySelector('#result-count').textContent.includes('找到 '+n+' 部作品')&&document.querySelector('#book-results').getAttribute('aria-busy')===null,cat.books);
 await page.locator('#sort-books').selectOption('word_count');await page.waitForFunction(()=>location.search.includes('sort=word_count')&&document.querySelector('#book-results').getAttribute('aria-busy')===null);
 assert.equal(await page.locator('#category-options [aria-pressed=true]').textContent(),cat.name);});
await check('浏览器切换四种真实榜单',async()=>{await go(page,'/book/book_ranking.html');for(let type=0;type<4;type++){
 await page.locator(`[data-rank="${type}"]`).click();await page.waitForFunction(()=>document.querySelector('#ranking-results').getAttribute('aria-busy')===null);
 const urls=await page.locator('#ranking-results h3 a').evaluateAll(els=>els.map(e=>e.getAttribute('href')));assert.ok(urls.length>0);assert.ok(urls.every(u=>bookIds.has(u.match(/\/book\/(\d+)\.html/)[1])));
}});
await check('真实书籍详情、目录排序和章节搜索',async()=>{await go(page,'/book/'+book.id+'.html');assert.equal(await page.locator('h1').textContent(),book.name);
 assert.equal(await page.getByRole('link',{name:'开始阅读',exact:true}).getAttribute('href'),`/book/${book.id}/${book.firstId}.html`);await page.screenshot({path:path.join(out,'real-book-detail.png'),fullPage:true});
 await go(page,'/book/indexList-'+book.id+'.html');assert.equal(await page.locator('#chapter-list a').count(),book.chapterCount);assert.equal(await page.locator('#chapter-list a').first().getAttribute('href'),`/book/${book.id}/${book.firstId}.html`);
 await page.locator('#chapter-sort').click();assert.equal(await page.locator('#chapter-list a').first().getAttribute('href'),`/book/${book.id}/${book.lastId}.html`);
 await page.locator('#chapter-search').fill(first.name);assert.ok(await page.locator('#chapter-list a:visible').count()>0);await page.locator('#chapter-search').fill('不存在的章节_'+run);assert.equal(await page.locator('#chapter-empty').isVisible(),true);
});
await check('真实空搜索、空评论和站内公告',async()=>{await go(page,'/book/bookclass.html?k='+encodeURIComponent('不存在的作品_'+run));assert.match(await page.locator('#book-results').textContent(),/还没有找到/);
 await go(page,'/book/comment-'+book.id+'.html');assert.match(await page.locator('#comment-results').textContent(),/还没有书评/);
 await go(page,'/about/news.html');assert.equal(await page.locator('.news-list li').count(),proof.news.length);for(const n of proof.news){await go(page,'/about/newsInfo-'+n.id+'.html');assert.equal(await page.locator('h1').textContent(),n.title);}});
await check('匿名个人中心、书架和作者专区跳转到登录',async()=>{for(const route of ['/user/userinfo.html','/user/favorites.html','/author/index.html']){
 await page.goto(base+route);await page.waitForURL(url=>url.pathname==='/user/login.html'&&url.searchParams.get('originUrl')===route);await page.waitForFunction(()=>document.documentElement.dataset.ready==='true');assert.equal(new URL(page.url()).searchParams.get('originUrl'),route);
}});
await check('真实登录失败提示和密码显隐',async()=>{await go(page,'/user/login.html');await page.locator('#txtUName').fill('19900000000');await page.locator('#txtPassword').fill('LiveQaInvalidPassword123');
 await page.locator('[data-password-toggle]').click();assert.equal(await page.locator('#txtPassword').getAttribute('type'),'text');await page.locator('[data-password-toggle]').click();
 await page.getByRole('button',{name:'登录',exact:true}).click();await page.waitForFunction(()=>document.querySelector('#LabErr').textContent.trim().length>0);assert.match(page.url(),/\/user\/login.html/);
 return {message:await page.locator('#LabErr').textContent()};});
const routes=[['home','/'],['catalog','/book/bookclass.html'],['ranking','/book/book_ranking.html'],['detail','/book/'+book.id+'.html'],['chapters','/book/indexList-'+book.id+'.html'],['comments','/book/comment-'+book.id+'.html'],['news','/about/news.html'],['article','/about/newsInfo-'+proof.news[0].id+'.html'],['login','/user/login.html'],['register','/user/register.html'],['error','/404.html'],['reader',`/book/${book.id}/${book.firstId}.html`]];
await check('48 组真实页面桌面、手机、窄屏和夜间布局',async()=>{for(const profile of [{name:'desktop',width:1440,height:1000,theme:'light'},{name:'mobile',width:390,height:844,theme:'light'},{name:'small',width:320,height:740,theme:'light'},{name:'night',width:390,height:844,theme:'dark'}]){
 const c=await browser.newContext({viewport:{width:profile.width,height:profile.height},isMobile:profile.width<800,hasTouch:profile.width<800});await guard(c);await c.addInitScript(theme=>localStorage.setItem('novel.site.theme',theme),profile.theme);const p=await c.newPage();observe(p);
 for(const[name,route]of routes){const response=await go(p,route,name==='reader');const measured=await p.evaluate(()=>({title:document.title,scroll:document.documentElement.scrollWidth,width:innerWidth,
  theme:document.documentElement.dataset.theme,h1:document.querySelectorAll('h1').length,broken:[...document.images].filter(i=>i.complete&&!i.naturalWidth&&!i.hidden).map(i=>i.src)}));
 const passed=response.status()===200&&measured.scroll<=measured.width+1&&measured.h1===1&&measured.theme===profile.theme&&measured.broken.length===0;
 report.layouts.push({profile:profile.name,page:name,passed,...measured});if(['home','reader','detail'].includes(name)&&profile.name!=='small')await p.screenshot({path:path.join(out,`real-${name}-${profile.name}.png`),fullPage:name!=='reader'});
 }await c.close();console.log('LAYOUT',profile.name,'complete');save();}const failed=report.layouts.filter(x=>!x.passed);assert.deepEqual(failed,[]);return {layouts:report.layouts.length};});
await check('手机导航展开、关闭和夜间主题保存',async()=>{const c=await browser.newContext({viewport:{width:390,height:844}});await guard(c);const p=await c.newPage();observe(p);try{
 await go(p,'/');assert.equal(await p.locator('#primary-nav').isVisible(),false);await p.locator('[data-menu-toggle]').click();assert.equal(await p.locator('#primary-nav').isVisible(),true);await p.keyboard.press('Escape');assert.equal(await p.locator('#primary-nav').isVisible(),false);
 await p.locator('[data-theme-toggle]').click();assert.equal(await p.locator('html').getAttribute('data-theme'),'dark');await go(p,'/book/book_ranking.html');assert.equal(await p.locator('html').getAttribute('data-theme'),'dark');
}finally{await c.close();}});
await check('真实章节正文、下一章和朗读目录分页',async()=>{await go(page,`/book/${book.id}/${book.firstId}.html`,true);assert.equal(await page.locator('#chapter-title').textContent(),first.name);
 assert.equal(await page.locator('.rd-paragraph').count(),first.paragraphCount);const second=catalogs.get(book.id)[1];await page.locator('[data-chapter=next]').click();await page.waitForFunction(id=>novelReader.chapter.bookIndexId===id,second.id);
 await page.locator('[data-chapter=previous]').click();await page.waitForFunction(id=>novelReader.chapter.bookIndexId===id,book.firstId);await page.getByRole('button',{name:'目录',exact:true}).first().click();
 await page.waitForFunction(()=>document.querySelectorAll('.rd-catalog button').length===100);await page.getByRole('button',{name:'下一页',exact:true}).click();await page.waitForFunction(name=>document.querySelector('.rd-catalog button')?.textContent.startsWith(name),catalogs.get(book.id)[100].name);
 assert.equal(await page.locator('.rd-catalog button').count(),Math.min(100,book.chapterCount-100));await page.keyboard.press('Escape');assert.equal(await page.locator('#reader-panel').isVisible(),false);
});
await check('真实章节 Sherpa 模型、单引擎和 16kHz PCM 播放暂停继续',async()=>{const workers=[];page.on('worker',w=>workers.push(w.url()));await page.evaluate(()=>novelReader.changeSetting('autoNext',false));
 if(await page.evaluate(()=>window.novelGlobalPlayer?.dock&&!novelGlobalPlayer.dock.expanded))await page.locator('.player-orb').click();
 if(await page.getByRole('button',{name:'固定播放器',exact:true}).count())await page.getByRole('button',{name:'固定播放器',exact:true}).click();
 report.audioStart=await page.evaluate(()=>({state:novelReader.state,label:document.querySelector('[data-action=play]').getAttribute('aria-label')}));save();
 const started=Date.now();await page.locator('[data-action=play]').click();await page.waitForFunction(()=>['playing','error'].includes(novelReader.state),null,{timeout:120000});
 const playback=await page.evaluate(()=>({state:novelReader.state,message:novelReader.message}));assert.equal(playback.state,'playing',playback.message);
 const firstPlaybackMs=Date.now()-started;const audio=await page.evaluate(()=>({bookId:novelReader.chapter.bookId,chapterId:novelReader.chapter.bookIndexId,contentVersion:novelReader.chapter.contentVersion,
  rate:novelReader.player.source.buffer.sampleRate,samples:novelReader.player.source.buffer.length,time:novelReader.player.context.currentTime,
  nonZero:novelReader.player.source.buffer.getChannelData(0).some(x=>Math.abs(x)>0.0001),engineState:novelReader.engine.state,
  sharedEngine:novelReader.engine.shared,engineId:novelReader.engine.engineId,initializations:novelReader.engine.initializations,queue:novelReader.scheduler.usage()}));
 assert.equal(audio.bookId,book.id);assert.equal(audio.chapterId,book.firstId);assert.equal(audio.contentVersion,first.contentVersion);assert.equal(audio.rate,16000);assert.ok(audio.samples>0&&audio.nonZero);
 if(audio.sharedEngine){assert.ok(audio.engineId);assert.equal(audio.initializations,1);assert.equal(workers.length,0);}else assert.equal(workers.length,1);
 await page.waitForTimeout(900);assert.ok(await page.evaluate(t=>novelReader.player.context.currentTime>t+.7,audio.time));await page.getByRole('button',{name:'暂停朗读',exact:true}).click();
 const paused=await page.evaluate(()=>novelReader.player.context.currentTime);await page.waitForTimeout(350);assert.equal(await page.evaluate(()=>novelReader.player.context.currentTime),paused);
 await page.getByRole('button',{name:'继续朗读',exact:true}).click();await page.waitForFunction(()=>novelReader.state==='playing');await page.screenshot({path:path.join(out,'real-reader-playing.png')});
 const stats=await page.evaluate(()=>({...novelReader.scheduler.stats,usage:novelReader.scheduler.usage()}));assert.ok(stats.peakCount<=2);assert.ok(stats.peakBytes<=16*1024*1024);
 report.audio={...audio,firstPlaybackMs,workers,stats};await page.evaluate(()=>novelReader.stop());return {sampleRate:audio.rate,samples:audio.samples,sharedEngine:audio.sharedEngine,initializations:audio.initializations,workerCount:workers.length};});
await check('浏览器无未捕获异常与服务器 5xx',async()=>{assert.deepEqual(report.pageErrors,[]);assert.deepEqual(report.httpErrors,[]);});
}finally{report.completed=new Date().toISOString();report.success=report.checks.every(c=>c.passed);save();await browser.close();}
console.log(JSON.stringify({success:report.success,checks:report.checks.length,passed:report.checks.filter(x=>x.passed).length,layouts:report.layouts.length,blockedWrites:report.blockedWrites.length}));if(!report.success)process.exitCode=1;
})().catch(error=>{report.fatal=error.stack;report.success=false;save();console.error(error);process.exitCode=1;});
