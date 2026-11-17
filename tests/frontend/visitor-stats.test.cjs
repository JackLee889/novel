const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

// Run the actual browser script. The fake surfaces supply DOM, time and network;
// assertions observe displayed text and requests rather than private functions.
const script = fs.readFileSync(path.resolve(__dirname,
  '../../novel-front/src/main/resources/static/javascript/site/stats.js'), 'utf8');
const template = fs.readFileSync(path.resolve(__dirname,
  '../../novel-front/src/main/resources/templates/common/site.html'), 'utf8');
const statsMarkup = template.match(/<section\b[^>]*th:fragment="stats\(compact\)"[^>]*>[\s\S]*?<\/section>/)?.[0];
assert.ok(statsMarkup, 'The actual shared statistics fragment must exist');
const selectors = new Set([...statsMarkup.matchAll(/\b(data-[\w-]+)(?=[\s=>])/g)].map(match => match[1]));
const snapshot = (overrides = {}) => ({
  online: '1', visitors: '1', pageViews: '1', startedAt: '2026-10-09T04:00:00Z',
  onlineWindowSeconds: 90, available: true, onlineAvailable: true, enabled: true,
  status: 'available', ...overrides
});
const reply = (data = snapshot()) => ({ok: true, status: 200,
  json: async () => ({code: 200, data})});
const flush = async () => { for (let i = 0; i < 4; i++) await new Promise(resolve => setImmediate(resolve)); };

function environment({locks = true, broadcast = true, handler} = {}) {
  const store = new Map(), cookies = new Map(), tabs = [], channels = new Set(), timers = new Map();
  let now = 0, timerId = 0, uuidId = 0, lockQueue = Promise.resolve();
  const epoch = Date.parse('2026-10-09T04:00:00Z');
  class BrowserDate extends Date {
    constructor(...args) { super(...(args.length ? args : [epoch + now])); }
    static now() { return epoch + now; }
  }
  function later(callback, delay = 0, ...args) {
    const id = ++timerId; timers.set(id, {at: now + Math.max(0, Number(delay) || 0), callback, args}); return id;
  }
  async function advance(milliseconds) {
    const until = now + milliseconds;
    for (;;) {
      const next = [...timers.entries()].filter(([, value]) => value.at <= until)
        .sort((a, b) => a[1].at - b[1].at || a[0] - b[0])[0];
      if (!next) break;
      timers.delete(next[0]); now = next[1].at; next[1].callback(...next[1].args); await flush();
    }
    now = until; await flush();
  }
  function tab({pathname = '/', hidden = false} = {}) {
    const listeners = {window: new Map(), document: new Map()}, requests = [], nodes = new Map();
    const node = name => {
      assert.ok(selectors.has(name), `The actual statistics fragment has no ${name} node`);
      if (!nodes.has(name)) nodes.set(name, {textContent: '', title: '', hidden: false, dataset: {}});
      return nodes.get(name);
    };
    const widget = {dataset: {}, attrs: new Map([['aria-busy', 'true']]),
      querySelector(selector) { const name = selector.slice(1, -1); return selectors.has(name) ? node(name) : null; },
      removeAttribute(name) { this.attrs.delete(name); }};
    const on = surface => (name, callback) => {
      if (!listeners[surface].has(name)) listeners[surface].set(name, []);
      listeners[surface].get(name).push(callback);
    };
    const emit = (surface, name, data = {}) => {
      for (const callback of listeners[surface].get(name) || []) callback(data);
    };
    const document = {hidden, querySelectorAll: () => [widget], addEventListener: on('document'),
      get cookie() { return [...cookies].map(([key, value]) => key + '=' + value).join('; '); },
      set cookie(value) {
        const pair = value.split(';')[0], split = pair.indexOf('=');
        if (/Max-Age=0(?:;|$)/i.test(value)) cookies.delete(pair.slice(0, split));
        else cookies.set(pair.slice(0, split), pair.slice(split + 1));
      }};
    const location = {pathname, search: '', protocol: 'http:', origin: 'http://localhost'};
    const navigator = locks ? {locks: {request(name, ...args) {
      const work = args.at(-1); const pending = lockQueue.then(work);
      lockQueue = pending.catch(() => {}); return pending;
    }}} : {};
    const surface = {requests, document, location, widget, emit, node}; tabs.push(surface);
    const localStorage = {getItem: key => store.get(key) || null, removeItem: key => store.delete(key),
      setItem(key, value) {
        const oldValue = store.get(key) || null; store.set(key, String(value));
        if (oldValue !== String(value)) for (const other of tabs) if (other !== surface)
          queueMicrotask(() => other.emit('window', 'storage', {key, newValue: String(value), oldValue}));
      }};
    class Channel {
      constructor(name) { this.name = name; this.callbacks = []; channels.add(this); }
      addEventListener(name, callback) { if (name === 'message') this.callbacks.push(callback); }
      postMessage(data) {
        for (const other of channels) if (other !== this && other.name === this.name)
          queueMicrotask(() => { if (channels.has(other)) for (const callback of other.callbacks) callback({data}); });
      }
      close() { channels.delete(this); }
    }
    const fetch = async (url, options) => {
      const request = {url, method: options.method,
        params: options.body ? Object.fromEntries(options.body.entries()) : {}, options};
      requests.push(request); return handler ? handler(request, surface) : reply();
    };
    const context = vm.createContext({document, location, navigator, localStorage, fetch,
      window: {addEventListener: on('window')}, URLSearchParams, AbortController,
      Uint8Array, Math, Date: BrowserDate, console, queueMicrotask,
      setTimeout: later, clearTimeout: id => timers.delete(id),
      crypto: {randomUUID: () => '00000000-0000-4000-8000-' + String(++uuidId).padStart(12, '0')},
      ...(broadcast ? {BroadcastChannel: Channel} : {})});
    vm.runInContext(script, context, {filename: 'stats.js'});
    surface.visits = () => requests.filter(request => request.url === '/site-stats/visit');
    surface.heartbeats = () => requests.filter(request => request.url === '/site-stats/heartbeat');
    surface.hide = () => { document.hidden = true; emit('document', 'visibilitychange'); };
    surface.show = () => { document.hidden = false; emit('document', 'visibilitychange'); };
    return surface;
  }
  return {tab, advance};
}

