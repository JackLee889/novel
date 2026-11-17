(function () {
    'use strict';
    function token() {
        var value = document.querySelector('meta[name="_csrf"]');
        var header = document.querySelector('meta[name="_csrf_header"]');
        return value && header ? {value: value.content, header: header.content} : null;
    }
    function localUnsafe(method, url) {
        return !/^(GET|HEAD|OPTIONS)$/i.test(method || 'GET') &&
            new URL(url || location.href, location.href).origin === location.origin;
    }
    var open = XMLHttpRequest.prototype.open;
    var send = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function (method, url) {
        this.novelCsrfUnsafe = localUnsafe(method, url);
        return open.apply(this, arguments);
    };
    XMLHttpRequest.prototype.send = function () {
        var current = token();
        if (this.novelCsrfUnsafe && current) {
            this.setRequestHeader(current.header, current.value);
        }
        return send.apply(this, arguments);
    };
    if (window.fetch) {
        var originalFetch = window.fetch;
        window.fetch = function (input, init) {
            var request = new Request(input, init);
            var current = token();
            if (localUnsafe(request.method, request.url) && current) {
                var headers = new Headers(request.headers);
                headers.set(current.header, current.value);
                request = new Request(request, {headers: headers});
            }
            return originalFetch.call(this, request);
        };
    }
    function addField(form) {
        var current = token();
        if (!current || !localUnsafe(form.method, form.action)) { return; }
        var field = form.querySelector('input[name="_csrf"]');
        if (!field) {
            field = document.createElement('input');
            field.type = 'hidden';
            field.name = '_csrf';
            form.appendChild(field);
        }
        field.value = current.value;
    }
    document.addEventListener('submit', function (event) { addField(event.target); }, true);
    document.addEventListener('click', function (event) {
        var link = event.target.closest('a[href]');
        if (!link || new URL(link.href, location.href).origin !== location.origin ||
            new URL(link.href, location.href).pathname !== '/logout') { return; }
        event.preventDefault();
        var form = document.createElement('form');
        form.method = 'POST'; form.action = link.href;
        document.body.appendChild(form);
        addField(form);
        form.submit();
    });
}());
