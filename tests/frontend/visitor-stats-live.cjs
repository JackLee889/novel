const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { chromium } = require('C:/Users/lylboy/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const base = process.env.VISITOR_QA_BASE || 'http://127.0.0.1:18988';
assert.ok(/^http:\/\/127\.0\.0\.1:\d+$/.test(base), 'Only the isolated loopback preview may be tested');
const out = path.resolve('doc/visitor-stats-20261009');
const report = { base, checks: [], errors: [], started: new Date().toISOString(), scope: 'Original book fixtures; real isolated MySQL and Redis visitor statistics' };
const check = text => { report.checks.push(text); console.log(text); };
async function stats() { const result = await (await fetch(base + '/site-stats')).json(); assert.equal(Number(result.code), 200); return result.data; }
async function verifyRows(page, width, footer = false) {
  const rects = await page.locator('.site-stats-item').evaluateAll(items => items.map(item => {
    const rect = item.getBoundingClientRect(); return {top:rect.top,left:rect.left};
  }));
  assert.equal(rects.length, 2);
  if (width > 800) assert.ok(rects[1].top > rects[0].top, 'Desktop retains two statistic rows');
  else assert.ok(Math.abs(rects[1].top-rects[0].top) <= 1 && rects[1].left > rects[0].left, 'Mobile uses one statistic row');
  if (footer && width <= 800) assert.ok(await page.locator('[data-site-stats]').evaluate(node => {
    const feedback=node.parentElement.querySelector('.footer-links a[href="/user/feedback.html"]').getBoundingClientRect(),rect=node.getBoundingClientRect();
    return rect.left >= feedback.right && Math.abs((rect.top+rect.height/2)-(feedback.top+feedback.height/2)) <= 1;
  }), 'Mobile statistics follow the feedback link on the same line');
}
async function open(page, route) {
  const ready = page.waitForResponse(r => r.url().includes('/site-stats/visit') && r.request().method() === 'POST' && r.status() === 200);
  await page.goto(base + route);
  const data = (await (await ready).json()).data;
  assert.equal(data.available, true); assert.equal(data.onlineAvailable, true);
  await page.locator('[data-site-stats][aria-busy]').waitFor({ state: 'detached' });
  return data;
}
(async () => {
  const browser = await chromium.launch({headless: true, channel: 'chrome'});
  const context = await browser.newContext({viewport: {width: 1440, height: 850}});
  await context.route('**/hm.baidu.com/**', route => route.abort());
  const page = await context.newPage();
  const visits = [];
  page.on('pageerror', error => report.errors.push(error.message));
  page.on('request', request => { if (request.url().endsWith('/site-stats/visit')) visits.push(request.postData()); });
  try {
    const initial = await stats();
    await stats(); assert.deepEqual(await stats(), initial); check('GET snapshot does not count a visit');
    const first = await open(page, '/');
    assert.equal(BigInt(first.visitors), BigInt(initial.visitors) + 1n);
    assert.equal(BigInt(first.pageViews), BigInt(initial.pageViews) + 1n); check('New browser adds one UV and one PV');
    const sameTab = await context.newPage();
    const second = await open(sameTab, '/book/bookclass.html');
    assert.equal(second.visitors, first.visitors); assert.equal(BigInt(second.pageViews), BigInt(first.pageViews) + 1n);
    assert.equal(second.online, first.online); check('Same browser across pages and tabs counts one visitor');
    await sameTab.close(); await page.bringToFront();
    const viewForm = new URLSearchParams(visits[0]);
    const retry = await context.request.post(base + '/site-stats/visit', {form: Object.fromEntries(viewForm), headers: {Origin: base}});
    assert.equal(retry.status(), 200); assert.equal((await retry.json()).data.pageViews, second.pageViews); check('Retried pageViewId does not increment PV');
    const beforeHeartbeat = await stats();
    const heartbeat = page.waitForResponse(r => r.url().endsWith('/site-stats/heartbeat'), {timeout: 40000});
    await heartbeat;
    const afterHeartbeat = await stats();
    assert.equal(afterHeartbeat.visitors, beforeHeartbeat.visitors); assert.equal(afterHeartbeat.pageViews, beforeHeartbeat.pageViews); check('Actual 30-second browser heartbeat keeps UV/PV unchanged');
    const reader = await open(page, '/book/9007199254740993/9007199254741001.html');
    await page.waitForFunction(() => !!window.novelReader);
    assert.equal(reader.visitors, first.visitors); check('Independent reader participates in the same site totals');
    const nextVisit = page.waitForResponse(r => r.url().endsWith('/site-stats/visit') && r.request().method() === 'POST');
    await page.locator('[data-chapter="next"]').click();
    const chapter = (await (await nextVisit).json()).data;
    assert.equal(BigInt(chapter.pageViews), BigInt(reader.pageViews) + 1n); assert.equal(chapter.visitors, reader.visitors); check('Successful reader SPA chapter change adds exactly one PV');
    const current = await stats();
    await page.evaluate(() => window.dispatchEvent(new CustomEvent('novel:chapter-view')));
    await page.waitForTimeout(150);
    assert.equal((await stats()).pageViews, current.pageViews); check('Duplicate chapter event does not add PV');
    await page.locator('[data-action="focus"]').click();
    assert.equal(await page.locator('[data-site-stats]').isVisible(), false);
    await page.locator('[data-action="focus"]').evaluate(node => node.click()); check('Focus reading mode hides the statistics strip');
    for (const width of [1440, 390, 320]) {
      await page.setViewportSize({width, height: 850});
      for (const theme of ['light', 'dark']) {
        await page.evaluate(value => novelReader.changeSetting('theme', value), theme);
        await page.locator('[data-site-stats]').scrollIntoViewIfNeeded();
        assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
        assert.equal(await page.locator('[data-site-stats] details, [data-site-stats] summary').count(), 0);
        assert.equal(await page.locator('[data-site-stats] .site-stats-item').count(), 2);
        assert.equal(await page.locator('[data-stats-visitors], [data-stats-full-visitors], [data-stats-unit-visitors]').count(), 0);
        await verifyRows(page, width);
        if (width !== 320) await page.locator('[data-site-stats]').screenshot({path: path.join(out, `reader-${width}-${theme}.png`)});
        check(`Reader statistics layout ${width}px ${theme}`);
      }
    }
    await page.setViewportSize({width: 1440, height: 850}); await open(page, '/');
    for (const width of [1440, 390, 320]) {
      await page.setViewportSize({width, height: 850});
      for (const theme of ['light', 'dark']) {
        await page.evaluate(value => {document.documentElement.dataset.theme = value; localStorage.setItem('novel.site.theme', value);}, theme);
        await page.locator('[data-site-stats]').scrollIntoViewIfNeeded();
        assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
        assert.ok(await page.locator('[data-site-stats]').evaluate(node => node.getBoundingClientRect().height <= 64));
        assert.equal(await page.locator('.site-stats-item').count(), 2);
        assert.equal(await page.locator('[data-site-stats] details, [data-site-stats] summary').count(), 0);
        assert.equal(await page.locator('[data-stats-visitors], [data-stats-full-visitors], [data-stats-unit-visitors]').count(), 0);
        assert.ok(await page.locator('[data-site-stats]').evaluate(node => node.parentElement?.classList.contains('footer-inner') === true));
        await verifyRows(page, width, true);
        if (width !== 320) await page.locator('[data-site-stats]').screenshot({path: path.join(out, `home-${width}-${theme}.png`)});
        check(`Home statistics layout ${width}px ${theme}`);
      }
    }
    const badOrigin = await context.request.post(base + '/site-stats/visit', {form: Object.fromEntries(viewForm), headers: {Origin: 'https://unrelated.invalid'}});
    assert.equal(badOrigin.status(), 403); check('Cross-origin count injection rejected');
    const independent = await browser.newContext(); await independent.route('**/hm.baidu.com/**', route => route.abort());
    const third = await open(await independent.newPage(), '/');
    const beforeThird = BigInt((await stats()).visitors); assert.equal(BigInt(third.visitors), BigInt(first.visitors) + 1n);
    await independent.close(); check('Independent browser adds one new visitor');
    await context.route('**/site-stats**', route => route.abort());
    await page.reload();
    await page.waitForFunction(() => document.querySelector('[data-stats-status]').textContent.includes('暂不可用'));
    assert.equal(await page.locator('[data-stats-online]').textContent(), '—');
    assert.ok(await page.locator('.site-search').isVisible()); check('Statistics outage shows unavailable while book browsing remains usable');
    await context.unroute('**/site-stats**');
    await page.evaluate(() => window.dispatchEvent(new Event('online')));
    await page.waitForFunction(() => document.querySelector('[data-site-stats]').dataset.online === 'true');
    assert.equal(BigInt((await stats()).visitors), beforeThird); check('Network recovery retains the same anonymous visitor identity');
    report.final = await stats(); assert.deepEqual(report.errors, []); report.success = true;
  } catch (error) { report.error = error.stack; process.exitCode = 1; console.error(error.stack); }
  finally { report.completed = new Date().toISOString(); fs.writeFileSync(path.join(out, 'browser-live.json'), JSON.stringify(report, null, 2)); await browser.close(); }
  console.log(JSON.stringify({success: report.success, checks: report.checks.length, errors: report.errors}));
})();
