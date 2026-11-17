(function () {
    'use strict';
    var curr = 1, limit = 10, request = null, generation = 0, pagerKey = '';
    var ui = window.CrawlRuntime;
    function message(text, failed) {
        $('#runtimeRefresh').text(text).toggleClass('runtime-warning', !!failed);
    }
    function renderPage(data) {
        var rows = data.list || [], open = new Set();
        document.querySelectorAll('.runtime-details[open]').forEach(function (detail) { open.add(detail.dataset.runtimeSource); });
        var html = rows.map(function (source, index) {
            var id = ui.number(source.id), on = Number(source.sourceStatus) === 1;
            return '<tr class="book_list"><td>[' + ((curr - 1) * limit + index + 1) + ']</td><td class="name">' + ui.escape(source.sourceName) +
                '</td><td>' + ui.escape(source.createTime) + '</td><td>' + ui.escape(source.updateTime) + '</td><td>' +
                ui.number(source.chapterCount) + '章</td><td id="sourceStatus' + id + '">' + (on ? '正在运行' : '停止运行') +
                '</td><td id="opt' + id + '"><a href="javascript:openOrStopCrawl(' + id + ',' + (on ? 1 : 0) + ')">' +
                (on ? '关闭' : '开启') + '</a> <a href="javascript:updateCrawlSource(' + id + ')">修改</a></td></tr>' +
                '<tr class="runtime-row"><td colspan="7">' + ui.render(source) + '</td></tr>';
        }).join('');
        $('#crawlSourceList').html(html || '<tr><td colspan="7">暂无爬虫源</td></tr>');
        document.querySelectorAll('.runtime-details').forEach(function (detail) { detail.open = open.has(detail.dataset.runtimeSource); });
        var key = [data.total, data.pageNum, data.pageSize].join(':');
        if (key !== pagerKey) {
            pagerKey = key;
            layui.use('laypage', function () {
                layui.laypage.render({elem: 'shellPage', count: data.total, curr: data.pageNum, limit: data.pageSize,
                    jump: function (obj, first) { if (!first) { curr = obj.curr; limit = obj.limit; search(true); } }});
            });
        }
        var sampled = rows.find(function (source) { return source.runtime; });
        message('刷新于 ' + ui.time(sampled ? sampled.runtime.observedAt : Date.now()), false);
    }
    function search(force) {
        if (request && !force) return;
        if (request) { generation++; request.abort(); }
        var token = ++generation;
        request = $.ajax({type: 'GET', url: '/crawl/listCrawlByPage', cache: false, timeout: 15000,
            data: {curr: curr, limit: limit}, dataType: 'json',
            success: function (response) {
                if (token !== generation) return;
                if (response.code === 200) renderPage(response.data);
                else if (response.code === 1001) location.href = '/user/login.html?originUrl=' + encodeURIComponent(location.href);
                else message('刷新失败，保留上次数据', true);
            },
            error: function (_, status) {
                if (token === generation && status !== 'abort') message('连接失败，保留上次数据，稍后重试', true);
            },
            complete: function () { if (token === generation) request = null; }
        });
    }
    window.updateCrawlSource = function (id) {
        localStorage.setItem('crawlSourceId', id);
        location.href = '/crawl/crawlSource_update.html';
    };
    window.openOrStopCrawl = function (id, status) {
        $.ajax({type: 'POST', url: '/crawl/openOrCloseCrawl', data: {sourceId: id, sourceStatus: status === 0 ? 1 : 0},
            dataType: 'json', success: function (data) {
                if (data.code === 200) search(true);
                else layer.alert(data.msg || '保存失败');
            }, error: function () { layer.alert('网络异常'); }});
    };
    search(false);
    setInterval(function () { if (!document.hidden) search(false); }, 5000);
    document.addEventListener('visibilitychange', function () { if (!document.hidden) search(false); });
})();
