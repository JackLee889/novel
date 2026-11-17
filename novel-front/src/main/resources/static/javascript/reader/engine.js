export class SherpaEngine {
  constructor(manifestUrl, onState = () => {}) {
    this.manifestUrl = manifestUrl; this.onState = onState;
    this.state = 'idle'; this.pending = new Map(); this.sequence = 0; this.generation = 0;
    this.initializations = 0; this.memoryFailures = 0;
    this.listeners = new Set(); this.playbackListeners = new Set(); this.yieldListeners = new Set();
    this.preferShared = typeof SharedWorker === 'function';
    if (this.preferShared) this.connectShared();
  }
  setState(state, detail = {}) {
    this.state = state; this.lastProgress = detail; this.onState(state, detail);
    for (const listener of this.listeners) listener(state, detail);
  }
  subscribe(listener) { this.listeners.add(listener); return () => this.listeners.delete(listener); }
  connectShared() {
    let complete, rejectConnection, announced = false;
    this.connection = new Promise((resolve, reject) => { complete = resolve; rejectConnection = reject; });
    // snapshot() may not be called until later; retain the rejection without an
    // unhandled promise, while ready()/connected() still see the original error.
    this.connection.catch(() => {});
    const timer = setTimeout(() => complete(false), 10000);
    try {
      const shared = new SharedWorker(new URL('./global.worker.js?v=20261008.3', import.meta.url),
        {name: 'novel-tts-1.13.8-global-v4', extendedLifetime: true});
      const port = shared.port;
      const adapter = {postMessage: value => port.postMessage(value), terminate: () => {
        clearTimeout(timer); clearInterval(this.heartbeat);
        try { port.postMessage({type: 'disconnect'}); } catch {}
        port.close(); complete(false);
      }};
      this.worker = adapter;
      const current = () => this.worker === adapter;
      port.onmessage = ({data}) => {
        if (!current()) return;
        if (data.type === 'hello') {
          clearTimeout(timer);
          try {
            this.shared = true; this.clientId = data.clientId; this.engineId = data.engineId;
            this.runtimeMode = data.runtimeMode;
            this.runtimeGeneration = data.runtimeGeneration;
            this.playbackSnapshot = {playback: data.playback, ownerId: data.ownerId};
            if (data.ready) {
              this.validateManifest(data.manifest); this.manifest = data.manifest;
              this.resourceStats = data.resources; this.initializations = data.initializations; this.diagnostics = data.diagnostics;
              this.setState('ready', {resources: data.resources, shared: true, reused: true});
            }
            announced = true;
            const heartbeat = () => {
              if (current()) try { port.postMessage({type: 'heartbeat', hidden: typeof document === 'object' && document.hidden}); } catch {}
            };
            heartbeat(); this.heartbeat = setInterval(heartbeat, 30000); this.heartbeat.unref?.();
            complete(true);
          } catch (error) { rejectConnection(error); this.reset(error); }
          return;
        }
        if (data.type === 'playback') {
          this.playbackSnapshot = {playback: data.playback, ownerId: data.ownerId};
          for (const listener of this.playbackListeners) listener(this.playbackSnapshot);
          return;
        }
        if (data.type === 'yield') { for (const listener of this.yieldListeners) listener(); return; }
        if (data.type === 'engine-error') { this.reset(Object.assign(new Error(data.message), {code: data.code, diagnostics: data.diagnostics})); return; }
        this.receive(data);
      };
      port.onmessageerror = () => { if (current()) this.reset(new Error('全局语音数据传输失败')); };
      shared.onerror = event => {
        event.preventDefault(); clearTimeout(timer);
        if (!current()) return;
        if (!announced) complete(false); else this.reset(new Error('全局语音 Worker 异常，请重试'));
      };
      this.sharedWorker = shared; port.start();
    } catch { clearTimeout(timer); complete(false); }
  }
  useDedicated() {
    this.worker?.terminate(); this.worker = null; this.sharedWorker = null;
    this.preferShared = false; this.shared = false;
  }
  validateManifest(manifest) {
    const base = new URL(manifest.baseUrl, location.href);
    if (base.origin !== location.origin || manifest.engineVersion !== '1.13.8' ||
        manifest.modelId !== 'matcha-icefall-zh-en') throw new Error('语音资源版本或来源不匹配');
    if (manifest.requiresIsolation && !crossOriginIsolated) throw new Error('当前语音资源需要浏览器隔离环境');
    return base;
  }
  receive(data) {
    // Dedicated runtimes can abort after ready or after the last audio reply.
    // Such an error is fatal to the heap even though its request is no longer pending.
    if (data.type === 'error' && data.fatal && data.transportGeneration === this.generation &&
        (this.shared || data.runtimeGeneration === this.runtimeGeneration)) {
      this.reset(Object.assign(new Error(data.message), {code: data.code, diagnostics: data.diagnostics}));
      return;
    }
    const item = this.pending.get(data.requestId);
    if (!item || item.generation !== this.generation || data.transportGeneration !== item.generation ||
        data.sessionId !== item.sessionId || data.queueRevision !== item.queueRevision) return;
    if (data.type === 'progress') { if (item.kind === 'init') this.setState('loading', data); return; }
    if (!['ready', 'audio', 'error', 'claimed', 'snapshot'].includes(data.type)) return;
    clearTimeout(item.timeout); this.pending.delete(data.requestId);
    if (data.type === 'error') {
      const error = Object.assign(new Error(data.message), {code: data.code, diagnostics: data.diagnostics});
      item.reject(error);
    } else item.resolve(data);
  }
  async ready() {
    if (this.state === 'ready') return this.manifest;
    if (this.initializing) return this.initializing;
    // There is no automatic model retry. Allow the initial attempt and two user
    // retries per page; each failed heap has been terminated before the next one.
    if (this.memoryFailures >= 3) {
      const error = Object.assign(new Error('浏览器内存仍不足，已停止重试。请关闭其他页面后刷新页面重试。'), {code: 'WASM_OOM_RETRY_LIMIT'});
      this.setState('error', {message: error.message, code: error.code}); throw error;
    }
    this.setState('loading', {message: '正在读取语音资源清单'});
    const generation = this.generation;
    const operation = (async () => {
      if (this.preferShared) {
        if (!this.worker) this.connectShared();
        if (!await this.connection) this.useDedicated();
        if (generation !== this.generation) throw new DOMException('会话已取消', 'AbortError');
        if (this.state === 'ready') return this.manifest;
      }
      const controller = new AbortController(); this.manifestAbort = controller;
      const timeout = setTimeout(() => controller.abort(), 15000);
      let response;
      try { response = await fetch(this.manifestUrl, {cache: 'no-cache', signal: controller.signal}); }
      finally { clearTimeout(timeout); if (this.manifestAbort === controller) this.manifestAbort = null; }
      if (!response.ok) throw new Error('无法加载语音资源清单');
      const manifest = await response.json();
      if (generation !== this.generation) throw new DOMException('会话已取消', 'AbortError');
      const base = this.validateManifest(manifest); this.manifest = manifest;
      const createDedicated = () => {
        const worker = new Worker(new URL('./sherpa.worker.js?v=20261008.3', import.meta.url));
        this.worker = worker; this.runtimeGeneration = generation;
        worker.onmessage = ({data}) => { if (this.worker === worker) this.receive(data); };
        worker.onerror = () => { if (this.worker === worker) this.reset(new Error('语音 Worker 异常，请重试')); };
        worker.onmessageerror = () => { if (this.worker === worker) this.reset(new Error('语音数据传输失败，请重试')); };
      };
      if (!this.shared) createDedicated();
      const initialize = () => this.request('init', {baseUrl: base.href, assets: manifest.assets, manifest,
        sessionId: 'engine', queueRevision: generation, runtimeGeneration: this.runtimeGeneration}, 120000);
      let result;
      try { result = await initialize(); }
      catch (error) {
        if (!this.shared || error.code !== 'SHARED_WORKER_UNAVAILABLE' || generation !== this.generation) throw error;
        this.useDedicated(); createDedicated(); result = await initialize();
      }
      if (generation !== this.generation) throw new DOMException('会话已取消', 'AbortError');
      this.resourceStats = result.resources; this.diagnostics = result.diagnostics;
      this.runtimeGeneration = result.runtimeGeneration;
      this.runtimeMode = result.runtimeMode || 'dedicated-worker';
      this.engineId = result.engineId; this.initializations = this.shared ? result.initializations : this.initializations + 1;
      this.setState('ready', {resources: result.resources, shared: this.shared, reused: result.reused, diagnostics: result.diagnostics});
      return manifest;
    })().catch(error => {
      if (generation === this.generation) this.reset(error);
      throw error;
    }).finally(() => { if (this.initializing === operation) this.initializing = null; });
    this.initializing = operation;
    return operation;
  }
  async connected() {
    if (this.preferShared && !this.worker) this.connectShared();
    if (!this.preferShared) return false;
    const connected = await this.connection;
    if (!connected) this.useDedicated();
    return connected;
  }
  async claim() {
    if (!await this.connected()) return null;
    return this.request('claim', {sessionId: 'global-control', queueRevision: this.generation}, 10000);
  }
  async snapshot() {
    if (!await this.connected()) return this.playbackSnapshot || {};
    return this.request('snapshot', {sessionId: 'global-control', queueRevision: this.generation}, 10000);
  }
  publish(playback) {
    if (this.shared) this.worker?.postMessage({type: 'publish', playback});
  }
  request(type, payload, ms = 45000) {
    return new Promise((resolve, reject) => {
      const requestId = ++this.sequence, generation = this.generation;
      const timeout = setTimeout(() => {
        if (generation === this.generation) this.reset(new Error(type === 'init' ? '语音模型初始化超时' : '语音合成超时'));
      }, ms);
      this.pending.set(requestId, {resolve, reject, timeout, generation, kind: type,
        sessionId: payload.sessionId, queueRevision: payload.queueRevision});
      try { this.worker.postMessage({type, requestId, ...payload, transportGeneration: generation}); }
      catch (error) { clearTimeout(timeout); this.pending.delete(requestId); reject(error); }
    });
  }
  async synthesize(segment, sessionId, queueRevision, speed) {
    await this.ready();
    if ([...this.pending.values()].some(i => i.kind === 'synthesize')) throw new Error('合成队列忙碌');
    const result = await this.request('synthesize', {sessionId, queueRevision, segmentId: segment.segmentId,
      text: segment.text, speed, modelId: this.manifest.modelId, speakerId: this.manifest.speakerId,
      runtimeGeneration: this.runtimeGeneration});
    this.diagnostics = result.diagnostics || this.diagnostics;
    return result;
  }
  reset(error = new DOMException('已释放', 'AbortError')) {
    if (error.code === 'WASM_OOM') this.memoryFailures++;
    this.generation++; this.manifestAbort?.abort(); this.manifestAbort = null;
    clearInterval(this.heartbeat); this.heartbeat = null;
    this.worker?.terminate(); this.worker = null; this.sharedWorker = null; this.shared = false;
    for (const item of this.pending.values()) { clearTimeout(item.timeout); item.reject(error); }
    this.pending.clear(); this.initializing = null;
    this.setState('error', {message: error.message, code: error.code, diagnostics: error.diagnostics});
  }
  dispose() { this.reset(); this.setState('disposed'); }
}
let globalEngine;
export function getGlobalEngine(manifestUrl = '/reader/tts-manifest.json', onState) {
  if (!globalEngine) globalEngine = new SherpaEngine(manifestUrl);
  if (onState) globalEngine.subscribe(onState);
  return globalEngine;
}
