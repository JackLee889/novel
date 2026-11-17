const test = require('node:test'), assert = require('node:assert/strict'), vm = require('node:vm'), fs = require('node:fs');
const tick = () => new Promise(resolve => setImmediate(resolve));
function deferred() { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return {promise, resolve, reject}; }
function actualPlay(file) {
  const source = fs.readFileSync(file, 'utf8'), start = source.indexOf('  async play() {');
  const end = source.indexOf('  async step(op) {', start);
  assert.ok(start >= 0 && end > start, 'Actual play method must be present');
  return source.slice(start, end);
}
const methods = {
  reader: actualPlay('novel-front/src/main/resources/static/javascript/reader/controller.js'),
  global: actualPlay('novel-front/src/main/resources/static/javascript/reader/global-player.js')
};
function fixture(kind, options = {}) {
  const timers = new Map(), model = deferred(), unlock = deferred(), events = [], failures = [];
  let now = 0, sequence = 0, authorizationSignal, modelReached = false, unlockCalls = 0;
  const context = {AbortController, DOMException, $: () => ({hidden: false}),
    setTimeout(callback, ms) { const id = ++sequence; timers.set(id, {callback, due: now + ms}); return id; },
    clearTimeout(id) { timers.delete(id); }};
  vm.runInNewContext('class ActualPlay {\n' + methods[kind] + '\n}; globalThis.play = ActualPlay.prototype.play;', context);
  const chapter = {bookId: '1', bookIndexId: '2', access: 'allowed', contentVersion: 'v1', accountScope: 'anonymous'};
  const harness = {play: context.play, op: 0, wantRun: false, ttsEnabled: true, supported: true,
    chapter, segments: [{segmentId: 'a'}], index: 0, offset: 0, settings: {speed: 1}, storage: {read: () => true},
    engine: {state: 'error', lastProgress: {}, ready() { modelReached = true; events.push('model'); return model.promise; }},
    scheduler: {enabled: false, prefetch() {}}, host: {last: {...chapter}, claim: async () => {events.push('claim');}},
    globalPlayer: {claim: async () => {events.push('claim');}}, player: {source: null,
      async unlock() { if (options.delayUnlock && ++unlockCalls === 1) await unlock.promise; }, stop() {}},
    expired: () => false,
    setState(state, message) { this.state = state; this.message = message; },
    fail(error) { failures.push(error); this.state = 'error'; this.message = error.message; },
    async step() { this.setState('playing'); }, notice() {}, install() {throw new Error('Unexpected chapter replacement');},
    repository: {async fetch(bookId, chapterId, signal) {
      events.push('fetch'); authorizationSignal = signal;
      if (options.permissionWait) return new Promise((resolve, reject) => {
        signal.addEventListener('abort', () => reject(new DOMException('The operation was aborted', 'AbortError')), {once: true});
      });
      return chapter;
    }} };
  const advance = ms => { now += ms; for (const [id, timer] of [...timers]) if (timer.due <= now) {timers.delete(id); timer.callback();} };
  const cancel = () => {harness.wantRun = false; harness.op++; (harness.playAbort || harness.abort)?.abort(); harness.setState('paused', '用户已暂停');};
  return {harness, advance, model, unlock, events, failures, timers, cancel,
    get signal() {return authorizationSignal;}, get modelReached() {return modelReached;}};
}

for (const kind of ['reader', 'global']) {
  test(kind + ': 权限成功后立即清计时，延迟模型OOM保留真实错误与WASM_OOM code', async () => {
    const f = fixture(kind, {delayUnlock: true}), task = f.harness.play(); await tick();
    assert.equal(f.timers.size, 0, 'Audio unlock is outside the permission deadline');
    f.advance(30000); assert.equal(f.signal, undefined); f.unlock.resolve(); await tick();
    assert.equal(f.modelReached, true); assert.deepEqual(f.events, ['claim', 'fetch', 'model']);
    assert.equal(f.timers.size, 0, 'Authorization timer must be cleared before engine.ready');
    f.advance(20000); assert.equal(f.signal.aborted, false, 'Slow model cannot abort the completed authorization');
    const oom = Object.assign(new Error('浏览器内存不足，语音模型加载失败'), {code: 'WASM_OOM', diagnostics: {instantiateMs: 20000}});
    f.harness.engine.lastProgress = {message: oom.message, code: oom.code, diagnostics: oom.diagnostics};
    f.model.reject(oom); await task;
    assert.equal(f.harness.message, oom.message); assert.equal(f.harness.engine.lastProgress.code, 'WASM_OOM');
    if (kind === 'reader') {assert.equal(f.failures[0], oom); assert.equal(f.failures[0].code, 'WASM_OOM');}
    assert.equal(f.timers.size, 0);
  });

  test(kind + ': 真正权限请求超过15秒报告章节校验超时，不启动模型', async () => {
    const f = fixture(kind, {permissionWait: true}), task = f.harness.play(); await tick();
    assert.equal(f.timers.size, 1); assert.equal(f.signal.aborted, false);
    f.advance(14999); await tick(); assert.equal(f.signal.aborted, false);
    f.advance(1); await task;
    assert.equal(f.signal.aborted, true); assert.equal(f.modelReached, false);
    assert.equal(f.harness.message, '章节校验超时，请重试'); assert.equal(f.timers.size, 0);
  });

  test(kind + ': 用户暂停或停止取消权限请求，不伪报超时且结束后清除计时', async () => {
    const f = fixture(kind, {permissionWait: true}), task = f.harness.play(); await tick();
    f.advance(1000); f.cancel(); await task;
    assert.equal(f.signal.aborted, true); assert.equal(f.modelReached, false); assert.equal(f.failures.length, 0);
    assert.equal(f.harness.message, '用户已暂停'); assert.equal(f.harness.state, 'paused'); assert.equal(f.timers.size, 0);
    f.advance(20000); assert.equal(f.harness.message, '用户已暂停');
  });
}