test('polling sends heartbeats without creating another page view', async () => {
  const env = environment(), tab = env.tab(); await flush();
  assert.equal(tab.visits().length, 1); assert.equal(tab.heartbeats().length, 1);
  await env.advance(90000);
  assert.equal(tab.visits().length, 1); assert.equal(tab.heartbeats().length, 4);
  assert.equal(tab.node('data-stats-views').textContent, '1');
  assert.equal(tab.requests.every(request => request.options.credentials === 'same-origin'
    && request.options.cache === 'no-store'), true);
});

for (const failure of ['network', 'unavailable']) test(`${failure} visit failures retry the same pageViewId`, async () => {
  let attempts = 0;
  const env = environment({handler(request) {
    if (request.url === '/site-stats/visit' && ++attempts === 1) {
      if (failure === 'network') throw new TypeError('connection lost');
      return reply(snapshot({available: false, onlineAvailable: false, visitors: null, pageViews: null}));
    }
    return reply();
  }}), tab = env.tab(); await flush();
  assert.equal(tab.visits().length, 1); const first = tab.visits()[0].params;
  await env.advance(30000);
  assert.equal(tab.visits().length, 2); assert.deepEqual(tab.visits()[1].params, first);
  await env.advance(30000); assert.equal(tab.visits().length, 2);
  assert.equal(tab.node('data-stats-views').textContent, '1');
});

test('a hidden document sends no visits or heartbeats and resumes without a new PV', async () => {
  const env = environment(), tab = env.tab(); await flush(); tab.hide();
  const requests = tab.requests.length; await env.advance(120000);
  assert.equal(tab.requests.length, requests);
  tab.show(); await flush(); assert.equal(tab.visits().length, 1);
  assert.equal(tab.heartbeats().length, 2);
  const background = env.tab({hidden: true}); await flush();
  assert.equal(background.requests.length, 0); background.show(); await flush();
  assert.equal(background.visits().length, 1);
});

test('hiding during an in-flight visit aborts it and resumes with the same pageViewId', async () => {
  let first = true;
  const env = environment({handler(request) {
    if (request.url === '/site-stats/visit' && first) {
      first = false;
      return new Promise((resolve, reject) => request.options.signal.addEventListener('abort',
        () => reject(Object.assign(new Error('aborted'), {name: 'AbortError'})), {once: true}));
    }
    return reply();
  }}), tab = env.tab(); await flush();
  const id = tab.visits()[0].params.pageViewId;
  tab.hide(); await flush(); assert.equal(tab.visits()[0].options.signal.aborted, true);
  await env.advance(120000); assert.equal(tab.visits().length, 1);
  tab.show(); await flush(); assert.equal(tab.visits().length, 2);
  assert.equal(tab.visits()[1].params.pageViewId, id);
  assert.equal(tab.heartbeats().length, 1);
});

test('BFCache restoration keeps the displayed page identity and resumes a heartbeat', async () => {
  const env = environment(), tab = env.tab(); await flush();
  tab.emit('window', 'pagehide', {persisted: true}); const requests = tab.requests.length;
  await env.advance(120000); assert.equal(tab.requests.length, requests);
  tab.emit('window', 'pageshow', {persisted: true}); await flush();
  assert.equal(tab.visits().length, 1); assert.equal(tab.heartbeats().length, 2);
});

