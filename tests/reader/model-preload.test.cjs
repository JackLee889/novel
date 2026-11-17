const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '../..');
const raw = fs.readFileSync(path.join(root,
  'novel-front/src/main/resources/static/javascript/reader/model-preload.js'), 'utf8');
const context = {WeakMap, Promise};
vm.runInNewContext(raw.replace(/^export /gm, '') +
  '\nglobalThis.scheduleModelPreload = scheduleModelPreload;', context);
const scheduleModelPreload = context.scheduleModelPreload;
const tick = () => new Promise(resolve => setImmediate(resolve));

function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return {promise, resolve, reject};
}

function environment(options = {}) {
  const listeners = new Map(), idle = new Map(), timers = new Map();
  let sequence = 0;
  const window = {
    addEventListener(type, callback, config) {
      const values = listeners.get(type) || [];
      values.push({callback, once: config?.once === true}); listeners.set(type, values);
    },
    removeEventListener(type, callback) {
      listeners.set(type, (listeners.get(type) || []).filter(item => item.callback !== callback));
    },
  };
  const env = {
    document: {readyState: options.loaded ? 'complete' : 'loading'}, window,
    Worker: options.noWorker ? undefined : class {},
    WebAssembly: options.noWasm ? undefined : {},
    AudioContext: class { constructor() { throw new Error('preload created audio output'); } },
    requestIdleCallback: options.noIdle ? undefined : (callback, config) => {
      const id = ++sequence; idle.set(id, {callback, config}); return id;
    },
    cancelIdleCallback(id) { idle.delete(id); },
    setTimeout(callback, ms) { const id = ++sequence; timers.set(id, {callback, ms}); return id; },
    clearTimeout(id) { timers.delete(id); },
  };
  return {env, idle, timers,
    emit(type, properties = {}) {
      for (const item of [...(listeners.get(type) || [])]) {
        if (item.once) window.removeEventListener(type, item.callback);
        item.callback({type, ...properties});
      }
    },
    load() { env.document.readyState = 'complete'; this.emit('load'); },
    runIdle() {
      assert.equal(idle.size, 1);
      const [id, item] = idle.entries().next().value; idle.delete(id);
      item.callback({didTimeout: false, timeRemaining: () => 30});
    },
    runTimer() {
      assert.equal(timers.size, 1);
      const [id, item] = timers.entries().next().value; timers.delete(id); item.callback();
    },
  };
}

function engineFixture() {
  const pending = deferred();
  const engine = {state: 'idle', initializing: null, calls: 0,
    ready() {
      this.calls++; this.state = 'loading'; this.initializing = pending.promise;
      pending.promise.then(() => { this.state = 'ready'; this.initializing = null; },
        () => { this.state = 'error'; this.initializing = null; });
      return pending.promise;
    },
    synthesize() { throw new Error('preload synthesized audio'); },
    play() { throw new Error('preload started playback'); },
  };
  return {engine, pending};
}

test('load前不准备模型，load后通过idle预约一次且有超时上界', async () => {
  const f = environment(), {engine, pending} = engineFixture();
  const handle = scheduleModelPreload(engine, f.env);
  assert.equal(handle.state, 'waiting-load'); assert.equal(engine.calls, 0);
  assert.equal(f.idle.size, 0); assert.equal(f.timers.size, 0);
  f.load(); assert.equal(engine.calls, 0); assert.equal(handle.state, 'scheduled');
  assert.equal(f.idle.values().next().value.config.timeout, 1000);
  f.runIdle(); await tick(); assert.equal(engine.calls, 1);
  pending.resolve({modelId: 'synthetic'}); await handle.promise;
  assert.equal(handle.state, 'ready');
  f.emit('load'); scheduleModelPreload(engine, f.env); await tick();
  assert.equal(engine.calls, 1); assert.equal(f.idle.size, 0);
});

test('已load且无requestIdleCallback时用异步timer准备，重复预约返回同handle', async () => {
  const f = environment({loaded: true, noIdle: true}), {engine, pending} = engineFixture();
  const first = scheduleModelPreload(engine, f.env), second = scheduleModelPreload(engine, f.env);
  assert.equal(first, second); assert.equal(engine.calls, 0); assert.equal(f.timers.size, 1);
  assert.equal(f.timers.values().next().value.ms, 0);
  f.runTimer(); await tick(); assert.equal(engine.calls, 1);
  pending.resolve({}); await first.promise; assert.equal(first.state, 'ready');
});

