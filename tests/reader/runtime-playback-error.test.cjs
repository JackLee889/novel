const test = require('node:test'), assert = require('node:assert/strict'), vm = require('node:vm'), fs = require('node:fs');
const tick = () => new Promise(resolve => setImmediate(resolve));
function deferred() {let resolve; const promise = new Promise(a => {resolve = a;}); return {promise, resolve};}
const root = 'novel-front/src/main/resources/static/javascript/reader/';
function method(source, name, next) {
  const start = source.indexOf('  ' + name), end = source.indexOf('  ' + next, start);
  assert.ok(start >= 0 && end > start, name + ' must come from actual source'); return source.slice(start, end);
}
function callback(source, marker) {
  const start = source.indexOf(marker), open = source.indexOf('{', start); assert.ok(start >= 0);
  let depth = 0;
  for (let i = open; i < source.length; i++) {
    if (source[i] === '{') depth++;
    if (source[i] === '}' && --depth === 0) return source.slice(start, i + 1);
  }
  throw new Error('Callback must have complete braces');
}
function fixture(kind) {
  const reader = kind === 'reader', source = fs.readFileSync(root + (reader ? 'controller.js' : 'global-player.js'), 'utf8');
  const schedulerSource = fs.readFileSync(root + 'scheduler.js', 'utf8').replace(/^export /gm, '');
  const body = method(source, reader ? 'engineFailed(info)' : 'engineFailed(detail)', reader ? 'fail(error)' : 'save()') +
    (reader ? method(source, 'fail(error)', 'install(chapter') : '') + method(source, 'async play()', 'async step(op)') +
    method(source, 'async step(op)', reader ? 'async pause()' : 'tick()') +
    '  makeCallback() { return ' + callback(source, reader ? '(state, info) => {' : '(state, detail) => {') + '; }';
  const values = {errors: [], saves: [], cancellations: 0, models: 1, readyCalls: 0, plays: 0, fetches: 0};
  const context = {AbortController, DOMException, $: () => ({hidden: false, textContent: ''}),
    activeStates: ['playing', 'preparing', 'buffering'], active: ['playing', 'preparing', 'buffering'],
    cancelAnimationFrame() {}, clearInterval() {}, setTimeout: () => 1, clearTimeout() {}};
  vm.runInNewContext(schedulerSource + '\nclass ActualSession {\n' + body + '\n}\nglobalThis.Session=ActualSession;globalThis.Scheduler=TtsScheduler;', context);
  const chapter = {bookId: '1', bookIndexId: '2', access: 'allowed', contentVersion: 'v1', accountScope: 'anonymous'};
  const audio = {samples: new Float32Array([0.2, 0.3]), duration: 1, sampleRate: 16000, elapsedMs: 1, speed: 1, wasmBytes: 64};
  const pending = deferred(); let hold = true;
  const engine = {state: 'ready', manifest: {modelId: 'matcha-icefall-zh-en', speakerId: 0},
    async ready() {values.readyCalls++; if (this.state === 'error') {values.models++; this.state='ready';} return this.manifest;},
    synthesize: async () => hold ? pending.promise : audio};
  const harness = new context.Session();
  Object.assign(harness, {state: 'playing', wantRun: true, op: 7, index: 0, offset: 0, chapter,
    segments: [0,1,2].map(index => ({text:'片段'+index, segmentId:'s'+index, contentVersion:'v1', chapterId:'2'})),
    ttsEnabled:true, supported:true, settings:{speed:1}, engine, storage:{read:()=>true},
    player:{source:{}, stats:{starts:1}, position:()=>17, async unlock(){}, stop(){this.source=null;}, play(){this.source={}; values.plays++;}},
    repository:{cancel(){values.cancellations++;}, async fetch(){values.fetches++; return chapter;}},
    globalPlayer:{async claim(){}}, host:{last:chapter, async claim(){},changed(){}},
    setState(state,message){this.state=state;this.message=message;}, save(){values.saves.push(this.offset);},
    event(type,data){if(type==='error') values.errors.push(data);}, notice(){}, highlight(){}, tick(){}, expired:()=>false,
    install(){throw new Error('Unexpected chapter replacement');}});
  harness.scheduler = new context.Scheduler(engine); harness.scheduler.reset(harness.segments,'s'); harness.scheduler.enabled=true;
  harness.scheduler.cache.set('old-cache',audio); harness.active=audio;
  const onState = harness.makeCallback();
  return {harness, values, onState, pending, release() {hold=false;pending.resolve(audio);}};
}

for (const kind of ['reader', 'global']) {
  test(kind + ': 实际constructor error订阅立即停止PCM/队列，清缓存保留位置与错误，仅用户play重建', async () => {
    const f=fixture(kind), h=f.harness;
    const oldOp=h.op, stale=h.scheduler.get(1,true).catch(error=>error); await tick();
    assert.equal(h.scheduler.inflight!==null,true); assert.equal(h.scheduler.usage().count,1);
    h.engine.state='error'; f.onState('error',{message:'浏览器内存不足',code:'WASM_OOM',diagnostics:{wasmBytes:64}});
    assert.equal(h.state,'error'); assert.equal(h.message,'浏览器内存不足'); assert.equal(h.wantRun,false);
    assert.equal(h.scheduler.enabled,false); assert.equal(h.scheduler.usage().count,0); assert.equal(h.player.source,null); assert.equal(h.active,null);
    assert.equal(h.offset,17); assert.equal(h.op,oldOp+1); assert.equal(h.lastError.code,'WASM_OOM'); assert.equal(h.lastError.diagnostics.wasmBytes,64);
    assert.equal(f.values.cancellations,1); assert.ok(f.values.saves.includes(17));
    const readyCalls=f.values.readyCalls;
    await h.step(oldOp); await h.scheduler.prefetch(1); f.release();
    assert.equal((await stale).name,'AbortError'); await tick();
    assert.equal(h.scheduler.usage().count,0); assert.equal(f.values.readyCalls,readyCalls); assert.equal(f.values.models,1);
    await h.play();
    assert.equal(f.values.models,2); assert.equal(f.values.fetches,1,'Explicit retry reauthorizes the chapter');
    assert.equal(h.state,'playing'); assert.equal(h.wantRun,true); assert.equal(f.values.plays,1);
  });

  test(kind + ': preparing阶段engine error结束旧await且旧操作不能继续，idle无播放会话不误变更', async () => {
    const f=fixture(kind),h=f.harness;
    h.state='preparing';h.player.source=null;
    const oldOp=h.op;f.onState('error',{message:'模型初始化中止',code:'RUNTIME_ABORT'});
    assert.equal(h.op,oldOp+1);assert.equal(h.state,'error');assert.equal(h.wantRun,false);assert.equal(h.lastError.code,'RUNTIME_ABORT');
    const idle=fixture(kind);idle.harness.state='idle';idle.harness.wantRun=false;idle.harness.player.source=null;
    idle.onState('error',{message:'另一页面引擎已失效'});
    assert.equal(idle.harness.state,'idle');assert.equal(idle.values.cancellations,0);
  });
}
