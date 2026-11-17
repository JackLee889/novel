const test = require('node:test'), assert = require('node:assert/strict');
const vm = require('node:vm'), fs = require('node:fs'), {webcrypto} = require('node:crypto');
const source = fs.readFileSync('novel-front/src/main/resources/static/javascript/reader/global.worker.js', 'utf8');
const tick = () => new Promise(resolve => setImmediate(resolve));
function fixture(options = {}) {
  const workers = [], sharedRuntimes = [], events = [], timers = new Map(), scope = {crypto: webcrypto,
    location: new URL('http://127.0.0.1:18987/javascript/reader/global.worker.js')};
  let running = 0, peak = 0, timerSequence = 0, now = 0;
  class NativeWorker {
    constructor(url, inline = false) {
      if (options.constructorFailure && !inline) throw new Error('nested workers unavailable');
      this.url = url.href; this.calls = []; (inline ? sharedRuntimes : workers).push(this);
    }
    terminate() { this.terminated = true; }
    emit(request, value) { this.onmessage({data: {...request, ...value}}); }
    finish(request) {
      this.emit(request, request.type === 'init' ? {type: 'ready', diagnostics: {wasmBytes: 512 * 1024 * 1024},
        resources: {downloadedAssets: 4, downloadedBytes: 163103220, persistent: true}} :
        {type: 'audio', samples: new Float32Array([1, 2]), sampleRate: 16000});
    }
    postMessage(request) {
      this.calls.push(request);
      if (options.manual) return;
      if (request.type === 'synthesize') { running++; peak = Math.max(peak, running); }
      setTimeout(() => {
        if (request.type === 'synthesize') running--;
        this.finish(request);
      }, 2);
    }
  }
  function schedule(callback, ms, interval = false) {
    const timer = {id: ++timerSequence, callback, ms, due: now + ms, interval, unref() {}};
    timers.set(timer.id, timer); return timer;
  }
  scope.close = () => { events.push('close'); scope.closed = true; };
  vm.runInNewContext(source, {self: scope, URL, DOMException, Date: {now: () => now},
    Worker: options.noWorker ? undefined : NativeWorker,
    importScripts(url) {
      assert.equal(scope.ReaderTtsSharedScope, true);
      assert.match(url, /sherpa.worker.js\?v=20261008.3$/);
      scope.createReaderTtsRuntime = emit => {
        const runtime = new NativeWorker(new URL(url), true);
        runtime.onmessage = ({data}) => emit(data); return runtime;
      };
    },
    setTimeout: (callback, ms) => schedule(callback, ms), clearTimeout: timer => timers.delete(timer?.id),
    setInterval: (callback, ms) => schedule(callback, ms, true), clearInterval: timer => timers.delete(timer?.id)});
  const client = () => {
    const port = {messages: [], postMessage(value) { events.push(value.type); this.messages.push(value); }, start() {}, close() { this.closed = true; }};
    scope.onconnect({ports: [port]});
    port.request = (type, payload = {}) => port.onmessage({data: {type, requestId: 1,
      sessionId: 'test', queueRevision: 1, transportGeneration: 0, ...payload}});
    return port;
  };
  const init = {baseUrl: 'http://127.0.0.1:18987/reader/tts/1.13.8-matcha-icefall-zh-en/', assets: [],
    manifest: {engineVersion: '1.13.8', modelId: 'matcha-icefall-zh-en'}};
  const advance = ms => {
    now += ms;
    for (const timer of [...timers.values()]) {
      if (!timers.has(timer.id) || timer.due > now) continue;
      if (timer.interval) timer.due = now + timer.ms; else timers.delete(timer.id);
      timer.callback();
    }
  };
  return {client, workers, sharedRuntimes, events, scope, init, advance, get peak() { return peak; }};
}

test('两个页面同时初始化只创建一个DedicatedWorker，短暂断开后新页面复用模型', async () => {
  const f = fixture(), a = f.client(), b = f.client();
  await Promise.all([a.request('init', f.init), b.request('init', f.init)]);
  assert.equal(f.workers.length, 1); assert.equal(f.workers[0].calls.filter(x => x.type === 'init').length, 1);
  assert.match(f.workers[0].url, /sherpa.worker.js\?v=20261008.3$/);
  const ready = a.messages.find(x => x.type === 'ready');
  assert.equal(ready.diagnostics.wasmBytes, 512 * 1024 * 1024);
  await a.request('disconnect'); await b.request('disconnect'); f.advance(25000);
  assert.equal(f.workers[0].terminated, undefined);
  const next = f.client(), hello = next.messages[0]; assert.equal(hello.engineId, ready.engineId);
  assert.equal(hello.ready, true); assert.equal(hello.initializations, 1); assert.equal(hello.resources.downloadedBytes, 0);
  f.advance(10000); assert.equal(f.workers[0].terminated, undefined);
});