test('后台预加载与真实SherpaEngine播放ready并发仍只读取一份manifest和初始化一次', async () => {
  const f = environment({loaded: true}), workers = []; let fetches = 0;
  const manifest = {baseUrl: 'http://127.0.0.1/reader/tts/1.13.8-matcha-icefall-zh-en/',
    engineVersion: '1.13.8', modelId: 'matcha-icefall-zh-en', speakerId: 0, assets: []};
  class Worker {
    constructor() { this.calls = []; workers.push(this); }
    postMessage(request) { this.calls.push(request); }
    terminate() { this.terminated = true; }
  }
  const engineSource = fs.readFileSync(path.join(root,
    'novel-front/src/main/resources/static/javascript/reader/engine.js'), 'utf8')
    .replace(/^export /gm, '').replaceAll('import.meta.url', "'http://127.0.0.1/javascript/reader/engine.js'");
  const engineContext = {URL, DOMException, AbortController, Map, Set, location: new URL('http://127.0.0.1'),
    crossOriginIsolated: false, Worker, SharedWorker: undefined,
    fetch: async () => { fetches++; return {ok: true, json: async () => manifest}; },
    setTimeout: f.env.setTimeout, clearTimeout: f.env.clearTimeout,
    setInterval: f.env.setTimeout, clearInterval: f.env.clearTimeout};
  vm.runInNewContext(engineSource + '\nglobalThis.SherpaEngine = SherpaEngine;', engineContext);
  const engine = new engineContext.SherpaEngine('/reader/tts-manifest.json');
  const handle = scheduleModelPreload(engine, f.env); f.runIdle(); await tick();
  const playPreparation = engine.ready();
  assert.equal(scheduleModelPreload(engine, f.env), handle);
  assert.equal(fetches, 1); assert.equal(workers.length, 1);
  const worker = workers[0], request = worker.calls[0];
  assert.deepEqual(worker.calls.map(item => item.type), ['init']);
  worker.onmessage({data: {...request, type: 'ready', resources: {cacheHits: 4}}});
  await Promise.all([handle.promise, playPreparation]);
  assert.equal(handle.state, 'ready'); assert.equal(engine.initializations, 1);
  assert.equal(fetches, 1); assert.equal(workers.length, 1);
  engine.dispose();
});

test('已有初始化任务直接沿用promise，已ready模型不再次调用ready', async () => {
  const f = environment({loaded: true}), pending = deferred();
  const engine = {state: 'loading', initializing: pending.promise,
    ready() { throw new Error('existing initialization restarted'); }};
  const handle = scheduleModelPreload(engine, f.env);
  if (f.idle.size) f.runIdle(); await tick();
  pending.resolve({}); await handle.promise; assert.equal(handle.state, 'ready');
  const warm = {state: 'ready', ready() { throw new Error('warm model reinitialized'); }};
  const ready = scheduleModelPreload(warm, f.env);
  if (f.idle.size) f.runIdle(); await ready.promise;
  assert.equal(ready.state, 'ready'); assert.equal(f.idle.size, 0);
});

test('后台reject和同步异常均被捕获，不出现unhandled或自动重试/播放', async () => {
  const unhandled = []; const listener = error => unhandled.push(error);
  process.on('unhandledRejection', listener);
  try {
    for (const sync of [false, true]) {
      const f = environment({loaded: true}); let calls = 0;
      const error = new Error('synthetic model failure');
      const engine = {state: 'idle', ready() { calls++; if (sync) throw error; return Promise.reject(error); },
        play() { throw new Error('preload played'); }, synthesize() { throw new Error('preload synthesized'); }};
      const handle = scheduleModelPreload(engine, f.env); f.runIdle();
      const result = await handle.promise; await tick();
      assert.equal(result.ok, false); assert.equal(handle.state, 'failed'); assert.equal(handle.error, error);
      engine.state = 'disposed'; f.emit('pageshow', {persisted: true}); f.emit('load');
      assert.equal(scheduleModelPreload(engine, f.env), handle); await tick();
      assert.equal(calls, 1); assert.equal(f.idle.size, 0); assert.equal(f.timers.size, 0);
    }
    assert.deepEqual(unhandled, []);
  } finally { process.removeListener('unhandledRejection', listener); }
});

test('缺少Worker或WebAssembly时跳过，预加载不需要创建音频上下文', async () => {
  for (const unsupported of [{noWorker: true}, {noWasm: true}]) {
    const f = environment({loaded: true, ...unsupported}), {engine} = engineFixture();
    const handle = scheduleModelPreload(engine, f.env);
    assert.equal(handle.state, 'unsupported'); f.emit('load'); f.emit('pageshow', {persisted: true}); await tick();
    assert.equal(engine.calls, 0); assert.equal(f.idle.size, 0); assert.equal(f.timers.size, 0);
  }
  const f = environment({loaded: true}), {engine, pending} = engineFixture();
  delete f.env.AudioContext;
  const handle = scheduleModelPreload(engine, f.env); f.runIdle(); pending.resolve({}); await handle.promise;
  assert.equal(engine.calls, 1);
});

