// Site-wide anonymous counts. Only displayed pages are visits; prefetches are not.
const widgets = [...document.querySelectorAll('[data-site-stats]')];
const storageKey = 'novel.visitor-stats.visitorId';
const cookieName = 'novel_stats_visitor';
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const interval = 30000;
let visitorId, timer, identityChannel, busy = false, active = true, lastViewedKey;
let shownViewKey = viewKey();
let pendingView = false, unavailable = false, refreshFailed = false;
let pausedStats = false;
const visits = [];
const requests = new Set();

function uuid() {
  if (globalThis.crypto?.randomUUID) return crypto.randomUUID();
  const bytes = new Uint8Array(16);
  if (globalThis.crypto?.getRandomValues) crypto.getRandomValues(bytes);
  else for (let i = 0; i < bytes.length; i++) bytes[i] = Math.floor(Math.random() * 256);
  bytes[6] = (bytes[6] & 15) | 64; bytes[8] = (bytes[8] & 63) | 128;
  const hex = [...bytes].map(value => value.toString(16).padStart(2, '0')).join('');
  return [hex.slice(0, 8), hex.slice(8, 12), hex.slice(12, 16), hex.slice(16, 20), hex.slice(20)].join('-');
}

function readCookie() {
  try {
    const value = document.cookie.split(';').map(item => item.trim()).find(item => item.startsWith(cookieName + '='));
    return value ? decodeURIComponent(value.slice(cookieName.length + 1)) : '';
  } catch { return ''; }
}

function savedVisitorId() {
  let saved;
  try { saved = localStorage.getItem(storageKey); } catch {}
  const cookie = readCookie();
  return uuidPattern.test(saved || '') ? saved : uuidPattern.test(cookie) ? cookie : '';
}

function saveVisitorId(value) {
  try { localStorage.setItem(storageKey, value); } catch {}
  try { document.cookie = cookieName + '=' + encodeURIComponent(value) + '; Path=/; Max-Age=31536000; SameSite=Lax' + (location.protocol === 'https:' ? '; Secure' : ''); } catch {}
  return value;
}

function resolveVisitorId() { return saveVisitorId(savedVisitorId() || uuid()); }

async function coordinateVisitorId() {
  const saved = savedVisitorId();
  if (!globalThis.BroadcastChannel) return resolveVisitorId();
  // HTTP sites can lack Web Locks. Let first-time tabs agree before recording UV.
  const proposed = saved || uuid(), candidates = new Set([proposed]);
  const established = new Set(saved ? [saved] : []);
  let settled = !!saved, chosen = saved;
  try {
    identityChannel = new BroadcastChannel('novel-visitor-stats-id');
    identityChannel.addEventListener('message', event => {
      const data = event.data;
      if (!data || !uuidPattern.test(data.id || '')) return;
      if (!settled) { candidates.add(data.id); if (data.established) established.add(data.id); }
      if (data.probe) identityChannel?.postMessage({id:chosen || proposed, established:settled});
    });
    identityChannel.postMessage({id:proposed, established:!!saved, probe:true});
    if (saved) return saveVisitorId(saved);
    await new Promise(resolve => setTimeout(resolve, 150));
    chosen = savedVisitorId() || [...(established.size ? established : candidates)].sort()[0];
    settled = true;
    identityChannel.postMessage({id:chosen, established:true});
    return saveVisitorId(chosen);
  } catch { identityChannel?.close(); identityChannel = null; return resolveVisitorId(); }
}

function viewKey() {
  const chapter = location.pathname.match(/^\/book\/(\d+)\/(\d+)\.html$/);
  return chapter ? chapter[1] + '/' + chapter[2] : location.pathname + location.search;
}

function queueCurrentView() {
  if (!visitorId || !active || document.hidden || pausedStats) return;
  const key = shownViewKey;
  if (key !== lastViewedKey) {
    visits.push({visitorId, pageViewId:uuid()});
    lastViewedKey = key;
  }
  pendingView = false;
}

function digits(value) {
  if (typeof value !== 'string' && typeof value !== 'number') return null;
  if (typeof value === 'number' && (!Number.isSafeInteger(value) || value < 0)) return null;
  const text = String(value);
  return /^\d+$/.test(text) ? text.replace(/^0+(?=\d)/, '') : null;
}

function grouped(value) { return value.replace(/\B(?=(\d{3})+(?!\d))/g, ','); }

function compact(value) {
  if (value.length < 5) return grouped(value);
  // Keep even a 64-bit counter within a narrow mobile column; the title is exact.
  if (value.length > 16) return value[0] + '.' + value.slice(1, 3) + 'e' + (value.length - 1);
  const scale = value.length > 12 ? 12 : value.length > 8 ? 8 : 4;
  const whole = value.slice(0, -scale), fraction = value.slice(-scale, -scale + 1);
  return whole + (fraction !== '0' && whole.length < 4 ? '.' + fraction : '') + ({4:'万', 8:'亿', 12:'万亿'})[scale];
}

function setCount(widget, name, value, unit) {
  const node = widget.querySelector('[data-stats-' + name + ']');
  const exact = digits(value);
  node.textContent = exact == null ? '—' : compact(exact);
  node.dataset.long = String(node.textContent.length >= 6);
  node.title = exact == null ? '暂不可用' : grouped(exact) + ' ' + unit;
  widget.querySelector('[data-stats-full-' + name + ']').textContent = exact == null ? '暂不可用' : exact + unit;
  widget.querySelector('[data-stats-unit-' + name + ']').hidden = exact == null;
}

