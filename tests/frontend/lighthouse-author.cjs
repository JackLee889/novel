const fs=require('node:fs'),path=require('node:path');
const {pathToFileURL}=require('node:url');
const {chromium}=require('C:/Users/lylboy/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const base=process.env.FRONTEND_BASE||'http://127.0.0.1:18984';
(async()=>{
  const cache=path.join(process.env.LOCALAPPDATA,'npm-cache','_npx');
  const root=fs.readdirSync(cache).map(n=>path.join(cache,n,'node_modules','lighthouse')).find(p=>{
    try{return JSON.parse(fs.readFileSync(path.join(p,'package.json'),'utf8')).version==='12.8.2';}catch{return false;}
  });
  if(!root)throw new Error('Run npx --yes lighthouse@12.8.2 --version first.');
  const {default:lighthouse}=await import(pathToFileURL(path.join(root,'core','index.js')).href);
  const context=await chromium.launchPersistentContext(path.resolve('.reader-tools/lighthouse-profile'),{
    headless:true,channel:'chrome',args:['--remote-debugging-port=18986','--no-sandbox','--disable-gpu']
  });
  try{
    await context.addCookies([{name:'Authorization',value:'fixture-purchased',url:base}]);
    const url=base+'/author/content_add.html?bookId=9007199254740993';
    const result=await lighthouse(url,{port:18986,disableStorageReset:true,onlyCategories:['accessibility'],
      blockedUrlPatterns:['*hm.baidu.com*'],output:['json','html'],logLevel:'error'});
    if(!result.lhr.finalDisplayedUrl.includes('/author/content_add.html'))throw new Error('Audit did not reach the author editor.');
    fs.writeFileSync('doc/frontend-evidence/lighthouse-author.report.json',result.report[0]);
    fs.writeFileSync('doc/frontend-evidence/lighthouse-author.report.html',result.report[1]);
    console.log(JSON.stringify({url:result.lhr.finalDisplayedUrl,accessibility:result.lhr.categories.accessibility.score*100}));
  }finally{await context.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
