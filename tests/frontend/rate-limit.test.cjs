const test = require('node:test'), assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path'), vm = require('node:vm');
const root = path.resolve(__dirname, '../../novel-front/src/main/resources/static/javascript');
const key = 'novel.search-chapter.retry-at.v1';
function environment({storageFails = false} = {}) {
  let now = Date.parse('2026-10-19T16:00:00Z'), cookie = '';
  const store = new Map(), tabs = [];
  async function tab() {
    const events = new Map(), timers = new Map(), modules = new Map(); let sequence = 0;
    class Clock extends Date { static now() { return now; } }
    const context = vm.createContext({Date:Clock, URL, URLSearchParams, AbortController, DOMException, FormData, atob, TextDecoder, Uint8Array, console,
      setTimeout(fn, ms) { const id = ++sequence; timers.set(id, {fn, at:now + ms}); return id; }, clearTimeout(id) { timers.delete(id); },
      location:{origin:'https://www.xs001.xyz', href:'https://www.xs001.xyz/'},
      document:{get cookie() { return cookie; }, set cookie(v) { cookie = v.split(';')[0]; }},
      window:{addEventListener(name, fn) { if (!events.has(name)) events.set(name, []); events.get(name).push(fn); }},
      localStorage:{getItem(k) { if (storageFails) throw Error('blocked'); return store.get(k) || null; }, setItem(k, v) {
        if (storageFails) throw Error('blocked'); store.set(k, v);
        for (const other of tabs) if (other.context !== context) for (const fn of other.events.get('storage') || []) fn({key:k, newValue:v});
      }}, fetch:async() => ({ok:true, status:200, json:async() => ({code:200, data:{}})})});
    const result = {context, events, timers}; tabs.push(result);
    async function load(name) {
      name = path.posix.normalize(name.split('?')[0]);
      if (modules.has(name)) return modules.get(name);
      const mod = new vm.SourceTextModule(fs.readFileSync(path.join(root, name), 'utf8'), {context, identifier:name}); modules.set(name, mod);
      await mod.link(specifier => load(path.posix.join(path.posix.dirname(name), specifier))); return mod;
    }
    const api = await load('site/api.js'), repository = await load('reader/chapter-repository.js');
    await api.evaluate(); await repository.evaluate();
    Object.assign(result, {api:api.namespace, Repository:repository.namespace.ChapterRepository, limit:modules.get('site/rate-limit.js').namespace});
    return result;
  }
  function advance(ms) {
    now += ms;
    for (const tab of tabs) for (const [id, timer] of tab.timers) if (timer.at <= now) { tab.timers.delete(id); timer.fn(); }
  }
  return {tab, advance, store, get now() { return now; }};
}
const blocked = (retryAfter, json) => ({ok:false, status:429, headers:{get:() => retryAfter}, json:json || (() => { throw Error('An HTML block page must not be parsed as JSON'); })});
const chapter = {bookId:'100', bookIndexId:'200', access:'allowed', paragraphs:[{id:'p1', text:'保留当前正文。'}]};
const chapterResponse = () => ({ok:true, status:200, json:async() => ({code:200, data:chapter})});

