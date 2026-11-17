// Frontend acceptance with a deliberately injected HTTP 429. Edge enforcement is verified separately.
const fs = require('node:fs'), path = require('node:path'), assert = require('node:assert/strict'), crypto = require('node:crypto');
const {chromium} = require('C:/Users/lylboy/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const root = path.resolve(__dirname, '../..'), out = path.join(root, 'doc/cloudflare-rate-limit-20261020');
const base = process.env.RATE_LIMIT_TEST_BASE || 'https://www.xs001.xyz';
const chapterPath = process.env.RATE_LIMIT_TEST_CHAPTER || '/book/2108671816092606464/2112215461111889920.html';
const hash = value => crypto.createHash('sha256').update(value).digest('hex');
(async() => {
  const report = {base, simulated429:true, checks:[], errors:[], started:new Date().toISOString()};
  const browser = await chromium.launch({headless:true, channel:'chrome'});
  const context = await browser.newContext({viewport:{width:390, height:844}});
  let blocked = false, requests = 0, activePage;
  try {
    await context.route('**/hm.baidu.com/**', route => route.abort());
    await context.route('**/reader/tts/**', route => route.abort());
    await context.route('**/javascript/**', async route => {
      const file = path.join(root, 'novel-front/src/main/resources/static', new URL(route.request().url()).pathname);
      if (file.startsWith(path.join(root, 'novel-front/src/main/resources/static') + path.sep) && fs.existsSync(file))
        return route.fulfill({contentType:'text/javascript; charset=utf-8', body:fs.readFileSync(file)});
      return route.continue();
    });
    await context.route('**/book/reader/chapter?*', route => {
      requests++;
      if (blocked) return route.fulfill({status:429, contentType:'text/html', body:fs.readFileSync(path.join(out, 'tool/429.html'), 'utf8')});
      return route.continue();
    });
    const page = activePage = await context.newPage(); page.on('pageerror', error => report.errors.push(error.message));
    page.on('response', response => { if (new URL(response.url()).pathname === '/book/reader/chapter') (report.chapterResponses ||= []).push({status:response.status(), simulated:blocked}); });
    await page.goto(base + chapterPath, {waitUntil:'domcontentloaded'});
    await page.waitForFunction(() => window.novelReader?.chapter, {timeout:30000});
    if (await page.evaluate(() => !novelReader.chapter.nextChapterId)) {
      await page.evaluate(() => novelReader.navigate(novelReader.chapter.previousChapterId));
      await page.waitForFunction(() => novelReader.chapter.nextChapterId, {timeout:20000});
    }
    const currentPath = new URL(page.url()).pathname;
    blocked = true; requests = 0;
    const original = await page.evaluate(() => ({id:novelReader.chapter.bookIndexId, next:novelReader.chapter.nextChapterId, text:document.querySelector('#reader-prose').textContent}));
    assert.ok(original.text.length > 30); report.originalTextSha256 = hash(original.text);
    await page.locator('[data-chapter="next"]').click();
    await page.waitForFunction(() => novelReader.state === 'paused' && document.querySelector('[data-rate-limit-notice]')?.textContent.includes('访问过于频繁'));
    assert.equal(requests, 1); assert.equal(await page.locator('#reader-prose').textContent(), original.text);
    assert.equal(await page.evaluate(() => novelReader.chapter.bookIndexId), original.id);
    report.checks.push('HTML 429 displays a countdown, pauses the reader and preserves existing text');
    await page.evaluate(async() => {
      novelReader.repository.prefetch(novelReader.chapter);
      await novelReader.navigate(novelReader.chapter.nextChapterId);
      const api = await import('/javascript/site/api.js?v=20261020.1');
      try { await api.api('/book/searchByPage', {keyword:'测试'}); throw Error('Expected cooldown'); }
      catch (error) { if (error.code !== 429) throw error; }
      document.querySelector('form.site-search')?.requestSubmit();
    });
    assert.equal(requests, 1); assert.equal(new URL(page.url()).pathname, currentPath);
    report.checks.push('prefetch, repeated navigation, search fetch and HTML form submit make no request during cooldown');
    const other = await context.newPage(); await other.goto(base + '/', {waitUntil:'domcontentloaded'});
    await other.waitForSelector('[data-rate-limit-notice]');
    const otherCode = await other.evaluate(async() => { try { const api = await import('/javascript/site/api.js?v=20261020.1'); await api.api('/book/searchByPage'); return 0; } catch (error) { return error.code; } });
    assert.equal(otherCode, 429); report.checks.push('a second page shares the same cooldown');
    await page.screenshot({path:path.join(out, 'frontend-429-mobile.png'), fullPage:false});
    await page.waitForFunction(() => document.querySelector('[data-rate-limit-notice]')?.textContent === '现在可以重试搜索或章节加载。', null, {timeout:16000});
    assert.equal(requests, 1); assert.equal(await page.evaluate(() => novelReader.wantRun), false);
    report.checks.push('cooldown expiry never retries or resumes playback automatically');
    blocked = false; await page.bringToFront();
    await page.evaluate(() => {
      window.rateLimitClicks = [];
      window.addEventListener('click', event => rateLimitClicks.push({phase:'capture', target:event.target.outerHTML?.slice(0,200)}), true);
      window.addEventListener('click', event => rateLimitClicks.push({phase:'bubble', prevented:event.defaultPrevented}), false);
    });
    await page.locator('[data-chapter="next"]').click();
    await page.waitForFunction(id => novelReader.chapter.bookIndexId === id, original.next, {timeout:20000});
    assert.equal(requests, 2); assert.equal(await page.evaluate(() => novelReader.wantRun), false);
    report.checks.push('manual navigation recovers after cooldown and preserves paused playback');
    report.requestCount = requests; assert.deepEqual(report.errors, []); report.success = true;
  } catch (error) {
    report.error = error.stack; report.requestCount = requests;
    if (activePage) {
      report.reader = await activePage.evaluate(async() => ({id:window.novelReader?.chapter.bookIndexId, next:window.novelReader?.chapter.nextChapterId, state:window.novelReader?.state, message:document.querySelector('#audio-state')?.textContent, notice:document.querySelector('[data-rate-limit-notice]')?.textContent, clicks:window.rateLimitClicks, remaining:(await import('/javascript/site/rate-limit.js?v=20261020.1')).remainingRetrySeconds()})).catch(() => null);
      await activePage.screenshot({path:path.join(out, 'frontend-failure.png')}).catch(() => {});
    }
    process.exitCode = 1;
  }
  finally { report.completed = new Date().toISOString(); fs.writeFileSync(path.join(out, 'frontend-browser.json'), JSON.stringify(report, null, 2)); await browser.close(); }
  console.log(JSON.stringify({success:report.success, checks:report.checks, error:report.error, browserErrors:report.errors, reader:report.reader, requestCount:report.requestCount, chapterResponses:report.chapterResponses}));
})().catch(error => { console.error(error.message); process.exitCode = 1; });
