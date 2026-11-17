const test = require('node:test'), assert = require('node:assert/strict'), vm = require('node:vm'), fs = require('node:fs');
const source = fs.readFileSync('novel-front/src/main/resources/static/javascript/reader/sherpa.worker.js', 'utf8');
const tick = () => new Promise(resolve => setImmediate(resolve));
function fixture(options = {}) {
  const messages = [], listeners = new Map(), revoked = [], engine = {handle: 1, sampleRate: 16000, numSpeakers: 1,
    free() { this.freed = true; }, generate() {
      if (options.synthesisOnAbort) { scope.Module.onAbort('Out of memory: injected during synthesis'); throw new Error('aborted'); }
      if (options.synthesisOOM) throw new Error('Out of memory');
      return {samples: new Float32Array([0.2, -0.2]), sampleRate: 16000};
    }};
  let aborted = false, doneCalls = 0, wasmResolve, wasmReject;
  const wasm = new Promise((resolve, reject) => { wasmResolve = resolve; wasmReject = reject; });
  const scope = {ReaderTtsSharedScope: options.sharedScope === true,
    location: new URL('http://127.0.0.1/javascript/reader/sherpa.worker.js'),
    addEventListener(name, callback) { listeners.set(name, callback); }, removeEventListener(name) { listeners.delete(name); },
    postMessage(value) { messages.push(value); }, close() { this.closed = true; }};
  class ResourceLoader {
    constructor(base) { this.base = base; this.stats = {downloadedAssets: 4}; }
    async load(signal) {
      signal.addEventListener('abort', () => { aborted = true; });
      if (options.stalledLoader) return new Promise((resolve, reject) => {
        signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')));
      });
      return new Map(['sherpa-onnx-tts.js', 'sherpa-onnx-wasm-main-tts.js',
        'sherpa-onnx-wasm-main-tts.wasm', 'sherpa-onnx-wasm-main-tts.data'].map(name => [name, new ArrayBuffer(4)]));
    }
  }
  class BlobUrls extends URL {
    static createObjectURL() { return 'blob:test-' + (++this.sequence); }
    static revokeObjectURL(value) { revoked.push(value); }
  }
  BlobUrls.sequence = 0;
  const context = {self: scope, URL: BlobUrls, Blob, AbortController, DOMException, Float32Array, Uint8Array, console,
    performance: {now: () => 100}, WebAssembly: {instantiate: () => wasm},
    createOfflineTts: () => engine,
    importScripts(...urls) {
      if (urls.length === 1 && urls[0].includes('resource-cache.js')) { scope.ReaderResourceCache = ResourceLoader; return; }
      const module = scope.Module;
      module.getPreloadedPackage('http://127.0.0.1/reader/tts/1.13.8-matcha-icefall-zh-en/sherpa-onnx-wasm-main-tts.data', 4);
      module.instantiateWasm({}, () => { doneCalls++; module.HEAPU8 = new Uint8Array(64); module.onRuntimeInitialized(); });
    }};
  vm.runInNewContext(source, context);
  const importListeners = listeners.size, autoHandler = typeof scope.onmessage;
  let inline;
  if (options.sharedScope) {
    delete scope.postMessage; // Actual SharedWorkerGlobalScope has no postMessage.
    inline = scope.createReaderTtsRuntime(value => messages.push(value));
  }
  const request = {type: 'init', requestId: 1, sessionId: 'test', queueRevision: 1, transportGeneration: 2, runtimeGeneration: 3,
    baseUrl: 'http://127.0.0.1/reader/tts/1.13.8-matcha-icefall-zh-en/', assets: []};
  return {scope, messages, listeners, revoked, engine, request, importListeners, autoHandler,
    send: payload => inline ? inline.postMessage(payload) : scope.onmessage({data: payload}),
    resolve: () => wasmResolve({instance: {}, module: {}}),
    reject: () => wasmReject(new Error('Out of memory: Cannot allocate Wasm memory for new instance')),
    get aborted() { return aborted; }, get doneCalls() { return doneCalls; }};
}

test('实例化OOM只报告一次并取消资源、去掉监听和全局Module引用', async () => {
  const f = fixture(); f.send(f.request); await tick();
  const module = f.scope.Module; f.reject(); await tick();
  const errors = f.messages.filter(message => message.type === 'error');
  assert.equal(errors.length, 1); assert.equal(errors[0].code, 'WASM_OOM'); assert.equal(errors[0].fatal, true);
  assert.equal(errors[0].runtimeGeneration, 3); assert.equal(errors[0].transportGeneration, 2);
  assert.equal(f.scope.Module, null); assert.equal(f.aborted, true); assert.equal(f.listeners.size, 0); assert.equal(f.revoked.length, 2);
  module.onRuntimeInitialized(); assert.equal(f.messages.filter(message => message.type === 'ready').length, 0);
});

