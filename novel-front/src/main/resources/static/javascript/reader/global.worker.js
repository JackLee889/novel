/* Origin-wide model. Prefer a nested Worker; close the entire shared scope when
 * Chromium cannot expose Worker here and this scope itself must own the heap. */
const clients = new Map(), pending = new Map(), bootListeners = new Map();
const engineId = self.crypto.randomUUID();
const IDLE_GRACE_MS = 30000, HEARTBEAT_TTL_MS = 10 * 60 * 1000;
let native, manifest, resources, diagnostics, boot, idleTimer, sequence = 0, clientSequence = 0, initializations = 0;
let generation = 0, ready = false, playback = null, owner = null, synthesis = Promise.resolve();
let sharedScopeRuntime = false, closing = false;
const send = (port, value, transfer = []) => { try { port.postMessage(value, transfer); } catch {} };
const reply = (port, request, type, value = {}) => send(port, {...value,
  requestId: request.requestId, sessionId: request.sessionId, queueRevision: request.queueRevision,
  transportGeneration: request.transportGeneration, segmentId: request.segmentId, type},
  value.samples ? [value.samples.buffer] : []);
const warmResources = () => ({...resources, cacheHits: 0, downloadedAssets: 0,
  downloadedBytes: 0, storedAssets: 0, reused: true});