test('不同页面使用相同请求ID时音频仍独立返回，推理全局串行', async () => {
  const f = fixture(), a = f.client(), b = f.client(); await a.request('init', f.init);
  a.messages.length = 0; b.messages.length = 0;
  await Promise.all([a.request('synthesize', {text: '甲', segmentId: 'a'}), b.request('synthesize', {text: '乙', segmentId: 'b'})]);
  assert.equal(f.peak, 1); assert.equal(a.messages.length, 1); assert.equal(b.messages.length, 1);
  assert.equal(a.messages[0].segmentId, 'a'); assert.equal(b.messages[0].segmentId, 'b');
  assert.equal(a.messages[0].transportGeneration, 0);
});

test('新页面取得播放权会暂停旧页面，旧页面不能覆盖全局位置', async () => {
  const f = fixture(), a = f.client(), b = f.client();
  const playback = {bookId: '123', bookIndexId: '456', accountScope: 'anonymous', contentVersion: 'v1',
    state: 'playing', offset: 5, segmentId: 'p1', paragraphs: [{text: '不可留存正文'}], samples: [1, 2]};
  await a.request('claim'); await a.request('publish', {playback});
  await b.request('claim'); assert.ok(a.messages.some(x => x.type === 'yield'));
  await a.request('publish', {playback: {...playback, offset: 99}});
  await b.request('snapshot');
  const snapshot = b.messages.find(x => x.type === 'snapshot').playback;
  assert.equal(snapshot.offset, 5); assert.ok(!('paragraphs' in snapshot)); assert.ok(!('samples' in snapshot));
  await b.request('disconnect');
});

test('OOM后真的终止旧Worker，晚到ready与旧finally不覆盖立即启动的新代次', async () => {
  const f = fixture({manual: true}), a = f.client();
  const first = a.request('init', f.init), old = f.workers[0], oldRequest = old.calls[0];
  old.emit(oldRequest, {type: 'error', message: '内存不足', code: 'WASM_OOM', fatal: true});
  assert.equal(old.terminated, true);
  const retry = a.request('init', {...f.init, requestId: 2});
  const next = f.workers[1], nextRequest = next.calls[0];
  old.finish(oldRequest); await first; await tick();
  assert.equal(a.messages.filter(m => m.type === 'ready').length, 0);
  const sameBoot = a.request('init', {...f.init, requestId: 3});
  assert.equal(f.workers.length, 2);
  next.finish(nextRequest); await Promise.all([retry, sameBoot]);
  assert.equal(a.messages.filter(m => m.type === 'ready').length, 2);
  assert.ok(a.messages.filter(m => m.type === 'ready').every(m => m.runtimeGeneration === nextRequest.runtimeGeneration));
});

test('失败时结束等待合成，旧排队任务与晚到audio不能向新Worker提交或播放', async () => {
  const f = fixture({manual: true}), a = f.client(), b = f.client();
  const init = a.request('init', f.init); f.workers[0].finish(f.workers[0].calls[0]); await init;
  a.messages.length = 0; b.messages.length = 0;
  const first = a.request('synthesize', {text: '甲', segmentId: 'old-a'});
  const queued = b.request('synthesize', {text: '乙', segmentId: 'old-b'}); await tick();
  const old = f.workers[0], request = old.calls.at(-1);
  old.emit(request, {type: 'error', message: '失败', fatal: true});
  const retry = a.request('init', {...f.init, requestId: 2});
  const next = f.workers[1]; next.finish(next.calls[0]);
  old.finish(request); await Promise.all([first, queued, retry]);
  assert.equal(old.terminated, true); assert.equal(next.calls.length, 1);
  assert.ok(![...a.messages, ...b.messages].some(m => m.type === 'audio'));
  assert.ok(b.messages.some(m => m.type === 'error' && m.segmentId === 'old-b'));
});

test('初始化超时结束全部等待并终止Worker，错误事件不影响下一代', async () => {
  const f = fixture({manual: true}), a = f.client(), b = f.client();
  const first = a.request('init', f.init), second = b.request('init', f.init), old = f.workers[0];
  await a.request('heartbeat'); await b.request('heartbeat');
  f.advance(120000); await Promise.all([first, second]);
  assert.equal(old.terminated, true); assert.ok(a.messages.some(m => m.type === 'error' && /超时/.test(m.message)));
  assert.ok(b.messages.some(m => m.type === 'error' && /超时/.test(m.message)));
  const retry = a.request('init', f.init), next = f.workers[1];
  old.onerror({message: 'old error', preventDefault() {}}); assert.equal(next.terminated, undefined);
  next.finish(next.calls[0]); await retry;
});

test('最后页面离开后宽限期回收WASM，活跃与心跳页面不会回收', async () => {
  const f = fixture(), a = f.client(); await a.request('init', f.init);
  f.advance(30000); assert.equal(f.workers[0].terminated, undefined);
  await a.request('heartbeat', {hidden: true}); f.advance(10 * 60 * 1000);
  assert.equal(a.closed, undefined); assert.equal(f.workers[0].terminated, undefined);
  await a.request('disconnect'); f.advance(30000); assert.equal(f.workers[0].terminated, true);
  const b = f.client(); assert.equal(b.messages[0].ready, false);
  await b.request('init', f.init); assert.equal(f.workers.length, 2);
});

