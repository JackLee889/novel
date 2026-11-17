const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),assert=require('node:assert/strict');
const {chromium}=require('C:/Users/lylboy/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const root=process.cwd(),run=fs.readFileSync('doc/covers-import-current.txt','utf8').trim(),out=path.join(root,'doc',run),base='http://127.0.0.1:18987';
assert.match(run,/^covers-import-\d{8}-\d{6}$/);
const archive=JSON.parse(fs.readFileSync(path.join(out,'archive-validation.json'),'utf8')),mapping=JSON.parse(fs.readFileSync(path.join(out,'cover-mapping.json'),'utf8'));
const byId=new Map(mapping.map(item=>[item.id,item])),report={started:new Date().toISOString(),base,checks:[],pageErrors:[],imageRequests:0,imageBytes:0};
function save(){fs.writeFileSync(path.join(out,'live-cover-validation.json'),JSON.stringify(report,null,2));}
function record(name,details={}){report.checks.push({name,passed:true,...details});save();console.log('PASS '+name);}
let browser;
(async()=>{
  let cursor=0;const headers={};
  const worker=async()=>{while(cursor<archive.images.length){const item=archive.images[cursor++],response=await fetch(base+item.picUrl);assert.equal(response.status,200,item.id);const bytes=Buffer.from(await response.arrayBuffer());assert.equal(bytes.length,item.bytes,item.id);assert.equal(crypto.createHash('sha256').update(bytes).digest('hex'),item.sha256,item.id);report.imageRequests++;report.imageBytes+=bytes.length;const type=response.headers.get('content-type')||'(unspecified)';headers[type]=(headers[type]||0)+1;}};
  await Promise.all(Array.from({length:8},worker));assert.equal(report.imageRequests,1563);
  record('全部 1,563 个封面 URL 返回 200，字节数和 SHA-256 均与导入包一致',{images:report.imageRequests,bytes:report.imageBytes,contentTypes:headers});
  browser=await chromium.launch({channel:'chrome',headless:true,args:['--disable-gpu','--renderer-process-limit=1']});
  const context=await browser.newContext({viewport:{width:1440,height:1050}});
  await context.route('**/*',route=>{const req=route.request(),url=new URL(req.url());if(url.hostname!=='127.0.0.1')return route.abort();if(req.method()==='POST'&&['/book/addVisitCount','/user/addReadHistory'].includes(url.pathname))return route.fulfill({status:200,contentType:'application/json',body:'{"code":200}'});if(!['GET','HEAD'].includes(req.method()))return route.abort();return route.continue();});
  const page=await context.newPage();page.on('pageerror',error=>report.pageErrors.push(error.message));
  async function data(url){const response=await context.request.get(base+url);assert.equal(response.status(),200);const result=await response.json();assert.equal(result.code,200);return result.data;}
  const books=[];
  for(let n=1;books.length<mapping.length;n++){const result=await data('/book/searchByPage?curr='+n+'&limit=100');assert.equal(Number(result.total),mapping.length);assert.ok(result.list.length);books.push(...result.list);}
  assert.equal(books.length,mapping.length);assert.equal(new Set(books.map(b=>b.id)).size,mapping.length);
  for(const book of books){assert.equal(typeof book.id,'string');const item=byId.get(book.id);assert.ok(item);assert.equal(book.picUrl,item.pic_url);assert.equal(book.bookName,item.book_name);assert.equal(book.authorName,item.author_name);}
  record('全部 16 页图书接口按完整 ID 返回对应封面、书名和作者',{books:books.length,pages:16});
  const settings=await data('/book/listBookSetting');let recommended=0;
  for(const book of Object.values(settings).flat()){const id=String(book.bookId),item=byId.get(id);assert.ok(item);assert.equal(book.picUrl,item.pic_url);recommended++;}
  for(let type=0;type<4;type++){const ranking=await data('/book/listRank?type='+type+'&limit=30');assert.ok(ranking.length);for(const book of ranking)assert.equal(book.picUrl,byId.get(book.id).pic_url);}
  record('首页推荐及四种榜单均引用新封面',{recommendations:recommended,rankingTypes:4});
  async function go(url){const response=await page.goto(base+url,{waitUntil:'domcontentloaded'});assert.equal(response.status(),200);await page.waitForFunction(()=>document.documentElement.dataset.ready==='true',null,{timeout:30000});}
  async function coverElements(){await page.waitForSelector('.cover img');await page.waitForFunction(()=>{const image=document.querySelector('.cover img');return image.complete&&image.naturalWidth===450&&image.naturalHeight===600;});const items=await page.locator('.cover img').evaluateAll(images=>images.map(image=>({src:new URL(image.src).pathname,width:image.naturalWidth,height:image.naturalHeight})));assert.ok(items.length);for(const item of items){assert.match(item.src,/^\/localPic\/cover\/\d+\.jpg$/);const id=path.basename(item.src,'.jpg');assert.equal(item.src,byId.get(id).pic_url);}return items.length;}
  await go('/');const homeImages=await coverElements();await page.screenshot({path:path.join(out,'covers-home-desktop.png')});
  await go('/book/bookclass.html?k='+encodeURIComponent('深空彼岸'));await coverElements();assert.equal(await page.locator('#book-results h3 a').first().getAttribute('href'),'/book/2106418450629967872.html');await page.screenshot({path:path.join(out,'covers-search.png')});
  for(const id of ['2106418450629967872','2106420586008854528','2106420586008854529']){await go('/book/'+id+'.html');await coverElements();assert.equal(await page.locator('h1').textContent(),byId.get(id).book_name);const mainImage=await page.locator('.cover img').first().getAttribute('src');assert.equal(new URL(mainImage,base).pathname,byId.get(id).pic_url);if(id==='2106418450629967872')await page.screenshot({path:path.join(out,'covers-book-detail.png')});}
  record('浏览器首页、中文搜索和三本代表书籍的详情封面正常显示',{homeCoverElements:homeImages,detailBooks:3});
  await page.setViewportSize({width:390,height:844});await go('/');await coverElements();assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth+1));await page.screenshot({path:path.join(out,'covers-home-mobile.png')});
  await page.evaluate(()=>{document.documentElement.dataset.theme='dark';localStorage.setItem('novel.site.theme','dark');});await page.screenshot({path:path.join(out,'covers-home-mobile-dark.png')});
  record('手机日间及夜间页面可显示本地封面，无横向溢出');
  const reader=await page.goto(base+'/book/2106418450629967872/2106418564102668288.html',{waitUntil:'domcontentloaded'});assert.equal(reader.status(),200);await page.waitForFunction(()=>window.novelReader?.chapter?.paragraphs?.length>0);assert.equal(await page.locator('[data-feedback-open]').count(),1);
  record('正文阅读和新增章节反馈入口正常');
  assert.deepEqual(report.pageErrors,[]);await context.close();report.passed=true;report.finished=new Date().toISOString();save();
})().catch(error=>{report.passed=false;report.error=error.stack;save();console.error(error.message);process.exitCode=1;}).finally(async()=>{await browser?.close();});
