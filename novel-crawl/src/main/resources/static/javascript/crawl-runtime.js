(function (root) {
    'use strict';
    function escape(value) {
        return String(value == null ? '' : value).replace(/[&<>"']/g, function (c) {
            return {'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c];
        });
    }
    function number(value) {
        var n = Number(value);
        return Number.isFinite(n) && n > 0 ? Math.floor(n) : 0;
    }
    function time(value) {
        if (value == null || !Number.isFinite(Number(value))) return '暂无记录';
        return new Intl.DateTimeFormat('zh-CN', {timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit',
            hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false}).format(new Date(Number(value)));
    }
    function status(source) {
        var rt = source.runtime;
        if (!rt) return {text: '实时统计尚不可用', style: 'muted'};
        var active = number(rt.activeDownloads) + number(rt.activeScans);
        if (Number(source.sourceStatus) !== 1) return {text: active ? '正在停止' : '已停止', style: 'muted'};
        if (rt.retryAt != null && Number(rt.retryAt) > Number(rt.observedAt)) return {text: '等待重试', style: 'warning'};
        if (number(rt.activeDownloads)) return {text: '正在采集', style: 'active'};
        if (number(rt.activeScans)) return {text: '正在扫描', style: 'active'};
        return {text: '等待任务', style: 'muted'};
    }
    function work(task) {
        var types = {REFRESH: '连载目录追更', UPDATE: '历史章节补采', DOWNLOAD: '采集 / 续采', MANUAL: '单本采集', SCAN: '分类扫描'};
        var outcomes = {COMPLETE: '本轮核验完整', PARTIAL: '等待续采', RETRY: '等待重试', FILTERED: '按采集范围跳过',
            EXISTING: '已存在', INTERRUPTED: '已中断', BUSY: '等待其他任务释放', FAILED: '本轮失败'};
        var done = number(task.processed), total = number(task.total);
        var progress = task.kind === 'SCAN' ? '第 ' + done + ' 页' + (total ? ' / 共 ' + total + ' 页' : '')
            : total ? '本轮目录检查 ' + Math.min(done, total) + ' / ' + total : '读取图书信息 / 目录';
        var percent = total ? Math.min(100, Math.floor(done * 100 / total)) : 0;
        var bar = task.kind !== 'SCAN' && total ? '<progress aria-label="本轮目录检查进度" max="100" value="' + percent + '"></progress>' : '';
        return '<div class="runtime-task"><div><span class="runtime-kind">' + escape(types[task.kind] || '采集任务') +
            '</span> <strong>' + escape(task.bookName || '读取图书信息') + '</strong>' +
            (task.sourceBookId ? ' <span class="runtime-dim">来源书号 ' + escape(task.sourceBookId) + '</span>' : '') +
            '</div><div class="runtime-task-progress">' + escape(progress) + bar +
            (task.kind !== 'SCAN' ? '<span>本轮保存 ' + number(task.saved) + ' 章</span>' : '') +
            (task.outcome ? '<span>' + escape(outcomes[task.outcome] || '本轮结束') + '</span>' : '') +
            '</div>' + (task.chapter ? '<div class="runtime-dim">' + escape(task.chapter) + '</div>' : '') + '</div>';
    }
    function render(source) {
        var rt = source.runtime, state = status(source);
        if (!rt) return '<span class="runtime-dim">' + state.text + '</span>';
        var tasks = Array.isArray(rt.tasks) ? rt.tasks : [], errors = Array.isArray(rt.errors) ? rt.errors : [];
        var html = '<div class="runtime-line"><span class="runtime-state runtime-state-' + state.style + '">' + state.text +
            '</span><span>近60秒保存 <strong>' + number(rt.savedLastMinute) + '</strong> 章</span>' +
            '<span>下载中 <strong>' + number(rt.activeDownloads) + '</strong></span>' +
            '<span>最近保存 ' + escape(time(rt.lastSavedAt)) + '</span></div>';
        var queue = source.queue;
        if (queue) {
            var minutes = queue.oldestRefreshDueAt == null ? 0 : Math.max(0, Math.floor((Number(queue.observedAt) - Number(queue.oldestRefreshDueAt)) / 60000));
            html += '<div class="runtime-line"><span>连载待检 <strong>' + number(queue.refreshWaiting) + '</strong></span>' +
                '<span>最久等待 ' + minutes + ' 分钟</span><span>书目待处理 ' + number(queue.discoveryWaiting) + '</span>' +
                '<span>后台补采待处理 ' + number(queue.backfillWaiting) + '</span></div>' +
                '<div class="runtime-dim">本源最近排期 ' + escape(time(queue.lastEnqueuedAt)) +
                ' · 实际目录检查 ' + escape(time(queue.lastCheckedAt)) + ' · 最近追更保存 ' + escape(time(queue.lastSavedAt)) + '</div>';
        }
        if (rt.retryAt != null && Number(rt.retryAt) > Number(rt.observedAt)) {
            html += '<div class="runtime-warning">源站验证或访问限制，约 ' + Math.ceil((Number(rt.retryAt) - Number(rt.observedAt)) / 1000) + ' 秒后重试</div>';
        }
        html += '<details class="runtime-details" data-runtime-source="' + number(source.id) + '"><summary>当前任务 ' +
            tasks.length + ' · 最近异常 ' + errors.length + '<span class="runtime-dim"> · 最近活动 ' + escape(time(rt.lastActivityAt)) +
            '</span></summary><div class="runtime-detail-content">';
        html += tasks.length ? tasks.map(work).join('') : '<p class="runtime-dim">当前没有在执行的任务。</p>';
        if (!tasks.length && rt.lastTask) html += '<p class="runtime-dim">最近任务</p>' + work(rt.lastTask);
        if (errors.length) {
            html += '<div class="runtime-errors"><strong>最近异常记录</strong>' + errors.map(function (error) {
                return '<div>' + escape(time(error.occurredAt)) + ' · ' + escape(error.message) + '</div>';
            }).join('') + '</div>';
        }
        html += '<p class="runtime-dim">本次启动后保存 ' + number(rt.savedSinceStart) + ' 章；近60秒异常 ' + number(rt.errorsLastMinute) +
            ' 次。目录检查位置不代表整本已采齐。</p></div></details>';
        return html;
    }
    var api = {escape: escape, number: number, time: time, status: status, render: render, work: work};
    if (typeof module !== 'undefined' && module.exports) module.exports = api;
    else root.CrawlRuntime = api;
})(typeof window !== 'undefined' ? window : globalThis);
