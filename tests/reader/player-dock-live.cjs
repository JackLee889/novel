const fs = require('node:fs'), path = require('node:path'), assert = require('node:assert/strict');
const {chromium} = require('C:/Users/lylboy/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const run = fs.readFileSync('doc/reader-dock-current.txt', 'utf8').trim();
assert.match(run, /^reader-dock-\d{8}-\d{6}$/);
const out = process.env.NOVEL_TEST_EVIDENCE_DIR ? path.resolve(process.env.NOVEL_TEST_EVIDENCE_DIR) : path.resolve('doc', run), base = process.env.READER_TEST_URL || 'http://127.0.0.1:18987';
assert.ok(out.startsWith(path.resolve('doc') + path.sep) && fs.statSync(out).isDirectory(), 'Evidence directory must exist under doc.');
const route = process.env.READER_TEST_ROUTE || '/book/2106695344219004928/2106695391920824320.html';
const label = process.env.READER_DOCK_REPORT || 'live'; assert.match(label, /^[a-z-]+$/);
const report = {base, route, started: new Date().toISOString(), checks: [], errors: [], blockedWrites: []};
let browser, context, page, engineId, nextId;
const save = () => fs.writeFileSync(path.join(out, label + '-results.json'), JSON.stringify(report, null, 2));
async function check(name, fn) {
  const start = Date.now();
  try { const detail = await fn(); report.checks.push({name, passed: true, ms: Date.now() - start, ...detail}); console.log('PASS', name); }
  catch (error) { report.checks.push({name, passed: false, error: error.stack}); throw error; }
  finally { save(); }
}
async function ready() { await page.waitForFunction(() => !!window.novelGlobalPlayer?.dock); }
async function open() { if (await page.locator('.player-orb').isVisible()) await page.locator('.player-orb').click(); }
async function value() { return page.evaluate(() => { const g = novelGlobalPlayer, s = g.session, d = g.dock;
  return {pinned: d.pinned, expanded: d.expanded, panelHidden: d.panel.hidden, state: s.state,
    engineId: g.engine.engineId, initializations: g.engine.initializations, sourceSerial: s.player.serial,
    stoppedSources: s.player.stats.stopped, contextState: s.player.context?.state,
    sampleRate: s.active?.sampleRate, nonzero: s.active?.samples.some(x => x !== 0), chapterId: s.chapter?.bookIndexId,
    index: s.index, offset: s.player.source ? s.player.position() : s.offset, orbState: d.orb.dataset.state,
    message: s.message, engineState: g.engine.state, resourceStats: g.engine.resourceStats}; }); }
async function audio() {
  await page.waitForFunction(() => ['playing', 'error'].includes(novelGlobalPlayer.session.state), null, {timeout: 150000});
  const v = await value(); (report.audioAttempts ||= []).push(v); save();
  assert.equal(v.state, 'playing', v.message); assert.equal(v.sampleRate, 16000); assert.equal(v.nonzero, true);
  assert.equal(v.initializations, 1); if (engineId) assert.equal(v.engineId, engineId); return v;
}
async function layout() { return page.evaluate(() => {
  const g = novelGlobalPlayer, orb = g.dock.orb, r = orb.getBoundingClientRect(), css = getComputedStyle(orb);
  const numbers = color => color.match(/[\d.]+/g).slice(0, 3).map(Number);
  const luminance = rgb => rgb.map(c => c / 255).map(c => c <= .04045 ? c / 12.92 : ((c + .055) / 1.055) ** 2.4)
    .reduce((sum, c, i) => sum + c * [.2126, .7152, .0722][i], 0);
  const light = [luminance(numbers(css.color)), luminance(numbers(css.backgroundColor))].sort((a, b) => b - a);
  return {width: innerWidth, scrollWidth: document.documentElement.scrollWidth, left: r.left, bottomGap: innerHeight - r.bottom,
    orbWidth: r.width, orbHeight: r.height, contrast: (light[0] + .05) / (light[1] + .05),
    mask: getComputedStyle(orb.querySelector('.player-orb-book')).maskImage,
    controls: [...g.dock.panel.querySelectorAll('button,input[type=range],a')].filter(n => n.getClientRects().length)
      .map(n => ({text: n.getAttribute('aria-label') || n.textContent, width: n.getBoundingClientRect().width,
        height: n.getBoundingClientRect().height}))};
  }); }
(async () => {
  try {
    browser = await chromium.launch({headless: true, channel: 'chrome'}); report.browser = browser.version();
    context = await browser.newContext({viewport: {width: 1440, height: 1000}});
    await context.route('**/*', route => {
      const request = route.request(), url = new URL(request.url());
      if (url.origin !== base) return route.abort();
      if (!['GET', 'HEAD', 'OPTIONS'].includes(request.method())) {
        report.blockedWrites.push({method: request.method(), path: url.pathname}); return route.abort();
      }
      return route.continue();
    });
    page = await context.newPage(); page.on('pageerror', error => report.errors.push(error.message));
    await page.goto(base + route, {waitUntil: 'domcontentloaded'}); await ready();
    nextId = await page.evaluate(() => novelReader.chapter.nextChapterId);
    await check('默认只显示左下角书本听书圆球，控制栏不遮挡正文', async () => {
      const v = await value(), l = await layout();
      assert.equal(v.pinned, false); assert.equal(v.expanded, false); assert.equal(v.panelHidden, true);
      assert.ok(l.left >= 0 && l.left < 30); assert.ok(l.bottomGap >= 14 && l.bottomGap < 30);
      assert.equal(l.orbWidth, 64); assert.equal(l.orbHeight, 64); assert.ok(l.contrast >= 4.5); assert.match(l.mask, /book.svg/);
      await page.screenshot({path: path.join(out, label + '-reader-orb.png')}); return {...v, ...l};
    });
    await check('点击圆球展开，固定后持续显示，取消固定立即收回', async () => {
      await open(); assert.equal((await value()).expanded, true);
      await page.getByRole('button', {name: '固定播放器', exact: true}).click();
      await page.waitForTimeout(3400); const fixed = await value(); assert.equal(fixed.pinned, true); assert.equal(fixed.panelHidden, false);
      await page.screenshot({path: path.join(out, label + '-reader-pinned.png')});
      await page.getByRole('button', {name: '取消固定播放器', exact: true}).click();
      assert.equal((await value()).expanded, false); return {fixed, unpinnedCollapsed: true};
    });
    await check('未固定时三秒自动收起，键盘可展开并用 Escape 收起', async () => {
      await open(); await page.waitForFunction(() => !novelGlobalPlayer.dock.expanded, null, {timeout: 6000});
      await page.locator('.player-orb').focus(); await page.keyboard.press('Enter');
      assert.equal((await value()).expanded, true);
      await page.waitForTimeout(3400); assert.equal((await value()).expanded, true);
      await page.keyboard.press('Escape'); assert.equal((await value()).expanded, false);
      assert.equal(await page.evaluate(() => document.activeElement.classList.contains('player-orb')), true);
      return {autoCollapseMs: 3000, keyboardFocusPreserved: true};
    });
    await check('圆球状态随朗读变化，收起和展开不停止声音或重新初始化', async () => {
      await open(); await page.locator('[data-action=play]').click(); const first = await audio(); engineId = first.engineId;
      await page.locator('#chapter-title').click();
      assert.equal((await value()).panelHidden, true); assert.equal((await value()).orbState, 'playing');
      const before = await value(); await open(); const after = await value();
      assert.equal(after.stoppedSources, before.stoppedSources); assert.equal(after.contextState, 'running');
      assert.equal(after.engineId, engineId); assert.equal(after.initializations, 1);
      await page.getByRole('button', {name: '暂停朗读', exact: true}).click();
      await page.getByRole('button', {name: '收起播放器', exact: true}).click();
      assert.equal((await value()).orbState, 'paused'); return {engineId, initializations: 1, effectivePcm: true};
    });
    await check('固定偏好和共享模型在刷新与内部换章后保留', async () => {
      await open(); await page.getByRole('button', {name: '固定播放器', exact: true}).click();
      await page.reload({waitUntil: 'domcontentloaded'}); await ready();
      await page.waitForFunction(() => novelReader.engine.state === 'ready');
      assert.equal((await value()).pinned, true); assert.equal((await value()).engineId, engineId);
      await page.locator('[data-chapter=next]').click();
      await page.waitForFunction(id => novelReader.chapter.bookIndexId === id, nextId);
      assert.equal((await value()).expanded, true); assert.equal((await value()).pinned, true);
      await page.getByRole('button', {name: '取消固定播放器', exact: true}).click();
      await page.reload({waitUntil: 'domcontentloaded'}); await ready();
      const v = await value(); assert.equal(v.pinned, false); assert.equal(v.panelHidden, true);
      assert.equal(v.initializations, 1); return {...v, pinPreferencePersisted: true};
    });
    await check('手机日间和夜间圆球与完整控制栏均无溢出、按钮可触达', async () => {
      for (const width of [390, 320]) for (const theme of ['light', 'dark']) {
        await page.setViewportSize({width, height: 844});
        await page.evaluate(theme => novelReader.changeSetting('theme', theme), theme);
        const collapsed = await layout(); assert.ok(collapsed.scrollWidth <= width + 1); assert.ok(collapsed.contrast >= 4.5);
        assert.equal(collapsed.orbWidth, 60); assert.equal(collapsed.orbHeight, 60);
        await page.screenshot({path: path.join(out, `${label}-reader-${width}-${theme}-orb.png`)});
        await open(); const expanded = await layout(); assert.ok(expanded.scrollWidth <= width + 1);
        for (const control of expanded.controls) { assert.ok(control.height >= 44, JSON.stringify(control)); assert.ok(control.width >= 44, JSON.stringify(control)); }
        await page.screenshot({path: path.join(out, `${label}-reader-${width}-${theme}-expanded.png`)});
        await page.getByRole('button', {name: '收起播放器', exact: true}).click();
      }
      await context.setDefaultTimeout(30000); await page.emulateMedia({reducedMotion: 'reduce'});
      assert.equal(await page.evaluate(() => getComputedStyle(document.querySelector('.player-orb-waves i')).animationName), 'none');
      return {widths: [390, 320], themes: ['light', 'dark'], minTarget: 44, reducedMotion: true};
    });
    await check('首页全局播放器采用相同圆球与固定规则，可继续真实章节', async () => {
      await page.setViewportSize({width: 1440, height: 1000}); await page.goto(base + '/', {waitUntil: 'domcontentloaded'}); await ready();
      assert.equal((await value()).panelHidden, true); await open();
      await page.getByRole('button', {name: '继续听书', exact: true}).click(); await audio();
      await open(); await page.getByRole('button', {name: '暂停听书', exact: true}).click();
      await page.getByRole('button', {name: '固定播放器', exact: true}).click();
      await page.reload({waitUntil: 'domcontentloaded'}); await ready();
      assert.equal((await value()).pinned, true); assert.equal((await value()).initializations, 1);
      await page.getByRole('button', {name: '取消固定播放器', exact: true}).click();
      await page.screenshot({path: path.join(out, label + '-home-orb.png')});
      assert.equal(report.errors.length, 0, report.errors.join('\n'));
      return {engineId, initializations: 1, globalControlsVerified: true};
    });
    report.passed = report.checks.every(c => c.passed);
  } catch (error) { report.passed = false; report.failure = error.stack; console.error(error); process.exitCode = 1; }
  finally { report.completed = new Date().toISOString(); save(); await browser?.close(); }
})();
