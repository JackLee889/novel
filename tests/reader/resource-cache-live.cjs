const fs = require('node:fs'), path = require('node:path'), assert = require('node:assert/strict');
const {chromium} = require('C:/Users/lylboy/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const run = fs.readFileSync('doc/reader-cache-current.txt', 'utf8').trim();
assert.match(run, /^reader-cache-\d{8}-\d{6}$/);
const out = path.resolve('doc', run), base = process.env.READER_TEST_URL || 'http://127.0.0.1:18987';
const route = process.env.READER_TEST_ROUTE || '/book/2106695344609075200/2106695397037875200.html';
const label = process.env.READER_CACHE_REPORT || 'live';
assert.match(label, /^[a-z-]+$/);
const report = {started: new Date().toISOString(), base, route, checks: [], requests: [], states: [], errors: [], blockedWrites: []};
const profile = path.join(out, 'chrome-profile-' + label);
let context, page, phase = 'cold', blockAssets = false;
const save = () => fs.writeFileSync(path.join(out, label + '-results.json'), JSON.stringify(report, null, 2));
async function check(name, fn) {
  const start = Date.now();
  try { const detail = await fn(); report.checks.push({name, passed: true, ms: Date.now() - start, ...detail}); console.log('PASS', name); }
  catch (error) { report.checks.push({name, passed: false, error: error.stack}); throw error; }
  finally { save(); }
}
async function launch() {
  context = await chromium.launchPersistentContext(profile, {headless: true, channel: 'chrome', viewport: {width: 1440, height: 1000}});
  await context.route('**/*', route => {
    const request = route.request(), url = new URL(request.url());
    if (url.origin !== base) return route.abort();
    if (!['GET', 'HEAD', 'OPTIONS'].includes(request.method())) {
      report.blockedWrites.push({method: request.method(), path: url.pathname}); return route.abort();
    }
    if (blockAssets && url.pathname.startsWith('/reader/tts/')) return route.abort();
    return route.continue();
  });
  page = context.pages()[0] || await context.newPage();
  page.on('pageerror', error => report.errors.push(error.message));
  page.on('request', request => report.requests.push({phase, url: request.url(), method: request.method()}));
}
async function entered(reload = false) {
  if (reload) await page.reload({waitUntil: 'domcontentloaded'});
  else await page.goto(base + route, {waitUntil: 'domcontentloaded'});
  await page.waitForFunction(() => !!window.novelReader);
  await page.evaluate(() => {
    window.cacheTestStates = [];
    new MutationObserver(() => cacheTestStates.push(document.querySelector('#audio-state').textContent))
      .observe(document.querySelector('#audio-state'), {childList: true, subtree: true, characterData: true});
  });
}
async function playing() {
  await page.waitForFunction(() => novelReader.state === 'playing' || novelReader.state === 'error', null, {timeout: 150000});
  const detail = await page.evaluate(() => ({state: novelReader.state, message: document.querySelector('#audio-state').textContent,
    initializations: novelReader.engine.initializations, resources: novelReader.engine.resourceStats,
    chapterId: novelReader.chapter.bookIndexId, sourceSerial: novelReader.player.serial, starts: novelReader.player.stats.starts,
    sampleRate: novelReader.active?.sampleRate, nonzero: novelReader.active?.samples.some(x => x !== 0),
    duration: novelReader.active?.duration, cachedAudio: novelReader.scheduler.usage()}));
  assert.equal(detail.state, 'playing', detail.message); assert.equal(detail.sampleRate, 16000); assert.equal(detail.nonzero, true);
  report.states.push({phase, messages: await page.evaluate(() => cacheTestStates.splice(0))});
  return detail;
}
const pause = () => page.getByRole('button', {name: '暂停朗读', exact: true}).click();
const start = () => page.locator('[data-action="play"]').click();
const assets = () => report.requests.filter(r => new URL(r.url).pathname.startsWith('/reader/tts/'));
(async () => {
  try {
    await launch(); await entered();
    const original = await page.evaluate(() => ({bookId: novelReader.chapter.bookId, chapterId: novelReader.chapter.bookIndexId,
      nextId: novelReader.chapter.nextChapterId, access: novelReader.chapter.access, version: novelReader.chapter.contentVersion}));
    report.chapter = original;
    await check('首次准备缓存四个真实模型文件并合成有效 PCM', async () => {
      assert.equal(await page.evaluate(() => novelReader.engine.state), 'idle'); assert.equal(assets().length, 0);
      await start(); const cold = await playing();
      assert.equal(cold.initializations, 1); assert.equal(cold.resources.downloadedAssets, 4);
      assert.equal(cold.resources.cacheHits, 0); assert.equal(cold.resources.persistent, true);
      assert.equal(cold.resources.downloadedBytes, 163103220); assert.equal(assets().length, 4);
      await pause(); return cold;
    });
    phase = 'repeat';
    await check('连续暂停继续三次复用同一个模型和音频源', async () => {
      const serial = await page.evaluate(() => novelReader.player.serial);
      for (let i = 0; i < 3; i++) {
        await start(); const resumed = await playing(); assert.equal(resumed.initializations, 1);
        assert.equal(resumed.sourceSerial, serial); await pause();
      }
      assert.equal(assets().length, 4); return {cycles: 3, extraAssetRequests: 0, initializations: 1};
    });
    await check('停止后再次播放保持模型就绪，不重复取资源', async () => {
      await page.getByRole('button', {name: '停止', exact: true}).click(); await start();
      const stopped = await playing(); assert.equal(stopped.initializations, 1); assert.equal(assets().length, 4);
      await pause(); return {initializations: 1, extraAssetRequests: 0};
    });
    await check('真实章节切换复用模型，并保留本章权限校验', async () => {
      assert.ok(original.nextId); await page.locator('[data-chapter="next"]').click();
      await page.waitForFunction(id => novelReader.chapter.bookIndexId === id && novelReader.state === 'paused', original.nextId);
      await start(); const next = await playing(); assert.equal(next.chapterId, original.nextId);
      assert.equal(next.initializations, 1); assert.equal(assets().length, 4); await pause();
      await page.locator('[data-chapter="previous"]').click();
      await page.waitForFunction(id => novelReader.chapter.bookIndexId === id && novelReader.state === 'paused', original.chapterId);
      const response = await context.request.get(base + `/book/reader/chapter?bookId=${original.bookId}&bookIndexId=${original.chapterId}`);
      assert.match(response.headers()['cache-control'], /private.*no-store/);
      const chapter = (await response.json()).data; assert.equal(chapter.contentVersion, original.version);
      return {nextId: original.nextId, initializations: 1, chapterReauthorized: true};
    });
    phase = 'reload'; blockAssets = true;
    await check('刷新后拦截所有模型下载，仍从本地缓存完成初始化与播放', async () => {
      await entered(true); const before = assets().length; await start(); const warm = await playing();
      assert.equal(warm.resources.cacheHits, 4); assert.equal(warm.resources.downloadedAssets, 0);
      assert.equal(warm.resources.downloadedBytes, 0); assert.equal(assets().length, before);
      assert.ok(report.states.at(-1).messages.some(m => /已缓存.*初始化/.test(m)));
      assert.ok(!report.states.at(-1).messages.some(m => /获取并缓存|正在加载语音资源/.test(m)));
      await page.screenshot({path: path.join(out, label + '-cached-playing.png')}); await pause();
      return {...warm, assetNetworkDisabled: true, extraAssetRequests: 0};
    });
    await context.close(); context = null; phase = 'browser-reopen';
    await launch(); await entered();
    await check('关闭测试浏览器后重新打开，四个资源缓存仍可复用', async () => {
      const before = assets().length; await start(); const reopened = await playing();
      assert.equal(reopened.resources.cacheHits, 4); assert.equal(reopened.resources.downloadedAssets, 0);
      assert.equal(assets().length, before); await pause();
      assert.equal(report.errors.length, 0);
      const authRequests = report.requests.filter(r => new URL(r.url).pathname === '/book/reader/chapter');
      assert.ok(authRequests.length >= 9);
      return {cacheHits: 4, downloadedBytes: 0, assetNetworkDisabled: true, authorizationRequests: authRequests.length};
    });
    report.passed = report.checks.every(c => c.passed);
  } catch (error) { report.passed = false; report.failure = error.stack; console.error(error); process.exitCode = 1; }
  finally { report.completed = new Date().toISOString(); save(); await context?.close(); }
})();
