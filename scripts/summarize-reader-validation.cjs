const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const dir=path.resolve(__dirname,'../doc/reader-evidence');
const read=name=>JSON.parse(fs.readFileSync(path.join(dir,name),'utf8'));
const browser=read('browser-validation.json'),focused=read('focused-validation.json'),resilience=read('resilience-validation.json');
const delivery=read('delivery-validation.json');
for(const report of [browser,focused,resilience,delivery]){assert.equal(report.success,true);assert.deepEqual(report.errors,[]);}
const soak=browser.checks.find(x=>x.wallSeconds),three=browser.checks.find(x=>x.name.includes('三个完整'));
assert.ok(soak.audioClockSeconds>=300);assert.equal(three.initializations,1);
assert.ok(soak.stats.peakCount<=2&&soak.stats.peakSeconds<=30&&soak.stats.peakBytes<=16*1024*1024);
const maven=fs.readFileSync(path.join(dir,'maven-package.log'),'utf8'),core=fs.readFileSync(path.join(dir,'node-core-tests.log'),'utf8');
assert.match(maven,/BUILD SUCCESS/);assert.match(maven,/Tests run: 11, Failures: 0, Errors: 0, Skipped: 0/);
assert.match(core,/# pass 7/);assert.match(core,/# fail 0/);
const lighthouse=read('lighthouse-mobile.json'),release=read('release-artifact.json'),changes=read('changed-files.json');
const summary={
  generatedAt:new Date().toISOString(),success:true,
  environment:{os:'Windows',cpu:'Intel Core i7-9700 @ 3.00 GHz',physicalMemoryBytes:51368742912,browser:browser.browser,
    fixtureUrl:'http://127.0.0.1:18983/book/9007199254740993/9007199254741001.html',productionDeployed:false},
  tests:{java:11,nodeCore:7,browserChecks:browser.checks.length,focusedChecks:focused.checks.length,resilienceChecks:resilience.checks.length,finalViewports:delivery.viewports.length},
  tts:{coldFirstAudioMs:browser.checks.find(x=>x.name.includes('冷加载')).ms,
    hotFirstAudioMs:browser.checks.find(x=>x.name.includes('热引擎')).ms,
    wav:browser.checks.filter(x=>x.name.includes('真实 WAV')),
    threeFullChapters:{initializations:three.initializations,completedSegments:three.player.completed,nextChapterId:three.id},
    soak:{wallSeconds:soak.wallSeconds,audioClockSeconds:soak.audioClockSeconds,observations:browser.soak.length,
      generated:soak.stats.generated,completed:soak.player.completed,
      rtf:soak.stats.elapsedMs/1000/soak.stats.audioSeconds,
      pendingPeak:{count:soak.stats.peakCount,seconds:soak.stats.peakSeconds,bytes:soak.stats.peakBytes},
      gapsOver100Ms:soak.gapsOver100Ms,maxGapMs:Math.max(0,...soak.segmentGapsMs),
      wasmHeapBytes:soak.stats.wasmBytes,
      mainJsHeapObservedBytes:{min:Math.min(...browser.soak.map(x=>x.heap)),max:Math.max(...browser.soak.map(x=>x.heap)),end:soak.heap},
      memoryScope:'WASM heap and pending PCM limits are separate; main JS heap is not total browser or Worker memory.'},
    workerTimeout:focused.checks.find(x=>x.name.includes('45秒'))},
  lighthouse:{version:lighthouse.lighthouseVersion,scores:Object.fromEntries(Object.entries(lighthouse.categories).map(([k,v])=>[k,v.score*100])),
    fcpMs:lighthouse.audits['first-contentful-paint'].numericValue,lcpMs:lighthouse.audits['largest-contentful-paint'].numericValue,
    cls:lighthouse.audits['cumulative-layout-shift'].numericValue,totalBlockingMs:lighthouse.audits['total-blocking-time'].numericValue,
    scope:'Mobile emulation, local fixture SSR, TTS not loaded, development static files.'},
  release,changedFileCounts:changes.reduce((a,x)=>(a[x.kind]=(a[x.kind]||0)+1,a),{}),
  evidence:['browser-validation.json','focused-validation.json','resilience-validation.json','delivery-validation.json','maven-package.log','node-core-tests.log','lighthouse-mobile.json'],
  limitations:['No production deployment or database/payment writes.','No physical phone, iOS lock-screen/background or headset acceptance.',
    'No complete production search/login/shelf/history/purchase regression.','Audio output verified automatically; subjective listening not claimed.']
};
fs.writeFileSync(path.join(dir,'validation-summary.json'),JSON.stringify(summary,null,2));
console.log(JSON.stringify({success:summary.success,tests:summary.tests,coldFirstAudioMs:summary.tts.coldFirstAudioMs,
 hotFirstAudioMs:summary.tts.hotFirstAudioMs,soak:summary.tts.soak,scores:summary.lighthouse.scores}));
