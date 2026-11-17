'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const ui = require('../../novel-crawl/src/main/resources/static/javascript/crawl-runtime.js');
const controller = fs.readFileSync('novel-crawl/src/main/resources/static/javascript/crawl-source-list.js', 'utf8');

function fixture() {
  const requests = [], intervals = [], listeners = {}, messages = [];
  let html = '';
  function $(selector) { return {text(text) { messages.push(text); return this; }, toggleClass() { return this; },
    html(text) { html = text; return this; }}; }
  $.ajax = options => {
    const request = {options, abort() { options.error?.({}, 'abort'); options.complete?.(); }};
    requests.push(request); return request;
  };
  const document = {hidden: false, querySelectorAll() { return []; }, addEventListener(name, callback) { listeners[name] = callback; }};
  const window = {CrawlRuntime: ui};
  const context = {window, document, $, Set, Date, Number, encodeURIComponent,
    localStorage: {setItem() {}}, location: {}, layer: {alert() {}},
    layui: {use(_, callback) { callback(); }, laypage: {render() {}}},
    setInterval(fn, ms) { intervals.push({fn, ms}); }};
  vm.runInNewContext(controller, context);
  function respond(request, data) { request.options.success({code: 200, data}); request.options.complete(); }
  return {requests, intervals, listeners, document, window, messages, respond, html: () => html};
}
const runtime = {observedAt: 1000000, savedLastMinute: 8, activeDownloads: 1, activeScans: 0, savedSinceStart: 30,
  errorsLastMinute: 0, tasks: [], errors: []};
const source = {id: 14, sourceName: 'UU看书', sourceStatus: 1, chapterCount: 100, runtime};

test('refresh waiting time and actual check/save timestamps remain distinct from enqueue time', () => {
  const html=ui.render({...source,queue:{observedAt:1000000,refreshWaiting:15,discoveryWaiting:900,backfillWaiting:80,
    oldestRefreshDueAt:400000,lastEnqueuedAt:950000,lastCheckedAt:null,lastSavedAt:null}});
  assert.match(html,/连载待检 <strong>15/);assert.match(html,/最久等待 10 分钟/);
  assert.match(html,/实际目录检查 暂无记录/);assert.match(html,/最近追更保存 暂无记录/);
  assert.match(ui.work({kind:'REFRESH',processed:0,total:10}),/连载目录追更/);
});

test('disabled sources distinguish a draining in-flight task from a fully stopped source', () => {
  assert.equal(ui.status({...source, sourceStatus: 0}).text, '正在停止');
  assert.equal(ui.status({...source, sourceStatus: 0, runtime: {...runtime, activeDownloads: 0}}).text, '已停止');
  assert.equal(ui.status({...source, runtime: {...runtime, activeDownloads: 0, activeScans: 2}}).text, '正在扫描');
  assert.equal(ui.status({...source, runtime: {...runtime, activeDownloads: 0}}).text, '等待任务');
});
test('missing runtime is unavailable rather than a misleading zero-rate success', () => {
  assert.match(ui.render({...source, runtime: undefined}), /尚不可用/);
  assert.doesNotMatch(ui.render({...source, runtime: undefined}), /近60秒保存/);
});
test('challenge cooldown and actual successful-write count are shown separately', () => {
  const html = ui.render({...source, runtime: {...runtime, retryAt: runtime.observedAt + 300000}});
  assert.match(html, /等待重试/); assert.match(html, /300 秒/); assert.match(html, /<strong>8<\/strong> 章/);
});
test('untrusted book titles, chapter labels and exception messages cannot create markup', () => {
  const payload = '<img src=x onerror="alert(1)">';
  const html = ui.render({...source, runtime: {...runtime,
    tasks: [{kind: 'UPDATE', bookName: payload, chapter: payload, sourceBookId: payload, processed: 1000, total: 100, saved: 2}],
    errors: [{occurredAt: 0, message: payload}]}});
  assert.doesNotMatch(html, /<img/); assert.match(html, /&lt;img/); assert.match(html, /value="100"/);
  assert.match(html, /目录检查位置不代表整本已采齐/);
});
test('five-second polling skips hidden tabs and never stacks outstanding GET requests', () => {
  const f = fixture();
  assert.equal(f.intervals[0].ms, 5000);
  f.intervals[0].fn(); f.intervals[0].fn();
  assert.equal(f.requests.length, 1);
  f.respond(f.requests[0], {list: [source], total: 1, pageNum: 1, pageSize: 10});
  f.document.hidden = true; f.intervals[0].fn(); assert.equal(f.requests.length, 1);
  f.document.hidden = false; f.listeners.visibilitychange(); assert.equal(f.requests.length, 2);
  assert.ok(f.requests.every(r => r.options.type === 'GET'));
});
test('a failed poll retains last displayed rows and clearly marks them as stale', () => {
  const f = fixture();
  f.respond(f.requests[0], {list: [source], total: 1, pageNum: 1, pageSize: 10});
  const html = f.html(); f.intervals[0].fn();
  f.requests[1].options.error({}, 'timeout'); f.requests[1].options.complete();
  assert.equal(f.html(), html); assert.match(f.messages.at(-1), /连接失败.*保留上次数据/);
});
test('a late pre-toggle GET cannot overwrite the post-save source state', () => {
  const f = fixture(), old = f.requests[0];
  f.window.openOrStopCrawl(14, 1);
  assert.equal(f.requests[1].options.type, 'POST');
  assert.equal(f.requests[1].options.data.sourceStatus, 0);
  f.requests[1].options.success({code: 200});
  assert.equal(f.requests.length, 3);
  f.respond(f.requests[2], {list: [{...source, sourceStatus: 0, runtime: {...runtime, activeDownloads: 0}}], total: 1, pageNum: 1, pageSize: 10});
  const closed = f.html();
  old.options.success({code: 200, data: {list: [source], total: 1, pageNum: 1, pageSize: 10}});
  assert.equal(f.html(), closed); assert.match(closed, /已停止/);
});
test('source metadata is escaped and an empty response clears stale source rows', () => {
  const f = fixture();
  f.respond(f.requests[0], {list: [{...source, sourceName: '<svg/onload=alert(1)>'}], total: 1, pageNum: 1, pageSize: 10});
  assert.doesNotMatch(f.html(), /<svg/);
  f.intervals[0].fn(); f.respond(f.requests[1], {list: [], total: 0, pageNum: 1, pageSize: 10});
  assert.match(f.html(), /暂无爬虫源/); assert.doesNotMatch(f.html(), /runtime-row/);
});
