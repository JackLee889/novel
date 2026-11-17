const fs = require('node:fs'), path = require('node:path'), assert = require('node:assert/strict');
const {chromium} = require('C:/Users/lylboy/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const run = fs.readFileSync('doc/reader-global-current.txt', 'utf8').trim();
assert.match(run, /^reader-global-\d{8}-\d{6}$/);
const out = process.env.NOVEL_TEST_EVIDENCE_DIR ? path.resolve(process.env.NOVEL_TEST_EVIDENCE_DIR) : path.resolve('doc', run), base = process.env.READER_TEST_URL || 'http://127.0.0.1:18987';
assert.ok(out.startsWith(path.resolve('doc') + path.sep) && fs.statSync(out).isDirectory(), 'Evidence directory must exist under doc.');
const readerRoute = process.env.READER_TEST_ROUTE || '/book/2106695344609075200/2106695397037875200.html';
const label = process.env.READER_GLOBAL_REPORT || 'live';
assert.match(label, /^[a-z-]+$/);
const report = {started: new Date().toISOString(), base, readerRoute, checks: [], requests: [], errors: [], blockedWrites: []};
let browser, context, page, phase = 'cold', identity, original;
const save = () => fs.writeFileSync(path.join(out, label + '-results.json'), JSON.stringify(report, null, 2));
async function check(name, fn) {
  const start = Date.now();
  try { const detail = await fn(); report.checks.push({name, passed: true, ms: Date.now() - start, ...detail}); console.log('PASS', name); }
  catch (error) { report.checks.push({name, passed: false, error: error.stack}); throw error; }
  finally { save(); }
}
function watch(tab) {
  tab.on('pageerror', error => report.errors.push(error.message));
  tab.on('request', request => report.requests.push({phase, url: request.url(), method: request.method()}));
}
const assets = () => report.requests.filter(r => new URL(r.url).pathname.startsWith('/reader/tts/'));
async function entered(tab = page, url = base + readerRoute) {
  await tab.goto(url, {waitUntil: 'domcontentloaded'});
  await tab.waitForFunction(() => !!window.novelReader && !!novelReader.engine.clientId);
}
async function state(tab = page) {
  return tab.evaluate(() => {
    const g = novelGlobalPlayer, r = g.session, e = g.engine;
    return {state: r.state, message: r.message || document.querySelector('#audio-state')?.textContent,
      shared: e.shared, engineId: e.engineId, initializations: e.initializations, engineState: e.state,
      resources: e.resourceStats, bookId: r.chapter?.bookId, chapterId: r.chapter?.bookIndexId,
      index: r.index, offset: r.player.source ? r.player.position() : r.offset,
      sampleRate: r.active?.sampleRate, nonzero: r.active?.samples.some(x => x !== 0),
      duration: r.active?.duration, wantRun: r.wantRun, owned: g.owned,
      contextState: r.player.context?.state, sourceSerial: r.player.serial};
  });
}
async function playing(tab = page) {
  await tab.waitForFunction(() => ['playing', 'error'].includes(novelGlobalPlayer.session.state), null, {timeout: 150000});
  const value = await state(tab); assert.equal(value.state, 'playing', value.message);
  assert.equal(value.sampleRate, 16000); assert.equal(value.nonzero, true); assert.equal(value.shared, true);
  if (identity) { assert.equal(value.engineId, identity); assert.equal(value.initializations, 1); }
  return value;
}
async function showControls(tab = page) {
  const orb = tab.locator('.player-orb'); if (await orb.isVisible()) await orb.click();
}
const start = async (tab = page) => { await showControls(tab); await tab.locator('[data-action="play"]').click(); };
const pause = async (tab = page) => { await showControls(tab); await tab.getByRole('button', {name: '暂停朗读', exact: true}).click(); };
const homeControl = async name => { await showControls(); await page.getByRole('button', {name, exact: true}).click(); };
async function warm(tab = page) {
  await tab.waitForFunction(() => novelGlobalPlayer.engine.state === 'ready', null, {timeout: 15000});
  const value = await state(tab); assert.equal(value.engineId, identity); assert.equal(value.initializations, 1);
  assert.equal(value.resources.reused, true); assert.equal(value.resources.downloadedBytes, 0);
  return value;
}
(async () => {
  try {
    browser = await chromium.launch({headless: true, channel: 'chrome'});
    report.browser = browser.version();
    context = await browser.newContext({viewport: {width: 1440, height: 1000}});
    await context.route('**/*', route => {
      const request = route.request(), url = new URL(request.url());
      if (url.origin !== base) return route.abort();
      if (!['GET', 'HEAD', 'OPTIONS'].includes(request.method())) {
        report.blockedWrites.push({method: request.method(), path: url.pathname}); return route.abort();
      }
      return route.continue();
    });
    page = await context.newPage(); watch(page); await entered();
    original = await page.evaluate(() => ({bookId: novelReader.chapter.bookId, chapterId: novelReader.chapter.bookIndexId,
      nextId: novelReader.chapter.nextChapterId, version: novelReader.chapter.contentVersion, access: novelReader.chapter.access}));
    report.chapter = original;
    await check('首次点击才初始化一个共享模型，生成有效中文 PCM', async () => {
      assert.equal((await state()).engineState, 'idle'); assert.equal(assets().length, 0);
      await start(); const first = await playing(); identity = first.engineId;
      assert.ok(identity); assert.equal(first.initializations, 1); assert.equal(first.resources.downloadedAssets, 4);
      assert.equal(first.resources.downloadedBytes, 163103220); await pause(); return first;
    });
    phase = 'reuse';
    await check('暂停继续和内部章节切换复用已初始化模型', async () => {
      const serial = (await state()).sourceSerial;
      for (let i = 0; i < 3; i++) { await start(); const s = await playing(); assert.equal(s.sourceSerial, serial); await pause(); }
      await page.locator('[data-chapter="next"]').click();
      await page.waitForFunction(id => novelReader.chapter.bookIndexId === id && novelReader.state === 'paused', original.nextId);
      await start(); const next = await playing(); assert.equal(next.chapterId, original.nextId); await pause();
      await page.locator('[data-chapter="previous"]').click();
      await page.waitForFunction(id => novelReader.chapter.bookIndexId === id && novelReader.state === 'paused', original.chapterId);
      await start(); await playing(); await pause();
      return {engineId: identity, initializations: 1, cycles: 3, nextId: original.nextId};
    });
    phase = 'refresh';
    await check('连续五次整页刷新保持相同引擎实例、初始化次数与朗读位置', async () => {
      const before = await state(), values = [];
      for (let i = 0; i < 5; i++) {
        await page.reload({waitUntil: 'domcontentloaded'});
        await page.waitForFunction(() => !!window.novelReader && !!novelReader.engine.clientId);
        const s = await warm(); assert.equal(s.index, before.index); assert.ok(Math.abs(s.offset - before.offset) < 0.5);
        assert.equal(s.state, 'paused'); values.push(s);
      }
      await start(); await playing(); await pause(); return {engineId: identity, initializations: 1, refreshes: values};
    });
    phase = 'full-chapter';
    await check('整页跳转到另一个章节无需再次初始化模型', async () => {
      await entered(page, base + `/book/${original.bookId}/${original.nextId}.html`);
      await warm(); await start(); const s = await playing(); await pause();
      return {engineId: s.engineId, initializations: s.initializations, chapterId: s.chapterId};
    });
    phase = 'global-pages';
    await check('首页全局播放器接续正文朗读，并重新验证章节权限', async () => {
      const before = await state(); await page.goto(base + '/', {waitUntil: 'domcontentloaded'});
      await page.waitForFunction(() => !!window.novelGlobalPlayer && !!novelGlobalPlayer.engine.clientId);
      await warm(); await homeControl('继续听书');
      const s = await playing(); assert.equal(s.chapterId, original.nextId); assert.equal(s.index, before.index);
      await homeControl('暂停听书');
      await page.screenshot({path: path.join(out, label + '-global-desktop.png')});
      for (const width of [390, 320]) {
        await page.setViewportSize({width, height: 844});
        await showControls();
        const layout = await page.evaluate(() => ({width: innerWidth, scrollWidth: document.documentElement.scrollWidth,
          buttons: [...document.querySelectorAll('.global-player button,.global-player-back')].map(n => ({
            text: n.textContent, width: n.getBoundingClientRect().width, height: n.getBoundingClientRect().height}))}));
        assert.ok(layout.scrollWidth <= layout.width + 1, JSON.stringify(layout));
        for (const b of layout.buttons) { assert.ok(b.width >= 44); assert.ok(b.height >= 44); }
        report.checks.push({name: `${width}px 全局控件布局`, passed: true, ...layout});
      }
      await page.screenshot({path: path.join(out, label + '-global-mobile.png')});
      await page.setViewportSize({width: 1440, height: 1000});
      const saved = await page.evaluate(() => JSON.parse(localStorage.getItem('novel.reader.v1.global-session')));
      assert.ok(!('paragraphs' in saved) && !('samples' in saved) && !('text' in saved));
      return {...s, persistentRecordContainsOnlyPosition: true};
    });
    await check('全局播放器遇到新的拒绝授权响应时清除旧正文和音频', async () => {
      const pattern = '**/book/reader/chapter?**';
      const denial = async route => {
        const response = await route.fetch(), payload = await response.json();
        payload.code = 1001; payload.data.access = 'need_login'; payload.data.paragraphs = [];
        await route.fulfill({response, json: payload});
      };
      await context.route(pattern, denial);
      try {
        await homeControl('继续听书');
        await page.waitForFunction(() => novelGlobalPlayer.session.message === '本章需要登录和订阅');
        const denied = await page.evaluate(() => { const s = novelGlobalPlayer.session;
          return {wantRun: s.wantRun, source: !!s.player.source, segments: s.segments.length, cache: s.scheduler.usage()}; });
        assert.equal(denied.wantRun, false); assert.equal(denied.source, false);
        assert.equal(denied.segments, 0); assert.equal(denied.cache.count, 0);
        return {...denied, simulatedDenial: true, engineId: identity, initializations: 1};
      } finally { await context.unroute(pattern, denial); }
    });
    await check('普通页面刷新后仍可继续听书并返回章节', async () => {
      await page.reload({waitUntil: 'domcontentloaded'});
      await page.waitForFunction(() => !!window.novelGlobalPlayer && !!novelGlobalPlayer.engine.clientId);
      await warm(); await homeControl('继续听书'); await playing();
      await homeControl('暂停听书');
      await page.getByRole('link', {name: '回到阅读页', exact: true}).click();
      await page.waitForFunction(() => !!window.novelReader && !!novelReader.engine.clientId); await warm();
      assert.equal((await state()).chapterId, original.nextId);
      return {engineId: identity, initializations: 1};
    });
    phase = 'multi-tab';
    await check('同源两个标签页共享模型，接续听书时暂停原页面', async () => {
      const other = await context.newPage(); watch(other); await entered(other);
      await warm(other); await start(other); await playing(other);
      await start(); const s = await playing();
      await other.waitForFunction(() => !novelReader.wantRun && novelReader.state === 'paused');
      const yielded = await state(other); assert.equal(yielded.contextState, 'suspended');
      await pause(); await other.close();
      return {engineId: identity, initializations: s.initializations, otherTab: yielded};
    });
    await check('各页面继续播放始终只初始化一次模型，业务写请求被拦截', async () => {
      assert.equal((await state()).initializations, 1); assert.equal(report.errors.length, 0, report.errors.join('\n'));
      const authorizationRequests = report.requests.filter(r => new URL(r.url).pathname === '/book/reader/chapter').length;
      assert.ok(authorizationRequests >= 10);
      return {nativeResourceLoadRounds: 1, additionalInitializations: 0,
        authorizationRequests, engineId: identity, initializations: 1};
    });
    phase = 'fallback';
    await check('不支持共享线程时仍能使用缓存完成朗读', async () => {
      const fallback = await context.newPage(); watch(fallback);
      await fallback.addInitScript(() => { window.SharedWorker = undefined; });
      await fallback.goto(base + readerRoute, {waitUntil: 'domcontentloaded'});
      await fallback.waitForFunction(() => !!window.novelReader);
      await start(fallback);
      await fallback.waitForFunction(() => ['playing', 'error'].includes(novelReader.state), null, {timeout: 150000});
      const s = await state(fallback); assert.equal(s.state, 'playing', s.message);
      assert.ok(!s.shared); assert.equal(s.sampleRate, 16000); assert.equal(s.nonzero, true);
      assert.equal(s.resources.cacheHits, 4); assert.equal(s.resources.downloadedBytes, 0);
      await pause(fallback); await fallback.close();
      return {simulatedMissingSharedWorker: true, cacheHits: 4, sampleRate: 16000, downloadedBytes: 0};
    });
    report.passed = report.checks.every(c => c.passed);
  } catch (error) { report.passed = false; report.failure = error.stack; console.error(error); process.exitCode = 1; }
  finally { report.completed = new Date().toISOString(); save(); await browser?.close(); }
})();
