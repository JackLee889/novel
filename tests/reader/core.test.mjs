import test from 'node:test';
import assert from 'node:assert/strict';
import {segmentChapter} from '../../novel-front/src/main/resources/static/javascript/reader/segmenter.js';
import {TtsScheduler} from '../../novel-front/src/main/resources/static/javascript/reader/scheduler.js';
import {ReaderStorage,sanitize} from '../../novel-front/src/main/resources/static/javascript/reader/storage.js';
import {AudioPlayer} from '../../novel-front/src/main/resources/static/javascript/reader/audio-player.js';
test('阅读器继承全站配色，显式阅读设置与旧夜间设置优先',()=>{
  const values=new Map([['novel.site.theme','dark']]);const storage={getItem:key=>values.get(key)||null};
  assert.equal(new ReaderStorage(storage).settings().theme,'dark');
  values.set('novel.reader.v1.settings',JSON.stringify({theme:'green'}));
  assert.equal(new ReaderStorage(storage).settings().theme,'green');
  values.delete('novel.reader.v1.settings');values.set('novel.site.theme','light');values.set('colorNum','6');
  assert.equal(new ReaderStorage(storage).settings().theme,'dark');
});
const chapter = {bookIndexId:'9007199254740993',contentVersion:'v1',paragraphs:[]};
test('中文、引号、英文缩写、小数和 Unicode 映射回原文',() => {
  const texts = ['他说：“Dr. Lin 在下午3.5点回来。”一切仍然平静。','Hello, reader! 数字123不能删。','😀'.repeat(40)+'字'.repeat(410),'---','！？',''];
  const c = {...chapter,paragraphs:texts.map((text,i)=>({id:'p'+i,text}))}, s = segmentChapter(c,35);
  assert.equal(new Set(s.map(x=>x.paragraphId)).size,3);
  for(const p of c.paragraphs.slice(0,3)) {
    const a=s.filter(x=>x.paragraphId===p.id);
    assert.equal(a.map(x=>x.text).join(''),p.text);
    for(const x of a) {
      assert.equal(x.text,p.text.slice(x.startOffset,x.endOffset));assert.ok(Array.from(x.text).length<=180);
      assert.ok(!/^[\uDC00-\uDFFF]|[\uD800-\uDBFF]$/.test(x.text));
    }
  }
  assert.ok(s.find(x=>x.text.includes('Dr. Lin') && x.text.includes('3.5')));
});
const fakeEngine = (deferred = false) => {
  let running=0,peak=0,resolve;
  return {manifest:{engineVersion:'1.13.8',modelId:'matcha-icefall-zh-en',speakerId:0},
    ready:async()=>{},get peak(){return peak;},
    synthesize:async()=> {
      running++;peak=Math.max(peak,running);
      if(deferred) await new Promise(r=>resolve=r);
      else await new Promise(r=>setTimeout(r,2));
      running--;
      return {samples:new Float32Array(160000),sampleRate:16000,duration:10,elapsedMs:2,wasmBytes:1,speed:1};
    },release(){resolve?.();deferred=false;}
  };
};
const segments = segmentChapter({...chapter,paragraphs:Array.from({length:8},(_,i)=>({id:'p'+i,text:'中文第'+i+'段。'}))});
test('停止与版本更新丢弃晚到音频，推理始终串行',async()=>{
  const e=fakeEngine(true),q=new TtsScheduler(e);q.reset(segments,'a');q.enabled=true;
  const old=q.get(0).catch(e=>e);
  await new Promise(r=>setTimeout(r,5));
  q.reset(segments,'b');const current=q.get(1);
  e.release();assert.equal((await old).name,'AbortError');assert.ok((await current).samples.length);
  assert.equal(e.peak,1);assert.equal(q.stats.discarded,1);
});
test('等待旧预生成时 Worker 失败仍传给当前播放，允许明确重试',async()=>{
  const e=fakeEngine();let rejectTask,calls=0;
  e.synthesize=()=>{calls++;return new Promise((resolve,reject)=>{rejectTask=reject;});};
  const q=new TtsScheduler(e);q.reset(segments,'old');
  const old=q.get(0).catch(error=>error);
  await new Promise(resolve=>setTimeout(resolve,0));
  q.reset(segments,'current');
  const current=q.get(1).catch(error=>error);
  await new Promise(resolve=>setTimeout(resolve,0));
  const failure=new Error('语音合成超时');rejectTask(failure);
  assert.equal(await old,failure);assert.equal(await current,failure);
  assert.equal(calls,1);assert.equal(q.inflight,null);assert.equal(q.usage().count,0);
  e.synthesize=fakeEngine().synthesize;
  assert.ok((await q.get(1)).samples.length);
});
test('预生成缓存最多2片段、30秒、16MiB；暂停不继续排程',async()=>{
  const q=new TtsScheduler(fakeEngine());q.reset(segments,'a');q.enabled=true;
  await q.prefetch(1);assert.deepEqual(q.usage(),{bytes:1280000,seconds:20,count:2});
  await q.get(1);assert.equal(q.usage().count,1);assert.equal(q.stats.hits,1);
  q.enabled=false;const n=q.stats.generated;await q.prefetch(4);assert.equal(q.stats.generated,n);
  q.setSpeed(1.25);assert.equal(q.usage().count,0);
  assert.ok(q.stats.peakSeconds<=30 && q.stats.peakCount<=2 && q.stats.peakBytes<=16*1024*1024);
});
test('超出单缓存预算的音频不会加入预生成缓存',async()=>{
  const e=fakeEngine();e.synthesize=async()=>({samples:new Float32Array(16*1024*1024/4+1),duration:31,elapsedMs:1});
  const q=new TtsScheduler(e);q.reset(segments,'a');q.enabled=true;await q.prefetch(1);assert.equal(q.usage().count,0);
});
test('进度按账户隔离；存储失败不影响阅读；设置参数校验',()=>{
  const map=new Map(),s=new ReaderStorage({getItem:k=>map.get(k),setItem:(k,v)=>map.set(k,v)});
  const a={accountScope:'1',bookId:'9007199254740993'},b={...a,accountScope:'2'};
  s.saveProgress(a,{offset:2});assert.equal(s.progress(a).offset,2);assert.equal(s.progress(b),null);
  const failing=new ReaderStorage({getItem(){throw Error();},setItem(){throw Error();}});
  assert.equal(failing.progress(a),null);assert.equal(failing.saveProgress(a,{}),false);
  assert.equal(sanitize({speed:9,theme:'invalid',fontSize:200}).speed,1);assert.equal(sanitize({fontSize:200}).fontSize,32);
});
test('播放时钟推进，暂停恢复，停止后的 ended 回调不能续播',async()=>{
  let ended=0,created=0;
  class Context {
    currentTime=0;state='running';destination={};
    async resume(){this.state='running';}async suspend(){this.state='suspended';}async close(){this.state='closed';}
    createBuffer(){return {copyToChannel(){}};}
    createBufferSource(){created++;return {connect(){},disconnect(){},start(){},stop(){},buffer:null,onended:null};}
  }
  globalThis.window={AudioContext:Context};
  const p=new AudioPlayer();await p.unlock();p.play({samples:new Float32Array(16000),sampleRate:16000,duration:1},.2,()=>ended++);
  const old=p.source,oldEnded=old.onended;p.context.currentTime=.3;assert.equal(p.position(),.5);
  await p.pause();assert.equal(p.context.state,'suspended');await p.resume();assert.equal(created,1);
  p.stop();oldEnded();assert.equal(ended,0);assert.equal(p.source,null);
  p.play({samples:new Float32Array(16000),sampleRate:16000,duration:1},0,()=>ended++);
  assert.equal(created,2);p.source.onended();assert.equal(ended,1);await p.dispose();
});