test('SharedWorker导入仅提供factory，不安装独立消息入口；使用broker回调可真实初始化与合成', async () => {
  const f = fixture({sharedScope: true});
  assert.equal(f.autoHandler, 'undefined'); assert.equal(f.importListeners, 0); assert.equal(f.scope.postMessage, undefined);
  f.send(f.request); await tick(); f.resolve(); await tick();
  assert.equal(f.messages.find(message => message.type === 'ready').sampleRate, 16000);
  f.send({...f.request, type: 'synthesize', requestId: 2, text: '测试', speed: 1, modelId: 'matcha-icefall-zh-en', speakerId: 0});
  await tick(); assert.equal(f.messages.find(message => message.type === 'audio').samples.length, 2);
  f.send({type: 'dispose'}); assert.equal(f.engine.freed, true);
  assert.equal(f.scope.closed, undefined, 'Broker must close the scope after broadcasting, not the imported native helper');
});

test('加载中dispose使用AbortSignal结束等待，停止后不创建模型或发布错误', async () => {
  const f = fixture({stalledLoader: true}); f.send(f.request); await tick();
  f.send({type: 'dispose'}); await tick();
  assert.equal(f.aborted, true); assert.equal(f.scope.closed, true); assert.equal(f.listeners.size, 0);
  assert.equal(f.messages.filter(message => ['ready', 'error'].includes(message.type)).length, 0);
});

test('实例化尚未完成时dispose，晚到实例不能调用done或恢复ready', async () => {
  const f = fixture(); f.send(f.request); await tick(); f.send({type: 'dispose'}); f.resolve(); await tick();
  assert.equal(f.doneCalls, 0); assert.equal(f.scope.Module, null); assert.equal(f.messages.filter(message => message.type === 'ready').length, 0);
});

test('ready与audio带阶段指标，错误runtime generation不能生成PCM，正常dispose释放模型', async () => {
  const f = fixture(); f.send(f.request); await tick(); f.resolve(); await tick();
  const ready = f.messages.find(message => message.type === 'ready');
  assert.equal(ready.sampleRate, 16000); assert.equal(ready.diagnostics.wasmBytes, 64);
  assert.ok(['resourcesMs', 'instantiateMs', 'modelMs'].every(name => name in ready.diagnostics));
  const synth = {...f.request, type: 'synthesize', requestId: 2, text: '测试', speed: 1,
    modelId: 'matcha-icefall-zh-en', speakerId: 0, segmentId: 'a'};
  f.send({...synth, runtimeGeneration: 99}); await tick(); assert.equal(f.messages.filter(message => message.type === 'audio').length, 0);
  f.send(synth); await tick(); const audio = f.messages.find(message => message.type === 'audio');
  assert.equal(audio.samples.length, 2); assert.equal(audio.transportGeneration, 2); assert.equal(audio.runtimeGeneration, 3);
  assert.ok('firstSynthesisMs' in audio.diagnostics);
  f.send({type: 'dispose'}); assert.equal(f.engine.freed, true); assert.equal(f.scope.Module, null);
});

test('合成内存不足成为fatal错误，释放引擎后不能再次合成', async () => {
  const f = fixture({synthesisOOM: true}); f.send(f.request); await tick(); f.resolve(); await tick();
  const synth = {...f.request, type: 'synthesize', requestId: 2, text: '测试', speed: 1, modelId: 'matcha-icefall-zh-en', speakerId: 0};
  f.send(synth); await tick(); f.send(synth); await tick();
  const errors = f.messages.filter(message => message.type === 'error');
  assert.equal(errors.length, 1); assert.equal(errors[0].code, 'WASM_OOM'); assert.equal(errors[0].fatal, true); assert.equal(f.engine.freed, true);
});

test('运行中Module.onAbort路由当前synthesis请求，而不是已完成的init请求', async () => {
  const f = fixture({synthesisOnAbort: true}); f.send(f.request); await tick(); f.resolve(); await tick();
  f.send({...f.request, type: 'synthesize', requestId: 22, text: '测试', speed: 1, modelId: 'matcha-icefall-zh-en', speakerId: 0});
  await tick(); const errors = f.messages.filter(m => m.type === 'error');
  assert.equal(errors.length, 1); assert.equal(errors[0].requestId, 22); assert.equal(errors[0].fatal, true);
  assert.equal(errors[0].code, 'WASM_OOM'); assert.equal(f.engine.freed, true); assert.equal(f.scope.Module, null);
});

test('ready后异步Module.onAbort仍发布fatal，即使最后请求已完成', async () => {
  const f = fixture(); f.send(f.request); await tick(); f.resolve(); await tick();
  f.scope.Module.onAbort('Out of memory: late abort');
  const error = f.messages.find(m => m.type === 'error');
  assert.equal(error.fatal, true); assert.equal(error.requestId, f.request.requestId); assert.equal(error.code, 'WASM_OOM');
  assert.equal(f.engine.freed, true); assert.equal(f.scope.Module, null);
});
