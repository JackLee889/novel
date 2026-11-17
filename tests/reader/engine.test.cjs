const test = require('node:test'), assert = require('node:assert/strict'), vm = require('node:vm'), fs = require('node:fs');
const raw = fs.readFileSync('novel-front/src/main/resources/static/javascript/reader/engine.js', 'utf8');
const source = raw.replace(/^export /gm, '').replaceAll('import.meta.url', "'http://127.0.0.1/javascript/reader/engine.js'") + '\nglobalThis.SherpaEngine = SherpaEngine;';
const tick = () => new Promise(resolve => setImmediate(resolve));
function fixture(options = {}) {
  const workers = [], sharedWorkers = [], timers = new Map(); let timerSequence = 0;
  const manifest = {baseUrl: 'http://127.0.0.1/reader/tts/1.13.8-matcha-icefall-zh-en/',
    engineVersion: '1.13.8', modelId: 'matcha-icefall-zh-en', speakerId: 0, assets: []};
  class NativeWorker {
    constructor(url) { this.url = url.href; this.calls = []; workers.push(this); }
    terminate() { this.terminated = true; }
    emit(request, value) { this.onmessage({data: {...request, ...value}}); }
    finish(request) {
      this.emit(request, request.type === 'init' ? {type: 'ready', resources: {cacheHits: 4}, runtimeGeneration: request.runtimeGeneration} :
        {type: 'audio', samples: new Float32Array([1]), sampleRate: 16000, diagnostics: {firstSynthesisMs: 1}});
    }
    postMessage(request) { this.calls.push(request); if (!options.manual) setImmediate(() => this.finish(request)); }
  }
  class SharedTransport {
    constructor(url, config) {
      this.url = url.href; this.config = config; sharedWorkers.push(this);
      this.port = {calls: [], close() { this.closed = true; },
        postMessage: request => {
          this.port.calls.push(request);
          if (['heartbeat', 'disconnect', 'publish'].includes(request.type)) return;
          setImmediate(() => {
            if (request.type === 'init' && options.oom)
              this.emit(request, {type: 'error', code: 'WASM_OOM', fatal: true, message: '内存不足'});
            else this.emit(request, {type: request.type === 'init' ? 'ready' : request.type === 'snapshot' ? 'snapshot' : 'claimed',
              resources: {cacheHits: 4}, manifest, runtimeGeneration: 5, initializations: 1,
              runtimeMode: options.noNested || options.nestedConstructorFailure ? 'shared-native' : 'nested-dedicated'});
          });
        },
        start: () => setImmediate(() => this.port.onmessage({data: {type: 'hello', nestedWorkerAvailable: !options.noNested,
          ready: options.warm === true, resources: {cacheHits: 4}, manifest, initializations: 1, runtimeGeneration: 5,
          clientId: 'shared-client', engineId: 'shared-engine', runtimeMode: options.noNested ? 'shared-native' : 'nested-dedicated'}}))};
    }
    emit(request, value) { this.port.onmessage({data: {...request, ...value}}); }
  }
  function schedule(callback, ms) { const timer = {id: ++timerSequence, callback, ms, unref() {}}; timers.set(timer.id, timer); return timer; }
  const context = {URL, DOMException, AbortController, Map, Set, location: new URL('http://127.0.0.1'), crossOriginIsolated: false,
    Worker: NativeWorker, SharedWorker: options.shared ? SharedTransport : undefined,
    fetch: async () => ({ok: true, json: async () => manifest}),
    setTimeout: schedule, clearTimeout: timer => timers.delete(timer?.id),
    setInterval: schedule, clearInterval: timer => timers.delete(timer?.id)};
  vm.runInNewContext(source, context);
  return {engine: new context.SherpaEngine('/reader/tts-manifest.json'), workers, sharedWorkers, manifest, timers};
}

test('无SharedWorker时DedicatedWorker可加载与合成，dispose真正terminate并忽略晚到消息', async () => {
  const f = fixture(), engine = f.engine; await engine.ready();
  assert.equal(f.workers.length, 1); assert.equal(engine.shared, undefined);
  const audio = await engine.synthesize({text: '甲', segmentId: 'a'}, 'session', 1, 1);
  assert.equal(audio.sampleRate, 16000); assert.equal(engine.diagnostics.firstSynthesisMs, 1);
  const worker = f.workers[0], request = worker.calls[0]; engine.dispose();
  assert.equal(worker.terminated, true); worker.finish(request); assert.equal(engine.state, 'disposed');
});