test('only same-origin search and chapter paths share the allowance', async() => {
  const {limit} = await environment().tab();
  for (const p of ['/book/searchByPage?k=a', '/book/bookclass.html?k=a', '/book/reader/chapter?bookId=1', '/book/100/200.html']) assert.equal(limit.isRateLimitedPath(p), true);
  for (const p of ['/', '/book/100.html', '/book/indexList-100.html', '/book/reader/catalog', '/javascript/site/api.js', '/reader/tts/model.data', 'https://other.example/book/searchByPage']) assert.equal(limit.isRateLimitedPath(p), false);
});
test('Retry-After supports seconds, HTTP dates and a safe ten-second fallback', async() => {
  const env = environment(), {limit} = await env.tab();
  assert.equal(limit.retryAfterSeconds('25', env.now), 25);
  assert.equal(limit.retryAfterSeconds(new Date(env.now + 22000).toUTCString(), env.now), 22);
  for (const value of [null, '', 'bad', '-4', 'NaN']) assert.equal(limit.retryAfterSeconds(value, env.now), 10);
  assert.equal(limit.retryAfterSeconds('999999999', env.now), 86400);
});
test('an HTML 429 from search pauses chapter fetches and prefetch without any new network request', async() => {
  const env = environment(), tab = await env.tab(); let requests = 0;
  tab.context.fetch = async() => { requests++; return blocked(null); };
  await assert.rejects(tab.api.api('/book/searchByPage'), e => e.code === 429 && e.kind === 'rate-limit' && e.message.includes('10 秒'));
  const repo = new tab.Repository();
  await assert.rejects(repo.fetch('100', '200'), e => e.code === 429);
  await assert.rejects(repo.load('100', '201'), e => e.code === 429);
  repo.prefetch({...chapter, nextChapterId:'201'});
  assert.equal(requests, 1); assert.equal(tab.limit.remainingRetrySeconds(), 10);
  env.advance(10000); assert.equal(requests, 1, 'expiry must never automatically retry');
  tab.context.fetch = async() => { requests++; return chapterResponse(); };
  assert.equal((await repo.load('100', '200')).bookIndexId, '200'); assert.equal(requests, 2);
});
test('a chapter 429 blocks search and other chapter IDs, but leaves unrelated APIs working', async() => {
  const env = environment(), tab = await env.tab(); let calls = 0;
  tab.context.fetch = async() => { calls++; return blocked('15'); };
  await assert.rejects(new tab.Repository().fetch('100', '200'), e => e.retryAfter === 15);
  await assert.rejects(tab.api.api('/book/searchByPage', {keyword:'another'}), e => e.code === 429);
  await assert.rejects(new tab.Repository().fetch('300', '400'), e => e.code === 429);
  tab.context.fetch = async() => { calls++; return {ok:true, status:200, json:async() => ({code:200, data:{available:true}})}; };
  assert.equal((await tab.api.api('/site-stats')).available, true); assert.equal(calls, 2);
});
test('tabs receive the shared cooldown and a refreshed document preserves it', async() => {
  const env = environment(), a = await env.tab(), b = await env.tab(); let notices = 0;
  b.limit.onRateLimit(() => notices++); a.limit.rateLimitResponse(blocked('12'), '/book/reader/chapter');
  assert.equal(notices, 1); assert.equal(b.limit.remainingRetrySeconds(), 12);
  const refreshed = await env.tab(); assert.equal(refreshed.limit.remainingRetrySeconds(), 12);
  env.advance(12000); assert.equal(refreshed.limit.remainingRetrySeconds(), 0); assert.equal(notices, 1);
});
test('storage failures do not bypass the cooldown or prevent the friendly 429', async() => {
  const tab = await environment({storageFails:true}).tab();
  const error = tab.limit.rateLimitResponse(blocked(null), '/book/reader/chapter');
  assert.equal(error.code, 429); assert.equal(tab.limit.remainingRetrySeconds(), 10);
  assert.throws(() => tab.limit.assertRequestAllowed('/book/bookclass.html'), e => e.code === 429);
});
test('an existing longer cooldown cannot be shortened by another response', async() => {
  const env = environment(), tab = await env.tab();
  tab.limit.rateLimitResponse(blocked('30'), '/book/reader/chapter'); env.advance(1000);
  tab.limit.rateLimitResponse(blocked('10'), '/book/searchByPage'); assert.equal(tab.limit.remainingRetrySeconds(), 29);
});
test('retry button counts down and only becomes available for a manual retry', async() => {
  const env = environment(), tab = await env.tab(); tab.limit.rateLimitResponse(blocked('10'), '/book/searchByPage');
  const button = {textContent:'重新加载', disabled:false, isConnected:true}; tab.limit.bindRateLimitRetry(button);
  assert.equal(button.disabled, true); assert.equal(button.textContent, '10 秒后可重试');
  env.advance(9000); assert.equal(button.disabled, true); assert.equal(button.textContent, '1 秒后可重试');
  env.advance(1000); assert.equal(button.disabled, false); assert.equal(button.textContent, '重新加载');
});
test('a failing UI listener cannot suppress the 429 or other playback pause listeners', async() => {
  const tab = await environment().tab(); let paused = false;
  tab.limit.onRateLimit(() => { throw Error('UI failure'); }); tab.limit.onRateLimit(() => { paused = true; });
  assert.equal(tab.limit.rateLimitResponse(blocked(null), '/book/reader/chapter').code, 429); assert.equal(paused, true);
});
test('already cancelled chapter fetches keep cancellation semantics during cooldown', async() => {
  const tab = await environment().tab(), abort = new AbortController(); abort.abort();
  tab.limit.rateLimitResponse(blocked(null), '/book/reader/chapter');
  await assert.rejects(new tab.Repository().fetch('100', '200', abort.signal), e => e.name === 'AbortError');
});
for (const name of ['controller', 'global-player']) test(name + ': 429 cancels pending work, pauses playback and preserves current chapter text', () => {
  const source = fs.readFileSync(path.join(root, 'reader', name + '.js'), 'utf8');
  const start = source.indexOf('  rateLimited(error) {'), end = source.indexOf('\n  }', start) + 4;
  const context = {clearInterval() {}}; vm.runInNewContext('class Actual {\n' + source.slice(start, end) + '\n}; globalThis.method = Actual.prototype.rateLimited;', context);
  let cancelled = 0, stopped = 0; const text = [{text:'原正文保持可读'}];
  const session = {chapter:{paragraphs:text}, player:{source:true, position:() => 4, stop:() => stopped++}, op:3,
    playAbort:{abort() {}}, abort:{abort() {}}, repository:{cancel:() => cancelled++}, scheduler:{enabled:true, invalidate() {}},
    pendingNavigation:{id:'201', resume:true}, wantRun:true, save() {}, event() {}, notice() {}, setState(state, message) { this.state = state; this.message = message; }};
  context.method.call(session, {message:'访问过于频繁，请等待 10 秒后重试。', code:429, retryAt:10});
  assert.equal(session.wantRun, false); assert.equal(session.scheduler.enabled, false); assert.equal(cancelled, 1); assert.equal(stopped, 1);
  assert.equal(session.state, 'paused'); assert.equal(session.chapter.paragraphs, text); assert.equal(session.offset, 4); assert.equal(session.op, 4);
  if (name === 'controller') assert.equal(session.pendingNavigation, null);
});