function status(text) {
  for (const widget of widgets) widget.querySelector('[data-stats-status]').textContent = text;
}

function render(data) {
  if (!data || typeof data !== 'object') throw new Error('Invalid statistics');
  pausedStats = data.enabled === false;
  if (pausedStats) { visits.length = 0; lastViewedKey = null; }
  unavailable = data.available !== true;
  const onlineAvailable = !pausedStats && !unavailable && data.onlineAvailable === true && digits(data.online) !== null;
  for (const widget of widgets) {
    widget.removeAttribute('aria-busy');
    widget.dataset.online = String(onlineAvailable && digits(data.online) !== '0');
    setCount(widget, 'online', onlineAvailable ? data.online : null, '人');
    setCount(widget, 'views', unavailable ? null : data.pageViews, '次');
    const since = widget.querySelector('[data-stats-since]');
    if (data.startedAt) {
      const date = new Date(data.startedAt);
      since.textContent = Number.isNaN(date.getTime()) ? '累计数据从功能启用时开始记录。' : '累计数据自 ' + date.toLocaleDateString('zh-CN', {timeZone:'Asia/Shanghai'}) + ' 起记录。';
    }
    const windowText = widget.querySelector('[data-stats-window]');
    windowText.textContent = '当前在线为最近 ' + (Number(data.onlineWindowSeconds) > 0 ? Number(data.onlineWindowSeconds) : 90) + ' 秒内仍在浏览的访客。';
  }
  status(pausedStats ? '访问统计已暂停' : unavailable ? '统计暂不可用，稍后自动刷新' : !onlineAvailable ? '在线人数稍后刷新' : '');
}

function failed() {
  refreshFailed = true;
  for (const widget of widgets) {
    widget.removeAttribute('aria-busy');
    widget.dataset.online = 'false';
    setCount(widget, 'online', null, '人');
  }
  status('统计暂不可用，稍后自动刷新');
}

async function request(path, params) {
  const controller = new AbortController(); requests.add(controller);
  const timeout = setTimeout(() => controller.abort(), 8000);
  // Match the existing site's form API without auth redirects or toast messages.
  const options = {method:params ? 'POST' : 'GET', credentials:'same-origin', cache:'no-store', signal:controller.signal, headers:{Accept:'application/json'}};
  if (params) { options.body = new URLSearchParams(params); options.headers['Content-Type'] = 'application/x-www-form-urlencoded;charset=UTF-8'; }
  try {
    const response = await fetch(path, options);
    if (!response.ok) throw new Error('Statistics request failed');
    const result = await response.json();
    if (Number(result?.code) !== 200) throw new Error('Statistics unavailable');
    return result.data;
  } finally { clearTimeout(timeout); requests.delete(controller); }
}

function schedule() {
  clearTimeout(timer);
  if (active && !document.hidden) timer = setTimeout(tick, pausedStats ? 300000 : interval);
}

async function tick() {
  if (busy || !visitorId || !active || document.hidden) return;
  busy = true; refreshFailed = false;
  try {
    if (pausedStats) {
      try { render(await request('/site-stats')); } catch { status('访问统计已暂停，稍后自动刷新'); }
      if (pausedStats || !active || document.hidden) return;
    }
    if (pendingView || lastViewedKey == null) queueCurrentView();
    while (visits.length && active && !document.hidden) {
      const visit = visits[0];
      try {
        const data = await request('/site-stats/visit', visit);
        if (data?.enabled === false) { render(data); return; }
        if (data?.available !== true) { render(data); refreshFailed = true; break; }
        visits.shift(); render(data);
      } catch { refreshFailed = true; break; }
    }
    if (!active || document.hidden || pausedStats) return;
    try { render(await request('/site-stats/heartbeat', {visitorId})); }
    catch { refreshFailed = true; }
    if (refreshFailed) {
      try {
        render(await request('/site-stats'));
        if (!unavailable && !pausedStats) status('统计稍后自动刷新');
      }
      catch { failed(); }
    }
  } finally { busy = false; schedule(); }
}

function resume() {
  if (!active || document.hidden) return;
  if (pendingView || lastViewedKey == null) queueCurrentView();
  tick();
}

function pause() {
  clearTimeout(timer);
  for (const controller of requests) controller.abort();
}

if (widgets.length) {
  window.addEventListener('novel:chapter-view', event => {
    const book = String(event.detail?.bookId || ''), chapter = String(event.detail?.chapterId || '');
    shownViewKey = /^\d+$/.test(book) && /^\d+$/.test(chapter) ? book + '/' + chapter : viewKey();
    pendingView = true;
    if (!document.hidden) { queueCurrentView(); tick(); }
  });
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) pause(); else resume();
  });
  window.addEventListener('pagehide', () => { active = false; pause(); identityChannel?.close(); identityChannel = null; });
  // The existing document's view ID survives bfcache restoration.
  window.addEventListener('pageshow', () => { active = true; resume(); });
  window.addEventListener('online', resume);
  window.addEventListener('storage', event => {
    if (event.key === storageKey && uuidPattern.test(event.newValue || '')) visitorId = event.newValue;
  });
  const initialize = async () => {
    try {
      visitorId = navigator.locks?.request ? await navigator.locks.request('novel-visitor-stats-id', resolveVisitorId) : await coordinateVisitorId();
    } catch { visitorId = resolveVisitorId(); }
    pendingView = true; resume();
  };
  initialize();
}
