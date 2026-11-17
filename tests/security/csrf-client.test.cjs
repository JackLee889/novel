const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

function page(module) {
    const callbacks = {};
    class Xhr {
        constructor() { this.headers = {}; }
        open(method, url) { this.method = method; this.url = url; }
        setRequestHeader(name, value) { this.headers[name] = value; }
        send() { this.sent = true; }
    }
    const requests = [];
    const document = {
        querySelector(selector) { return {content: selector.includes('_csrf_header') ? 'X-CSRF-TOKEN' : 'session-token'}; },
        addEventListener(event, handler) { callbacks[event] = handler; }
    };
    const window = {fetch: async request => { requests.push(request); return {ok: true}; }};
    const context = vm.createContext({document, window, XMLHttpRequest: Xhr,
        location: {href: 'http://127.0.0.1:18088/login', origin: 'http://127.0.0.1:18088'}, URL, Headers, Request});
    const file = path.resolve(__dirname, '../../' + module + '/src/main/resources/static/js/csrf.js');
    vm.runInContext(fs.readFileSync(file, 'utf8'), context);
    return {Xhr, window, requests, callbacks};
}

for (const module of ['novel-admin', 'novel-crawl']) {
    test(module + ': local mutations include a token, reads and other origins do not', () => {
        const {Xhr} = page(module);
        for (const [method, url, expected] of [
            ['POST', '/login', 'session-token'], ['DELETE', '/crawl/task/1', 'session-token'],
            ['GET', '/novel/book/list', undefined], ['POST', 'https://other.example/upload', undefined],
            ['POST', '//other.example/upload', undefined]
        ]) {
            const request = new Xhr(); request.open(method, url); request.send();
            assert.equal(request.headers['X-CSRF-TOKEN'], expected, method + ' ' + url);
            assert.equal(request.sent, true);
        }
    });
    test(module + ': fetch keeps its data and cannot expose the token to another origin', async () => {
        const {window, requests} = page(module);
        await window.fetch('http://127.0.0.1:18088/update', {method: 'POST', body: 'original-data', headers: {'X-Original': 'keep'}});
        assert.equal(requests[0].headers.get('X-CSRF-TOKEN'), 'session-token');
        assert.equal(requests[0].headers.get('X-Original'), 'keep');
        assert.equal(await requests[0].text(), 'original-data');
        await window.fetch('https://other.example/update', {method: 'POST'});
        assert.equal(requests[1].headers.has('X-CSRF-TOKEN'), false);
    });
}
