(function () {
    'use strict';
    const base = '/novel/seo', message = document.getElementById('seo-message');
    let selectedBook = null, offset = 0;
    function notify(text, error) {
        message.textContent = text;
        message.className = 'alert ' + (error ? 'alert-danger' : 'alert-success');
    }
    async function request(path, data) {
        const options = {credentials: 'same-origin', headers: {'Accept': 'application/json', 'X-Requested-With': 'XMLHttpRequest'}};
        if (data !== undefined) {
            const token = document.querySelector('meta[name="_csrf"]');
            options.method = 'POST'; options.headers['Content-Type'] = 'application/json';
            options.headers['X-CSRF-TOKEN'] = token ? token.content : '';
            options.body = JSON.stringify(data);
        }
        const response = await fetch(base + path, options);
        const result = await response.json();
        if (!response.ok || result.code !== 0) throw new Error(result.msg || '操作失败，请刷新后重试');
        return result;
    }
    function task(work) { return async function (event) { if (event) event.preventDefault(); try { await work(); } catch (error) { notify(error.message, true); } }; }
    function values(form) { return Object.fromEntries(new FormData(form)); }
    function fill(form, data) {
        Object.keys(data).forEach(key => {
            const input = form.elements.namedItem(key); if (!input) return;
            if (input.type === 'checkbox') input.checked = data[key] === true;
            else input.value = data[key] == null ? '' : String(data[key]);
        });
    }
    const configForm = document.getElementById('config-form');
    async function config() {
        const data = (await request('/config')).data;
        fill(configForm, data.settings); configForm.elements.expectedEpoch.value = data.settings.epoch;
        document.getElementById('site-url').textContent = data.siteUrl || '未配置';
        document.getElementById('site-url-warning').textContent = data.siteUrlValid ? '' : '站点根地址未通过校验，不能启用索引。';
        document.getElementById('config-version').textContent = '配置版本：' + data.settings.epoch;
    }
    if (configForm) {
        configForm.addEventListener('submit', task(async () => {
            const data = values(configForm);
            ['indexEnabled','chaptersIndexEnabled','chapterSitemapEnabled'].forEach(key => { data[key] = configForm.elements[key].checked; });
            await request('/config', data); await config(); notify('站点设置已保存，生成队列将使用新版本。');
        })); task(config)();
    }
    const bookForm = document.getElementById('book-form'), searchForm = document.getElementById('book-search');
    async function books() {
        const list = (await request('/books?query=' + encodeURIComponent(searchForm.elements.query.value) + '&offset=' + offset + '&limit=20')).data;
        const rows = document.getElementById('book-rows'); rows.replaceChildren();
        list.forEach(book => {
            const row = document.createElement('tr');
            [book.bookName + ' (' + book.bookId + ')', book.authorName, Number(book.status) === 1 ? '已上架' : '入库/未公开', book.revision].forEach(value => {
                const cell = document.createElement('td'); cell.textContent = value == null ? '' : String(value); row.appendChild(cell);
            });
            const cell = document.createElement('td'), button = document.createElement('button');
            button.className = 'btn btn-default btn-sm'; button.textContent = '设置与预览';
            button.addEventListener('click', task(() => bookPreview(String(book.bookId)))); cell.appendChild(button); row.appendChild(cell); rows.appendChild(row);
        });
        document.getElementById('books-prev').disabled = offset === 0;
        document.getElementById('books-next').disabled = list.length < 20;
    }
    async function bookPreview(bookId) {
        const data = (await request('/books/' + encodeURIComponent(bookId))).data;
        selectedBook = bookId; document.getElementById('book-panel').hidden = false;
        document.getElementById('selected-book').textContent = data.bookName + ' · ' + bookId;
        document.getElementById('index-reason').textContent = data.indexable ? '当前允许索引' : '排除原因：' + (data.excludedReason || '索引条件未满足');
        document.getElementById('book-canonical').textContent = 'Canonical：' + (data.canonical || '站点地址未配置');
        document.getElementById('effective-title').textContent = data.resolvedTitle || '';
        document.getElementById('effective-description').textContent = data.resolvedDescription || '';
        document.getElementById('effective-keywords').textContent = data.resolvedKeywords || '';
        document.getElementById('effective-robots').textContent = data.robots || '';
        const override = data.override || {};
        fill(bookForm, override); bookForm.elements.expectedRevision.value = data.revision;
        bookForm.elements.indexMode.value = override.indexEnabled == null ? 'INHERIT' : (override.indexEnabled ? 'ALLOW' : 'DENY');
        document.getElementById('book-preview').textContent = JSON.stringify(data, null, 2);
    }
    if (searchForm) {
        searchForm.addEventListener('submit', task(async () => { offset = 0; await books(); }));
        document.getElementById('books-prev').addEventListener('click', task(async () => { offset = Math.max(0, offset - 20); await books(); }));
        document.getElementById('books-next').addEventListener('click', task(async () => { offset += 20; await books(); }));
        bookForm.addEventListener('submit', task(async () => { await request('/books/' + encodeURIComponent(selectedBook), values(bookForm)); await bookPreview(selectedBook); notify('单书覆盖已保存；没有改变书籍上架状态。'); }));
        const rebuildBook = document.getElementById('book-rebuild');
        if (rebuildBook) rebuildBook.addEventListener('click', task(async () => { const result = await request('/rebuild', {scope:'book',bookId:selectedBook}); notify('任务已入队：' + result.jobId); }));
        task(books)();
    }
    function tableRow(parent, values) {
        const row = document.createElement('tr');
        values.forEach(value => { const cell = document.createElement('td'); cell.textContent = value == null ? '' : String(value); row.appendChild(cell); });
        parent.appendChild(row);
    }
    function httpStats(data) {
        const status = document.getElementById('http-stats-status'); if (!status) return;
        const clients = document.getElementById('http-client-rows'), hours = document.getElementById('http-hour-rows');
        clients.replaceChildren(); hours.replaceChildren();
        const available = data && data.available === true, totals = available ? data.totals || {} : {};
        [['http-requests','requests'],['http-not-found','notFound'],['http-server-errors','serverErrors'],['http-unresolved','unresolvedExceptions']].forEach(([id,key]) => {
            document.getElementById(id).textContent = available ? String(totals[key] || '0') : '—';
        });
        status.textContent = available ? '统计窗口：' + data.windowStart + ' 至 ' + data.windowEndExclusive + '（结束时间不含）。最近一次非空批次：' + (data.lastFlushedAt || '尚无请求统计') : (data && data.message || 'HTTP 聚合统计尚不可用。');
        document.getElementById('http-outage-summary').textContent = '';
        if (!available) return;
        const names = {ordinary:'普通请求 / 未声明爬虫', googlebot:'Googlebot（UA 声明）', bingbot:'Bingbot（UA 声明）', baiduspider:'Baiduspider（UA 声明）', other_declared_crawler:'其他爬虫（UA 声明）'};
        const byKind = new Map((data.clients || []).map(item => [item.clientKind, item]));
        Object.entries(names).forEach(([kind,name]) => { const item = byKind.get(kind) || {}; tableRow(clients, [name,item.requests || '0',item.notFound || '0',item.serverErrors || '0']); });
        (data.hourly || []).forEach(item => tableRow(hours, [item.hour,item.requests,item.notFound,item.serverErrors,item.unresolvedExceptions]));
        const outage = data.unbucketedOutageTotals;
        if (outage && String(outage.requests) !== '0') document.getElementById('http-outage-summary').textContent = '历史长时间写库失败期间另有合并计数：请求 ' + outage.requests + '，404 ' + outage.notFound + '，5xx ' + outage.serverErrors + '，终态未确认异常 ' + outage.unresolvedExceptions + '；范围 ' + outage.firstHour + ' 至 ' + outage.lastHour + '。这部分保留总量，无法精确分小时，因此未混入上方 168 小时统计。';
    }
    async function jobs() {
        const data = (await request('/sitemaps')).data;
        httpStats(data.httpStats);
        const {httpStats: ignored, ...sitemapData} = data;
        document.getElementById('sitemap-state').textContent = JSON.stringify(sitemapData, null, 2);
        const states = data.states || [], waiting = states.filter(item => item.status === 'pending' || item.status === 'running' || item.status === 'failed');
        document.getElementById('queue-summary').textContent = '单书队列：' + (waiting.map(item => item.status + ' ' + item.count).join('，') || '暂无积压或失败任务') + '。失败任务可能正在等待重试。';
        const duration = value => value == null ? '未采集' : String(value) + ' ms';
        const core = data.coreSnapshot || {};
        document.getElementById('core-task-summary').textContent = '核心地图：' + (core.status || '未知') + '；开始 ' + (core.startedAt || '—') + '，完成 ' + (core.completedAt || '—') + '，耗时 ' + duration(core.durationMs) + (core.lastError ? '；失败原因 ' + core.lastError : '');
        const rows = document.getElementById('sitemap-job-rows'); rows.replaceChildren();
        (data.jobs || []).forEach(item => tableRow(rows, [item.bookId,item.status,item.startedAt || '—',item.completedAt || '—',duration(item.durationMs),item.urlCount || '0',item.lastError || '—']));
    }
    if (document.getElementById('seo-sitemaps')) {
        document.getElementById('refresh-jobs').addEventListener('click', task(jobs)); task(jobs)();
        const rebuildAll = document.getElementById('rebuild-all');
        if (rebuildAll) rebuildAll.addEventListener('click', task(async () => { const result = await request('/rebuild', {scope:'all',bookId:null}); notify('全站任务已入队：' + result.jobId); await jobs(); }));
    }
    const diagnosisForm = document.getElementById('diagnosis-form');
    if (diagnosisForm) {
        const refreshHttp = task(async () => httpStats((await request('/diagnosis')).data.httpStats));
        document.getElementById('refresh-http').addEventListener('click', refreshHttp);
        if (!document.getElementById('seo-sitemaps')) refreshHttp();
    }
    if (diagnosisForm) diagnosisForm.addEventListener('submit', task(async () => {
        const data = values(diagnosisForm); data.bookId = data.bookId || null; data.chapterId = data.chapterId || null;
        document.getElementById('diagnosis-result').textContent = JSON.stringify((await request('/diagnosis', data)).data, null, 2);
    }));
}());