const snapshot = () => ({playback, ownerId: owner ? clients.get(owner)?.id : null});
const errorData = error => ({message: error.message, code: error.code, fatal: error.fatal === true, diagnostics: error.diagnostics});
const stale = () => new DOMException('语音运行时已更换', 'AbortError');
const runtimeMode = () => sharedScopeRuntime || typeof Worker !== 'function' ? 'shared-native' : 'nested-dedicated';
function broadcastPlayback() {
  for (const port of clients.keys()) send(port, {type: 'playback', ...snapshot()});
}
function scheduleIdle() {
  clearTimeout(idleTimer);
  if (clients.size || closing) return;
  idleTimer = setTimeout(() => {
    if (!clients.size) resetNative(new DOMException('语音引擎闲置已释放', 'AbortError'), generation, false);
  }, IDLE_GRACE_MS);
  idleTimer.unref?.();
}
function drop(port) {
  clients.delete(port); bootListeners.delete(port);
  if (owner === port) {
    owner = null;
    if (playback) playback = {...playback, state: 'paused'};
    broadcastPlayback();
  }
  scheduleIdle();
}
function resetNative(error, expectedGeneration = generation, notify = true) {
  if (expectedGeneration !== generation) return;
  const previous = native;
  native = null; ready = false; boot = null; resources = null; generation++;
  previous?.terminate();
  for (const item of pending.values()) { clearTimeout(item.timer); item.reject(error); }
  pending.clear();
  if (notify) for (const port of clients.keys()) send(port, {type: 'engine-error', ...errorData(error)});
  // postMessage queues failures at the other endpoint before close discards this
  // worker's tasks. Every connected client rejects its waits and reconnects only
  // on an explicit retry; no aborted Emscripten scope is reused.
  if (sharedScopeRuntime && !closing) { closing = true; self.close(); }
}
function requestNative(type, values, expectedGeneration = generation, timeout = 45000) {
  return new Promise((resolve, reject) => {
    if (!native || expectedGeneration !== generation) { reject(stale()); return; }
    const requestId = ++sequence, worker = native;
    const timer = setTimeout(() => {
      if (native === worker && generation === expectedGeneration)
        resetNative(new Error(type === 'init' ? '语音模型初始化超时' : '语音合成超时'), expectedGeneration);
    }, timeout);
    pending.set(requestId, {resolve, reject, timer, generation: expectedGeneration});
    try { worker.postMessage({type, requestId, sessionId: 'global-engine', queueRevision: expectedGeneration,
      ...values, runtimeGeneration: expectedGeneration}); }
    catch (error) { resetNative(error, expectedGeneration); }
  });
}
async function initialize(request) {
  if (closing) throw stale();
  const base = new URL(request.baseUrl, self.location.href);
  if (base.origin !== self.location.origin || base.pathname !== '/reader/tts/1.13.8-matcha-icefall-zh-en/' ||
      request.manifest?.engineVersion !== '1.13.8' || request.manifest?.modelId !== 'matcha-icefall-zh-en')
    throw new Error('全局语音资源版本无效');
  if (ready) return;
  if (boot) return boot.promise;
  manifest = request.manifest;
  const epoch = ++generation;
  let worker;
  if (typeof Worker === 'function') {
    try { worker = new Worker(new URL('./sherpa.worker.js?v=20261008.3', self.location.href)); }
    catch {} // A constructor failure also falls back to one origin-wide scope.
  }
  if (!worker) {
    sharedScopeRuntime = true;
    try {
      self.ReaderTtsSharedScope = true;
      importScripts(new URL('./sherpa.worker.js?v=20261008.3', self.location.href).href);
      const runtime = self.createReaderTtsRuntime(data => worker.onmessage({data}));
      worker = {postMessage: request => runtime.postMessage(request), terminate: () => runtime.terminate()};
    } catch (error) { resetNative(error, epoch); throw error; }
  }
  native = worker;
  worker.onmessage = ({data}) => {
    if (native !== worker || epoch !== generation || data.runtimeGeneration !== epoch) return;
    if (data.type === 'progress') {
      for (const [port, request] of bootListeners) reply(port, request, 'progress', data);
      return;
    }
    // Runtime aborts may arrive after the init/synthesis request was completed.
    // A fatal error belongs to the current heap, not to one pending request ID.
    if (data.type === 'error' && data.fatal) {
      resetNative(Object.assign(new Error(data.message), {code: data.code, fatal: true, diagnostics: data.diagnostics}), epoch);
      return;
    }
    const item = pending.get(data.requestId);
    if (!item || item.generation !== epoch) return;
    clearTimeout(item.timer); pending.delete(data.requestId);
    if (data.type === 'error') {
      const error = Object.assign(new Error(data.message), {code: data.code, fatal: data.fatal, diagnostics: data.diagnostics});
      item.reject(error);
    } else item.resolve(data);
  };
  worker.onerror = event => {
    event.preventDefault?.();
    resetNative(new Error(event.message || '语音 Worker 异常，请重试'), epoch);
  };
  worker.onmessageerror = () => resetNative(new Error('语音数据传输失败，请重试'), epoch);
  const currentBoot = {generation: epoch};
  boot = currentBoot;
  currentBoot.promise = requestNative('init', {baseUrl: base.href, assets: request.assets}, epoch, 120000)
    .then(result => {
      if (native !== worker || epoch !== generation) throw stale();
      resources = result.resources; diagnostics = result.diagnostics; ready = true; initializations++;
    }).catch(error => { resetNative(error, epoch); throw error; })
    .finally(() => { if (boot === currentBoot) boot = null; });
  return currentBoot.promise;
}
function cleanPlayback(value) {
  if (!value || !/^\d+$/.test(value.bookId) || !/^\d+$/.test(value.bookIndexId) ||
      typeof value.accountScope !== 'string' || typeof value.contentVersion !== 'string')
    throw new Error('播放位置无效');
  // Never retain chapter text or PCM in the shared playback record.
  return {bookId: value.bookId, bookIndexId: value.bookIndexId,
    bookName: String(value.bookName || '').slice(0, 200), chapterName: String(value.chapterName || '').slice(0, 200),
    accountScope: value.accountScope.slice(0, 100), contentVersion: value.contentVersion.slice(0, 100),
    segmentId: String(value.segmentId || '').slice(0, 100), index: Math.max(0, Math.min(100000, Math.floor(value.index) || 0)),
    offset: Math.max(0, Math.min(180, Number(value.offset) || 0)),
    speed: [0.8, 1, 1.25, 1.5].includes(value.speed) ? value.speed : 1,
    state: ['idle', 'preparing', 'playing', 'paused', 'buffering', 'ended', 'error'].includes(value.state) ? value.state : 'paused',
    resumeRequested: value.resumeRequested === true, savedAt: Date.now()};
}
// Hidden tabs can have heavily throttled timers. Use a generous lease, extended
// while hidden; pagehide disconnect handles ordinary navigation immediately.
const sweep = setInterval(() => {
  const now = Date.now();
  for (const [port, client] of clients) {
    if (now - client.seen > HEARTBEAT_TTL_MS * (client.hidden ? 3 : 1)) { drop(port); port.close(); }
  }
}, 30000);
sweep.unref?.();
self.onconnect = event => {
  const port = event.ports[0]; clearTimeout(idleTimer);
  if (closing) { send(port, {type: 'engine-error', message: '语音运行时已关闭，请重试'}); port.close(); return; }
  const client = {id: engineId + ':' + (++clientSequence), seen: Date.now(), hidden: false};
  clients.set(port, client);
  port.onmessage = async ({data: request}) => {
    if (request.type === 'disconnect') { drop(port); port.close(); return; }
    if (!clients.has(port)) return;
    client.seen = Date.now();
    if (request.type === 'heartbeat') { client.hidden = request.hidden === true; return; }
    try {
      if (request.type === 'init') {
        const reused = ready || !!boot;
        bootListeners.set(port, request);
        try {
          await initialize(request);
          if (!clients.has(port)) return;
          reply(port, request, 'ready', {manifest, resources: reused ? warmResources() : resources,
            engineId, initializations, shared: true, reused, runtimeGeneration: generation, diagnostics,
            runtimeMode: runtimeMode()});
        } finally { if (bootListeners.get(port) === request) bootListeners.delete(port); }
      } else if (request.type === 'synthesize') {
        if (!ready) throw new Error('全局语音模型尚未就绪');
        const epoch = generation;
        const task = synthesis.then(() => {
          if (!clients.has(port)) throw new DOMException('页面已离开', 'AbortError');
          if (!ready || epoch !== generation) throw stale();
          return requestNative('synthesize', {text: request.text, speed: request.speed,
            modelId: request.modelId, speakerId: request.speakerId, segmentId: request.segmentId}, epoch);
        });
        synthesis = task.catch(() => {});
        const result = await task;
        if (epoch !== generation || !clients.has(port)) throw stale();
        reply(port, request, 'audio', result);
      } else if (request.type === 'claim') {
        if (owner && owner !== port) send(owner, {type: 'yield'});
        owner = port; reply(port, request, 'claimed', {ownerId: client.id});
      } else if (request.type === 'snapshot') {
        reply(port, request, 'snapshot', snapshot());
      } else if (request.type === 'publish') {
        if (owner === port) { playback = cleanPlayback(request.playback); broadcastPlayback(); }
      }
    } catch (error) { if (clients.has(port)) reply(port, request, 'error', errorData(error)); }
  };
  port.onmessageerror = () => { drop(port); port.close(); };
  port.start();
  send(port, {type: 'hello', clientId: client.id, engineId, shared: true,
    nestedWorkerAvailable: typeof Worker === 'function', runtimeMode: runtimeMode(),
    ready, manifest: ready ? manifest : null,
    resources: ready ? warmResources() : null, runtimeGeneration: generation, diagnostics: ready ? diagnostics : null,
    initializations, ...snapshot()});
};
