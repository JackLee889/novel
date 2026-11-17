/* DedicatedWorker native runtime. Destroy the worker to release its WASM heap. */
self.createReaderTtsRuntime = function(postMessage) {
  let engine, initRequest, activeRequest, disposed = false, module, files, dataPackage, wasmBinary;
  let abort, scripts = [], diagnostics = {}, stageStart, started;
  const elapsed = () => Math.round(performance.now() - started);
  const memory = () => ({wasmBytes: module?.HEAPU8?.buffer?.byteLength || 0,
    jsHeapBytes: performance.memory?.usedJSHeapSize || null});
  const emit = (request, type, payload = {}) => { if (!disposed) postMessage({
    requestId: request.requestId, sessionId: request.sessionId, runtimeGeneration: request.runtimeGeneration,
    transportGeneration: request.transportGeneration, queueRevision: request.queueRevision,
    segmentId: request.segmentId, type, ...payload
  }, payload.samples ? [payload.samples.buffer] : []); };
  const dispose = () => {
    if (disposed) return;
    disposed = true; abort?.abort();
    try { engine?.free(); } catch {}
    engine = null; files?.clear(); files = null; dataPackage = null; wasmBinary = null;
    for (const url of scripts) URL.revokeObjectURL(url);
    scripts = [];
    if (self.Module === module) self.Module = null;
    module = null; activeRequest = null; initRequest = null;
    self.removeEventListener('error', onError);
    self.removeEventListener('unhandledrejection', onRejection);
  };
  const fail = (request, error, fatal = false) => {
    const message = String(error?.message || error);
    const oom = /out of memory|cannot allocate.*memory|memory allocation|insufficient memory/i.test(message);
    emit(request, 'error', {message: oom ? '浏览器内存不足，语音模型加载失败。请关闭其他页面后重试。' : message,
      code: oom ? 'WASM_OOM' : undefined, fatal: fatal || oom, diagnostics: {...diagnostics, ...memory(), elapsedMs: elapsed()}});
    if (fatal || oom) dispose();
  };
  // Emscripten may throw asynchronously after onAbort. Fail once and abort the
  // resource loader; the owning broker/page then really terminates this worker.
  const onError = event => {
    if (activeRequest) { event.preventDefault(); fail(activeRequest, new Error(event.message || '语音资源加载失败'), true); }
  };
  const onRejection = event => {
    if (activeRequest) { event.preventDefault(); fail(activeRequest, event.reason || new Error('语音资源加载失败'), true); }
  };
  self.addEventListener('error', onError);
  self.addEventListener('unhandledrejection', onRejection);
  const stage = (request, phase, message) => emit(request, 'progress', {phase, message,
    diagnostics: {...diagnostics, ...memory(), elapsedMs: elapsed()}});
  const handle = async ({data: request}) => {
    if (request.type === 'dispose') { dispose(); return; }
    if (disposed) return;
    if (request.type === 'init') {
      if (initRequest) { fail(request, new Error('引擎已初始化')); return; }
      initRequest = activeRequest = request; abort = new AbortController(); started = performance.now();
      try {
        const base = new URL(request.baseUrl, self.location.href);
        if (base.origin !== self.location.origin) throw new Error('语音资源必须同源');
        importScripts(new URL('./resource-cache.js?v=20261008.1', self.location.href).href);
        const resources = new self.ReaderResourceCache(base.href, request.assets,
          detail => emit(request, 'progress', {...detail, diagnostics: {...diagnostics, ...memory(), elapsedMs: elapsed()}}));
        files = await resources.load(abort.signal);
        if (disposed) { files.clear(); files = null; return; }
        diagnostics.resourcesMs = elapsed();
        dataPackage = files.get('sherpa-onnx-wasm-main-tts.data');
        wasmBinary = files.get('sherpa-onnx-wasm-main-tts.wasm');
        scripts = ['sherpa-onnx-tts.js', 'sherpa-onnx-wasm-main-tts.js'].map(name =>
          URL.createObjectURL(new Blob([files.get(name)], {type: 'application/javascript'})));
        files.clear(); files = null;
        module = self.Module = {
          locateFile: name => new URL(name, base).href,
          getPreloadedPackage(name, size) {
            if (name !== new URL('sherpa-onnx-wasm-main-tts.data', base).href ||
                !dataPackage || dataPackage.byteLength !== size) throw new Error('语音模型数据不匹配');
            const buffer = dataPackage; dataPackage = null; return buffer;
          },
          instantiateWasm(imports, done) {
            stageStart = performance.now(); stage(request, 'instantiate', '正在创建语音执行环境');
            WebAssembly.instantiate(wasmBinary, imports).then(({instance, module: compiled}) => {
              wasmBinary = null;
              if (disposed) return;
              diagnostics.instantiateMs = Math.round(performance.now() - stageStart);
              done(instance, compiled);
            }).catch(error => { wasmBinary = null; fail(request, error, true); });
            return {};
          },
          setStatus(status) {
            if (status) stage(request, 'initialize', '正在初始化语音模型');
          },
          print() {}, printErr(message) { console.warn(message); },
          onAbort(message) { fail(activeRequest || initRequest || request, new Error('语音引擎中止：' + message), true); },
          onRuntimeInitialized() {
            if (disposed) return;
            try {
              const modelStart = performance.now(); stage(request, 'model', '正在创建语音模型');
              engine = createOfflineTts(module);
              if (!engine || !engine.handle || engine.sampleRate < 8000) throw new Error('模型初始化失败');
              diagnostics.modelMs = Math.round(performance.now() - modelStart);
              emit(request, 'ready', {sampleRate: engine.sampleRate, speakers: engine.numSpeakers,
                elapsedMs: elapsed(), resources: resources.stats, diagnostics: {...diagnostics, ...memory()}});
            } catch (error) { fail(request, error, true); }
          }
        };
        try { importScripts(...scripts); }
        finally { for (const url of scripts) URL.revokeObjectURL(url); scripts = []; }
      } catch (error) { if (!disposed) fail(request, error, true); }
      return;
    }
    if (request.type === 'synthesize') {
      activeRequest = request;
      const start = performance.now();
      try {
        if (!engine) throw new Error('语音模型尚未就绪');
        if (request.runtimeGeneration !== initRequest.runtimeGeneration) throw new DOMException('语音运行时已更换', 'AbortError');
        if (request.modelId !== 'matcha-icefall-zh-en' || request.speakerId !== 0 ||
            ![0.8, 1, 1.25, 1.5].includes(request.speed)) throw new Error('语音参数无效');
        if (typeof request.text !== 'string' || !request.text.trim() || Array.from(request.text).length > 180) throw new Error('合成文本无效');
        const audio = engine.generate({text: request.text, sid: request.speakerId, speed: request.speed});
        // Upstream generate copies HEAPF32 and destroys its native audio handle.
        const samples = audio.samples;
        if (!(samples instanceof Float32Array) || samples.length === 0 ||
            !samples.every(Number.isFinite) || audio.sampleRate < 8000) throw new Error('语音输出无效');
        if (diagnostics.firstSynthesisMs === undefined) diagnostics.firstSynthesisMs = Math.round(performance.now() - start);
        emit(request, 'audio', {samples, sampleRate: audio.sampleRate,
          duration: samples.length / audio.sampleRate, elapsedMs: performance.now() - start,
          wasmBytes: module.HEAPU8.buffer.byteLength, speed: request.speed, diagnostics: {...diagnostics, ...memory()}});
      } catch (error) { fail(request, error); }
    }
  };
  return {postMessage: request => handle({data: request}), terminate: dispose};
};
// The Chrome shared-scope fallback imports only the factory; its broker owns
// messaging and must close the entire SharedWorker after a fatal failure.
if (!self.ReaderTtsSharedScope) {
  const runtime = self.createReaderTtsRuntime((value, transfer) => self.postMessage(value, transfer));
  self.onmessage = ({data}) => { runtime.postMessage(data); if (data.type === 'dispose') self.close(); };
}