test('Chrome缺少嵌套Worker仍使用共享原生模型，snapshot/ready并行不会悬挂', async () => {
  for (const options of [{shared: true, noNested: true}, {shared: true, nestedConstructorFailure: true}]) {
    const f = fixture(options); await f.engine.ready();
    assert.equal(f.workers.length, 0); assert.equal(f.sharedWorkers[0].port.closed, undefined);
    assert.equal(f.engine.preferShared, true); assert.equal(f.engine.shared, true); assert.equal(f.engine.runtimeMode, 'shared-native');
    assert.equal(f.engine.clientId, 'shared-client');
    f.engine.dispose();
  }
  const f = fixture({shared: true}); const snapshot = f.engine.snapshot(), ready = f.engine.ready();
  await Promise.all([snapshot, ready]); assert.equal(f.workers.length, 0); assert.equal(f.engine.shared, true);
  assert.equal(f.engine.runtimeGeneration, 5); f.engine.dispose();
});

test('共享模型OOM不创建第二个降级模型，三次失败后拒绝无限重试', async () => {
  const f = fixture({shared: true, oom: true});
  for (let attempt = 0; attempt < 3; attempt++) await assert.rejects(f.engine.ready(), error => error.code === 'WASM_OOM');
  assert.equal(f.engine.memoryFailures, 3); assert.equal(f.workers.length, 0); assert.equal(f.sharedWorkers.length, 3);
  await assert.rejects(f.engine.ready(), error => error.code === 'WASM_OOM_RETRY_LIMIT');
  assert.equal(f.sharedWorkers.length, 3); assert.ok(f.sharedWorkers.every(worker => worker.port.closed));
});

test('DedicatedWorker初始化失败后立即重试，旧finally和旧ready不能清除新初始化任务', async () => {
  const f = fixture({manual: true}), first = f.engine.ready().catch(error => error);
  await tick(); const old = f.workers[0], oldRequest = old.calls[0];
  old.emit(oldRequest, {type: 'error', message: '内存不足', code: 'WASM_OOM', fatal: true});
  const retry = f.engine.ready(); const operation = f.engine.initializing;
  old.finish(oldRequest); await first; await tick();
  assert.equal(f.engine.initializing, operation); assert.equal(f.engine.state, 'loading');
  assert.equal(old.terminated, true); assert.equal(f.workers.length, 2);
  const next = f.workers[1]; next.finish(next.calls[0]); await retry;
  assert.equal(f.engine.state, 'ready'); assert.equal(f.engine.initializing, null);
});

test('旧transport generation与错误queue revision不能完成当前请求', async () => {
  const f = fixture({manual: true}), ready = f.engine.ready(); await tick();
  const worker = f.workers[0], request = worker.calls[0];
  worker.emit(request, {type: 'ready', resources: {}, transportGeneration: request.transportGeneration + 1});
  worker.emit(request, {type: 'ready', resources: {}, queueRevision: request.queueRevision + 1});
  assert.equal(f.engine.pending.size, 1); assert.equal(f.engine.state, 'loading');
  worker.finish(request); await ready; f.engine.dispose();
});

test('Dedicated ready后late fatal无需pending仍终止模型，旧transport/runtime代次不得误杀', async () => {
  const f = fixture(), engine = f.engine; await engine.ready();
  const worker = f.workers[0], request = worker.calls[0];
  worker.emit(request, {type: 'error', fatal: true, code: 'WASM_OOM', message: 'stale', transportGeneration: request.transportGeneration - 1});
  worker.emit(request, {type: 'error', fatal: true, code: 'WASM_OOM', message: 'stale', runtimeGeneration: request.runtimeGeneration - 1});
  assert.equal(worker.terminated, undefined); assert.equal(engine.state, 'ready');
  worker.emit(request, {type: 'error', fatal: true, code: 'WASM_OOM', message: 'late abort'});
  assert.equal(worker.terminated, true); assert.equal(engine.state, 'error'); assert.equal(engine.memoryFailures, 1);
  assert.equal(engine.pending.size, 0); await tick(); assert.equal(f.workers.length, 1);
  await engine.ready(); assert.equal(f.workers.length, 2); engine.dispose();
});
