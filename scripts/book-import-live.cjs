// Verify the activated imported library through real APIs and the current reader UI.
const fs=require('node:fs'),path=require('node:path'),cp=require('node:child_process'),crypto=require('node:crypto'),assert=require('node:assert/strict');
const {chromium}=require(path.join(process.env.USERPROFILE,'.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright'));
const root=path.resolve(__dirname,'..'),out=path.resolve(process.argv[2]),base='http://127.0.0.1:18987';
const activation=JSON.parse(fs.readFileSync(path.join(out,'activation-state.json'),'utf8'));
assert.equal(activation.phase,'activated');
const report={base,started:new Date().toISOString(),checks:[],pageErrors:[]};
const save=()=>fs.writeFileSync(path.join(out,'live-library-validation.json'),JSON.stringify(report,null,2));
const record=(name,details={})=>{report.checks.push({name,result:'pass',...details});save();console.log(name);};
function sql(query){try{return cp.execFileSync('docker',['exec','-i','novel-plus-local-mysql-1','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --user=root --default-character-set=utf8mb4 --batch --raw --skip-column-names novel_plus'],{input:query,encoding:'utf8',windowsHide:true,maxBuffer:8*1024*1024}).trim().split(/\r?\n/).filter(Boolean).map(row=>row.split('\t'));}catch(e){fs.writeFileSync(path.join(out,'backup/live-sql-error.log'),String(e.stderr||e.message));throw new Error('SQL acceptance query failed; inspect the private live-sql-error.log.');}}
function credentials(){const file=path.join(root,'doc/auth-history-20261007-104112/backup/test-credentials.dpapi.json').replace(/'/g,"''");const code=`$items=Get-Content -LiteralPath '${file}' -Raw | ConvertFrom-Json; $items | ForEach-Object { $s=ConvertTo-SecureString $_.encryptedPassword; $p=New-Object System.Management.Automation.PSCredential('qa',$s); [pscustomobject]@{username=$_.username;password=$p.GetNetworkCredential().Password} } | ConvertTo-Json`;return JSON.parse(cp.execFileSync('pwsh.exe',['-NoProfile','-Command',code],{encoding:'utf8',windowsHide:true}));}
const books=sql('SELECT CAST(id AS CHAR),book_name,author_name,cat_id FROM book ORDER BY id;').map(r=>({id:r[0],name:r[1],author:r[2],catId:r[3]}));
const ids=new Set(books.map(b=>b.id));
const categories=sql('SELECT c.id,c.name,COUNT(b.id) FROM book_category c LEFT JOIN book b ON b.cat_id=c.id GROUP BY c.id,c.name ORDER BY c.id;');
const representative=sql('SELECT CAST(id AS CHAR) FROM (SELECT id,ROW_NUMBER() OVER(PARTITION BY cat_id ORDER BY word_count DESC,id) AS n FROM book) ranked WHERE n<=2 ORDER BY id;').flat();
const accounts=credentials();
let browser;
(async()=>{
  browser=await chromium.launch({channel:'chrome',headless:true,args:['--disable-gpu','--renderer-process-limit=1']});
  const context=await browser.newContext({viewport:{width:1440,height:1000}});
  await context.route('**/*',route=>{const request=route.request(),url=new URL(request.url());if(url.hostname!=='127.0.0.1'||!['GET','HEAD'].includes(request.method()))return route.abort();return route.continue();});
  const page=await context.newPage();page.on('pageerror',e=>report.pageErrors.push(e.message));
  async function data(url,headers){const response=await context.request.get(base+url,{headers,timeout:30000});assert.equal(response.status(),200,url);const result=await response.json();assert.equal(result.code,200,url);return result.data;}
  async function go(url){const response=await page.goto(base+url,{waitUntil:'domcontentloaded'});assert.equal(response.status(),200);if(/^\/book\/\d+\/\d+\.html$/.test(url))await page.waitForFunction(()=>window.novelReader?.chapter?.bookIndexId,null,{timeout:35000});else await page.waitForFunction(()=>document.documentElement.dataset.ready==='true',null,{timeout:35000});}
  let all=[];
  for(let n=1;all.length<books.length;n++){const p=await data(`/book/searchByPage?curr=${n}&limit=100`);assert.equal(Number(p.total),books.length);assert.ok(p.list.length);all.push(...p.list);}
  assert.equal(all.length,books.length);assert.equal(new Set(all.map(b=>b.id)).size,books.length);assert.deepEqual(all.map(b=>b.id).sort(),[...ids].sort());all.forEach(b=>assert.equal(typeof b.id,'string'));
  record('图书 API 分页完整返回全部导入图书',{books:books.length,pages:Math.ceil(books.length/100)});
  for(const [id,name,count] of categories){const p=await data(`/book/searchByPage?curr=1&limit=100&catId=${id}`);assert.equal(Number(p.total),Number(count),name);assert.ok(p.list.every(b=>String(b.catId)===id));}
  record('全部分类的图书数量与 SQL 一致',{categories:categories.length});
  for(let type=0;type<4;type++){const result=await data(`/book/listRank?type=${type}&limit=30`);assert.ok(result.length);assert.ok(result.every(b=>ids.has(b.id)));}
  const settings=await data('/book/listBookSetting');assert.ok(Object.values(settings).flat().every(b=>ids.has(String(b.bookId))));
  record('榜单和首页推荐均引用新书库',{rankingTypes:4});
  await page.goto(base+'/',{waitUntil:'domcontentloaded'});
  let sampled=0;
  for(const id of representative){
    const book=books.find(b=>b.id===id),detail=await data('/book/queryBookDetail/'+id);assert.equal(detail.bookName,book.name);assert.equal(detail.authorName,book.author);
    const chapters=sql(`SELECT CAST(id AS CHAR),index_name,is_vip,index_num FROM book_index WHERE book_id=${id} ORDER BY index_num;`);assert.ok(chapters.length);
    const first=await data(`/book/reader/catalog?bookId=${id}&page=1`),last=await data(`/book/reader/catalog?bookId=${id}&page=${Math.ceil(chapters.length/100)}`);
    assert.equal(first.entries[0].id,chapters[0][0]);assert.equal(last.entries.at(-1).id,chapters.at(-1)[0]);assert.equal(last.hasMore,false);
    const picks=[chapters[0],chapters[Math.floor(chapters.length/2)],chapters.at(-1)];
    for(const chapter of picks){
      const response=await context.request.get(`${base}/book/reader/chapter?bookId=${id}&bookIndexId=${chapter[0]}`);
      assert.match(response.headers()['cache-control'],/private.*no-store/);const body=await response.json();
      assert.equal(body.data.bookId,id);assert.equal(body.data.bookIndexId,chapter[0]);assert.equal(body.data.chapterName,chapter[1]);
      if(chapter[2]==='1'){assert.equal(response.status(),401);assert.equal(body.data.access,'need_login');assert.deepEqual(body.data.paragraphs,[]);}
      else {assert.equal(response.status(),200);const html=Buffer.from(sql(`SELECT HEX(content) FROM book_content WHERE index_id=${chapter[0]};`)[0][0],'hex').toString('utf8');
        const texts=await page.evaluate(raw=>{let s=raw.replace(/<(script|style)[^>]*>.*?<\/\1\s*>/gis,'').replace(/<br\s*\/?>|<\/(?:p|div|li|h[1-6])\s*>/gi,'\n').replace(/<!--.*?-->/gs,'').replace(/<\/?[A-Za-z][^>]*>/g,'');const t=document.createElement('textarea');t.innerHTML=s;s=t.value.replace(/\u00a0/g,' ').replace(/\r/g,'');return s.split(/\n+/).map(v=>v.trim()).filter(Boolean);},html);
        assert.deepEqual(body.data.paragraphs.map(p=>p.text),texts);assert.equal(body.data.contentVersion,crypto.createHash('sha256').update('reader-text-v1\n'+texts.join('\n')).digest('hex'));
      }sampled++;
    }
  }
  record('跨分类代表作品的目录首尾及首中末章正文与 SQL 一致',{books:representative.length,chapters:sampled});
  const main=books[0];await go('/');assert.ok(await page.locator('.book-card').count());await page.screenshot({path:path.join(out,'imported-home-desktop.png'),fullPage:true});
  await go('/book/bookclass.html?k='+encodeURIComponent(main.name));assert.equal(await page.locator('#book-results h3 a').first().getAttribute('href'),'/book/'+main.id+'.html');
  await go('/book/'+main.id+'.html');assert.equal(await page.locator('h1').textContent(),main.name);const href=await page.getByRole('link',{name:'开始阅读',exact:true}).getAttribute('href');assert.ok(href.includes('/'+main.id+'/'));await page.screenshot({path:path.join(out,'imported-book-detail.png'),fullPage:true});
  await go(href);await page.waitForFunction(()=>window.novelReader?.chapter?.paragraphs?.length>0);assert.ok(await page.locator('.rd-paragraph').count());await page.screenshot({path:path.join(out,'imported-reader-desktop.png'),fullPage:true});
  const second=sql(`SELECT CAST(id AS CHAR) FROM book_index WHERE book_id=${main.id} ORDER BY index_num LIMIT 1 OFFSET 1;`)[0][0];
  await page.locator('[data-chapter=next]').click();await page.waitForFunction(id=>window.novelReader.chapter.bookIndexId===id,second);assert.ok(await page.locator('.rd-paragraph').count());
  await page.setViewportSize({width:390,height:844});await go('/');assert.ok(await page.locator('.book-card').count());assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth+1));await page.screenshot({path:path.join(out,'imported-home-mobile.png'),fullPage:true});
  record('浏览器首页、中文搜索、书籍详情、正文和移动页面正常');
  for(const account of accounts){
    const login=await context.request.post(base+'/user/login',{form:account});const result=await login.json();assert.equal(result.code,200);
    const headers={Authorization:result.data.token},profile=await data('/user/userInfo',headers);assert.equal(profile.username,account.username);
    const history=await data('/user/listReadHistoryByPage?curr=1&limit=12',headers);assert.equal(Number(history.total),0);
  }
  record('保留的两个账号可以登录，旧书库阅读历史已移至回滚库',{accounts:2});
  assert.deepEqual(report.pageErrors,[]);report.finished=new Date().toISOString();report.passed=true;save();
})().catch(e=>{report.passed=false;report.error=e.stack;save();console.error(e.message);process.exitCode=1;}).finally(async()=>{if(browser)await browser.close();});
