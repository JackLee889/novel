// 只预约公开模型加载，不创建音频、合成 PCM 或请求章节正文。
const scheduledEngines = new WeakMap();

/**
 * 页面 load 后空闲时预加载，返回同引擎共享的状态与取消句柄。
 * env 可注入平铺的 document/window、Worker/WebAssembly、idle 和 timer API。
 * promise 在启动前为 null；启动后总是 resolve {ok, error?}，后台失败不自动重试。
 */
export function scheduleModelPreload(engine, env = globalThis) {
  if (!engine || typeof engine.ready !== 'function') throw new TypeError('需要可初始化的语音引擎');
  if (scheduledEngines.has(engine)) return scheduledEngines.get(engine);
  const host = env.window || env;
  let state = 'waiting-load', error = null, promise = null;
  let pendingId = null, pendingKind = null, ticket = 0, loadListener = null;
  let hidden = false, manualCancellation = false, failed = false, started = false;
  let active = null, restoreAfterTask = false, cancellationReason = null;

  function clearPending() {
    ticket++;
    if (pendingKind === 'idle') env.cancelIdleCallback?.(pendingId);
    if (pendingKind === 'timer') env.clearTimeout?.(pendingId);
    pendingId = null; pendingKind = null;
    if (loadListener) host.removeEventListener?.('load', loadListener);
    loadListener = null;
  }

  function cancelPending(reason) {
    if (!['waiting-load', 'scheduled'].includes(state)) return false;
    clearPending(); cancellationReason = reason; state = 'cancelled';
    return true;
  }

  function restoredEngineNeedsLoad() {
    return engine.state === 'disposed' || engine.state === 'idle';
  }

  function start() {
    if (hidden || manualCancellation || failed) return;
    pendingId = null; pendingKind = null; cancellationReason = null;
    started = true; error = null;
    if (engine.state === 'ready') {
      state = 'ready'; promise = Promise.resolve({ok: true, reused: true});
      return;
    }
    if (engine.state === 'error' && !engine.initializing) {
      // 用户播放已失败，后台预约不能消耗一次显式重试机会。
      state = 'failed'; failed = true;
      error = new Error('语音引擎已初始化失败，等待用户重试');
      promise = Promise.resolve({ok: false, skipped: 'engine-error', error});
      return;
    }
    state = 'loading';
    const attempt = {leftPage: false}; active = attempt;
    // ready() 本身合并初始化；若播放已经在初始化，则直接采用它的现有任务。
    const task = engine.initializing || Promise.resolve().then(() => {
      // idle 回调与此微任务之间可能发生 pagehide，不能重连已释放的页面。
      if (attempt.leftPage || hidden) {
        throw Object.assign(new Error('页面已离开，取消后台预加载'), {name: 'AbortError'});
      }
      return engine.ready();
    });
    promise = Promise.resolve(task).then(() => {
      if (active === attempt) state = 'ready';
      return {ok: true};
    }, failure => {
      const cancelled = attempt.leftPage && failure?.name === 'AbortError';
      if (active === attempt) {
        error = failure;
        if (cancelled) { state = 'cancelled'; cancellationReason = 'pagehide'; }
        else { state = 'failed'; failed = true; }
      }
      return {ok: false, error: failure, cancelled};
    }).then(outcome => {
      if (active === attempt) active = null;
      if (restoreAfterTask) {
        restoreAfterTask = false;
        if (!hidden && !failed && !manualCancellation && restoredEngineNeedsLoad()) queue();
      }
      return outcome;
    });
  }

  function queue() {
    if (hidden || manualCancellation || failed) return;
    clearPending();
    if (engine.state === 'ready') { start(); return; }
    if (env.document && env.document.readyState !== 'complete') {
      state = 'waiting-load';
      loadListener = () => { loadListener = null; queueIdle(); };
      host.addEventListener?.('load', loadListener, {once: true});
    } else queueIdle();
  }

  function queueIdle() {
    if (hidden || manualCancellation || failed) return;
    state = 'scheduled';
    const current = ++ticket;
    const run = () => { if (current === ticket && state === 'scheduled') start(); };
    if (typeof env.requestIdleCallback === 'function') {
      pendingKind = 'idle'; pendingId = env.requestIdleCallback(run, {timeout: 1000});
    } else {
      pendingKind = 'timer'; pendingId = env.setTimeout(run, 0);
    }
  }

  const onPageHide = () => {
    hidden = true;
    cancelPending('pagehide');
    if (active) active.leftPage = true;
    // 已开始的加载由全局播放器管理，不能在此 reset/dispose 共享引擎。
  };
  const onPageShow = event => {
    hidden = false;
    if (!event.persisted || manualCancellation || failed) return;
    if (active) {
      // 先等旧任务结算，区分页面释放与真正失败，避免后台失败自动循环重试。
      if (active.leftPage && restoredEngineNeedsLoad()) restoreAfterTask = true;
      return;
    }
    if ((state === 'cancelled' && cancellationReason === 'pagehide') ||
        (started && restoredEngineNeedsLoad())) queue();
  };

  const handle = {
    get state() { return state; },
    get error() { return error; },
    get promise() { return promise; },
    cancel() {
      if (!cancelPending('manual')) return false;
      manualCancellation = true;
      host.removeEventListener?.('pagehide', onPageHide);
      host.removeEventListener?.('pageshow', onPageShow);
      return true;
    }
  };
  scheduledEngines.set(engine, handle);
  if (typeof env.Worker !== 'function' || !env.WebAssembly) {
    state = 'unsupported'; promise = Promise.resolve({ok: false, skipped: 'unsupported'});
    return handle;
  }
  host.addEventListener?.('pagehide', onPageHide);
  host.addEventListener?.('pageshow', onPageShow);
  queue();
  return handle;
}