test('Chrome缺少嵌套Worker能力时保留全局共享模型，刷新交接宽限期内复用', async () => {
  for (const options of [{noWorker: true}, {constructorFailure: true}]) {
    const f = fixture(options), a = f.client(), b = f.client();
    await Promise.all([a.request('init', f.init), b.request('init', f.init)]);
    assert.equal(f.workers.length, 0); assert.equal(f.sharedRuntimes.length, 1);
    assert.equal(a.messages.find(m => m.type === 'ready').runtimeMode, 'shared-native');
    await a.request('disconnect'); await b.request('disconnect'); f.advance(20000);
    const next = f.client(); assert.equal(next.messages[0].ready, true); assert.equal(next.messages[0].runtimeMode, 'shared-native');
    await next.request('init', f.init); assert.equal(f.sharedRuntimes.length, 1); assert.equal(f.scope.closed, undefined);
  }
});

test('Chrome共享原生OOM先通知所有等待客户端再真正close整个SharedWorker', async () => {
  const f = fixture({noWorker: true, manual: true}), a = f.client(), b = f.client();
  const first = a.request('init', f.init), second = b.request('init', f.init);
  const runtime = f.sharedRuntimes[0], request = runtime.calls[0];
  runtime.emit(request, {type: 'error', message: '内存不足', code: 'WASM_OOM', fatal: true});
  assert.equal(f.scope.closed, true); assert.equal(runtime.terminated, true); assert.equal(f.workers.length, 0);
  const closeIndex = f.events.indexOf('close');
  assert.equal(f.events.slice(0, closeIndex).filter(event => event === 'engine-error').length, 2);
  await Promise.all([first, second]);
  assert.ok(a.messages.some(m => m.type === 'engine-error' && m.code === 'WASM_OOM'));
  const refused = f.client(); assert.equal(refused.closed, true); assert.ok(!refused.messages.some(m => m.type === 'hello'));
  runtime.finish(request); assert.ok(!a.messages.some(m => m.type === 'ready'));
  const fresh = fixture({noWorker: true}), next = fresh.client(); await next.request('init', fresh.init);
  assert.notEqual(next.messages[0].engineId, a.messages[0].engineId); assert.equal(fresh.scope.closed, undefined);
});

test('Chrome共享原生初始化超时与无客户端闲置都真正close，而不是只free句柄', async () => {
  const timeout = fixture({noWorker: true, manual: true}), a = timeout.client(), pending = a.request('init', timeout.init);
  timeout.advance(120000); await pending;
  assert.equal(timeout.scope.closed, true); assert.ok(a.messages.some(m => m.type === 'engine-error' && /超时/.test(m.message)));
  const idle = fixture({noWorker: true}), b = idle.client(); await b.request('init', idle.init);
  await b.request('disconnect'); idle.advance(29999); assert.equal(idle.scope.closed, undefined);
  idle.advance(1); assert.equal(idle.scope.closed, true); assert.equal(idle.sharedRuntimes[0].terminated, true);
});

test('ready后没有pending的当前代次late fatal仍结束运行时，旧epoch fatal必须忽略', async () => {
  const f = fixture(), a = f.client(); await a.request('init', f.init);
  const worker = f.workers[0], request = worker.calls[0];
  worker.emit({...request, runtimeGeneration: request.runtimeGeneration - 1}, {type: 'error', message: 'old abort', fatal: true});
  assert.equal(worker.terminated, undefined); assert.ok(!a.messages.some(m => m.type === 'engine-error'));
  worker.emit(request, {type: 'error', message: '运行中abort', code: 'WASM_OOM', fatal: true});
  assert.equal(worker.terminated, true); assert.equal(f.workers.length, 1);
  assert.ok(a.messages.some(m => m.type === 'engine-error' && m.code === 'WASM_OOM'));
  await tick(); assert.equal(f.workers.length, 1, 'There is no automatic model replacement');
  const retry = a.request('init', f.init), replacement = f.workers[1];
  worker.emit(request, {type: 'error', message: 'stale old scope', fatal: true});
  assert.equal(replacement.terminated, undefined); await retry;
});

test('Chrome shared-native ready后late fatal先通知客户端再close，无自动新scope或模型', async () => {
  const f = fixture({noWorker: true}), a = f.client(); await a.request('init', f.init);
  const runtime = f.sharedRuntimes[0], request = runtime.calls[0];
  runtime.emit(request, {type: 'error', message: '运行中abort', code: 'WASM_OOM', fatal: true});
  assert.equal(f.scope.closed, true); assert.equal(runtime.terminated, true);
  assert.ok(a.messages.some(m => m.type === 'engine-error' && m.code === 'WASM_OOM'));
  assert.ok(f.events.indexOf('engine-error') < f.events.indexOf('close'));
  await tick(); assert.equal(f.sharedRuntimes.length, 1); assert.equal(f.workers.length, 0);
});