test('pagehide取消未启动任务，pageshow恢复一次预约', async () => {
  for (const noIdle of [false, true]) {
    const f = environment({loaded: true, noIdle}), {engine, pending} = engineFixture();
    const handle = scheduleModelPreload(engine, f.env);
    f.emit('pagehide', {persisted: true}); assert.equal(handle.state, 'cancelled');
    assert.equal(f.idle.size, 0); assert.equal(f.timers.size, 0); assert.equal(engine.calls, 0);
    f.emit('pageshow', {persisted: true}); f.emit('pageshow', {persisted: true});
    assert.equal(f.idle.size + f.timers.size, 1);
    noIdle ? f.runTimer() : f.runIdle(); pending.resolve({}); await handle.promise;
    assert.equal(engine.calls, 1); assert.equal(handle.state, 'ready');
  }
});

test('BFCache返回时已dispose的模型重新预约，正常ready页面不重复初始化', async () => {
  const f = environment({loaded: true}); let calls = 0;
  const engine = {state: 'idle', ready() { calls++; this.state = 'ready'; return Promise.resolve({}); }};
  const handle = scheduleModelPreload(engine, f.env); f.runIdle(); await handle.promise;
  f.emit('pageshow', {persisted: true}); assert.equal(f.idle.size, 0); assert.equal(calls, 1);
  engine.state = 'disposed'; f.emit('pageshow', {persisted: true});
  assert.equal(f.idle.size, 1); f.runIdle(); await handle.promise;
  assert.equal(calls, 2); assert.equal(handle.state, 'ready');
});

test('idle回调和ready微任务之间离开并dispose页面不会重启模型', async () => {
  const f = environment({loaded: true}); let calls = 0;
  const engine = {state: 'idle',
    ready() { calls++; this.state = 'ready'; return Promise.resolve({}); },
    dispose() { this.state = 'disposed'; }};
  const handle = scheduleModelPreload(engine, f.env);
  f.runIdle();
  assert.equal(calls, 0); // The engine invocation is still queued as a microtask.
  f.emit('pagehide', {persisted: true}); engine.dispose();
  await tick(); if (handle.promise) await handle.promise;
  assert.equal(calls, 0); assert.equal(engine.state, 'disposed');
  assert.equal(f.idle.size, 0); assert.equal(f.timers.size, 0);
});

test('用户播放初始化先失败时idle预加载不得自动ready重试', async () => {
  const f = environment({loaded: true}), {engine, pending} = engineFixture();
  const handle = scheduleModelPreload(engine, f.env);
  const userAttempt = engine.ready();
  const handledUserAttempt = userAttempt.catch(() => {});
  pending.reject(new Error('user model initialization failed'));
  await handledUserAttempt; await tick();
  assert.equal(engine.state, 'error'); assert.equal(engine.initializing, null); assert.equal(engine.calls, 1);
  f.runIdle(); await tick(); if (handle.promise) await handle.promise;
  assert.equal(engine.calls, 1); assert.equal(engine.state, 'error');
  f.emit('pageshow', {persisted: true}); scheduleModelPreload(engine, f.env); await tick();
  assert.equal(engine.calls, 1); assert.equal(f.idle.size, 0); assert.equal(f.timers.size, 0);
});

test('BFCache先恢复再结算旧任务，仅页面释放AbortError可以重新预约', async () => {
  for (const aborted of [true, false]) {
    const f = environment({loaded: true}), old = deferred(), next = deferred(); let calls = 0;
    const engine = {state: 'idle', initializing: null,
      ready() {
        calls++; this.state = 'loading'; this.initializing = calls === 1 ? old.promise : next.promise;
        const operation = this.initializing;
        operation.then(() => {
          if (this.initializing === operation) { this.state = 'ready'; this.initializing = null; }
        }, () => {
          if (this.initializing === operation) { this.state = 'error'; this.initializing = null; }
        });
        return operation;
      },
      dispose() { this.state = 'disposed'; this.initializing = null; },
    };
    const handle = scheduleModelPreload(engine, f.env); f.runIdle(); await tick();
    assert.equal(calls, 1); assert.equal(handle.state, 'loading');
    f.emit('pagehide', {persisted: true}); engine.dispose();
    f.emit('pageshow', {persisted: true});
    assert.equal(f.idle.size, 0); // Wait for the old task before deciding whether to retry.
    old.reject(aborted ? new DOMException('page released', 'AbortError') : new Error('model OOM'));
    await tick();
    if (aborted) {
      assert.equal(f.idle.size, 1); f.runIdle(); await tick(); assert.equal(calls, 2);
      next.resolve({}); await handle.promise; assert.equal(handle.state, 'ready');
    } else {
      assert.equal(f.idle.size, 0); assert.equal(calls, 1); assert.equal(handle.state, 'failed');
      f.emit('pageshow', {persisted: true}); await tick(); assert.equal(f.idle.size, 0);
    }
  }
});