test('a successfully displayed new chapter counts once, including a chapter reached while hidden', async () => {
  const env = environment(), tab = env.tab({pathname: '/book/7/71.html'}); await flush();
  tab.location.pathname = '/book/7/72.html'; tab.emit('window', 'novel:chapter-view'); await flush();
  assert.equal(tab.visits().length, 2);
  assert.notEqual(tab.visits()[0].params.pageViewId, tab.visits()[1].params.pageViewId);
  tab.emit('window', 'novel:chapter-view'); await flush(); assert.equal(tab.visits().length, 2);
  tab.hide(); tab.location.pathname = '/book/7/73.html'; tab.emit('window', 'novel:chapter-view');
  await env.advance(90000); assert.equal(tab.visits().length, 2);
  tab.show(); await flush(); assert.equal(tab.visits().length, 3);
});

test('a failed chapter navigation that only changes the URL does not create a PV', async () => {
  const env = environment(), tab = env.tab({pathname: '/book/7/71.html'}); await flush();
  tab.location.pathname = '/book/7/72.html'; tab.emit('window', 'popstate');
  await env.advance(30000); assert.equal(tab.visits().length, 1);
  assert.equal(tab.heartbeats().length, 2);
  tab.emit('window', 'novel:chapter-view', {detail: {bookId: '7', chapterId: '72'}}); await flush();
  assert.equal(tab.visits().length, 2);
});

test('64-bit decimal counters preserve exact titles and screen reader text', async () => {
  const exact = '9223372036854775807';
  const env = environment({handler: () => reply(snapshot({visitors: exact, pageViews: exact}))});
  const tab = env.tab(); await flush();
  assert.equal(tab.node('data-stats-views').textContent, '9.22e18');
  assert.equal(tab.node('data-stats-views').title, '9,223,372,036,854,775,807 次');
  assert.equal(tab.node('data-stats-full-views').textContent, exact + '次');
  assert.equal(tab.node('data-stats-unit-views').hidden, false);
  assert.match(tab.node('data-stats-since').textContent, /2026/);
  assert.equal(tab.widget.attrs.has('aria-busy'), false);
});

test('unsafe numeric counters and a failed refresh never display an invented zero', async () => {
  let offline = false, unsafe = true;
  const env = environment({handler: () => {
    if (offline) throw new TypeError('offline');
    return reply(snapshot({pageViews: unsafe ? Number.MAX_SAFE_INTEGER + 2 : '7'}));
  }}), tab = env.tab(); await flush();
  assert.equal(tab.node('data-stats-views').textContent, '—');
  assert.equal(tab.node('data-stats-full-views').textContent, '暂不可用');
  assert.equal(tab.node('data-stats-unit-views').hidden, true);
  unsafe = false; await env.advance(30000);
  assert.equal(tab.node('data-stats-views').textContent, '7');
  offline = true; await env.advance(30000);
  assert.equal(tab.node('data-stats-online').textContent, '—');
  assert.equal(tab.node('data-stats-views').textContent, '7');
  assert.match(tab.node('data-stats-status').textContent, /暂不可用/);
});

test('disabled statistics stop POSTs, probe with GET, and resume one visit when enabled', async () => {
  let enabled = false;
  const env = environment({handler: () => reply(snapshot(enabled ? {} : {
    enabled: false, available: false, onlineAvailable: false, online: null, visitors: null, pageViews: null
  }))}), tab = env.tab(); await flush();
  assert.equal(tab.visits().length, 1); assert.equal(tab.heartbeats().length, 0);
  assert.equal(tab.node('data-stats-status').textContent, '访问统计已暂停');
  const initial = tab.requests.length; await env.advance(299999);
  assert.equal(tab.requests.length, initial);
  await env.advance(1); assert.equal(tab.requests.at(-1).method, 'GET');
  assert.equal(tab.visits().length, 1); assert.equal(tab.heartbeats().length, 0);
  enabled = true; await env.advance(300000);
  assert.equal(tab.visits().length, 2); assert.equal(tab.heartbeats().length, 1);
  await env.advance(30000); assert.equal(tab.visits().length, 2);
});

test('simultaneous first tabs without Web Locks agree on one visitor before recording UV', async () => {
  const env = environment({locks: false, broadcast: true});
  const a = env.tab(), b = env.tab(); await flush();
  assert.equal(a.visits().length + b.visits().length, 0);
  await env.advance(150);
  assert.equal(a.visits().length, 1); assert.equal(b.visits().length, 1);
  assert.equal(a.visits()[0].params.visitorId, b.visits()[0].params.visitorId);
  assert.notEqual(a.visits()[0].params.pageViewId, b.visits()[0].params.pageViewId);
});
