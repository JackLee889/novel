const test = require('node:test'), assert = require('node:assert/strict');
const fs = require('node:fs'), vm = require('node:vm'), {webcrypto, createHash} = require('node:crypto');
const source = fs.readFileSync('novel-front/src/main/resources/static/javascript/reader/resource-cache.js', 'utf8');
const base = 'http://127.0.0.1:18987/reader/tts/1.13.8-matcha-icefall-zh-en/';
const names = ['sherpa-onnx-tts.js', 'sherpa-onnx-wasm-main-tts.js',
  'sherpa-onnx-wasm-main-tts.wasm', 'sherpa-onnx-wasm-main-tts.data'];
function fixture(options = {}) {
  const data = new Map(names.map((name, i) => [base + name, Buffer.from('asset-' + i)]));
  const assets = names.map(name => ({name, url: base + name, bytes: data.get(base + name).length,
    sha256: createHash('sha256').update(data.get(base + name)).digest('hex')}));
  const stored = new Map(), requests = [], progress = [], allocations = [], canceled = [], fetchOptions = [];
  const cache = {
    match: async key => stored.get(key)?.clone(),
    put: async (key, response) => {
      if (options.failPut) throw new Error('QuotaExceededError');
      // Fully consume the response, just as CacheStorage does before put resolves.
      const buffer = await response.arrayBuffer();
      stored.set(key, new Response(buffer, {headers: response.headers}));
    }
  };
  const scope = {location: new URL('http://127.0.0.1:18987/javascript/reader/sherpa.worker.js'), crypto: webcrypto,
    caches: {open: async () => { if (options.denied) throw new Error('SecurityError'); return cache; }}};
  class TrackedBytes extends Uint8Array {
    constructor(value) { super(value); if (typeof value === 'number') allocations.push(value); }
  }
  vm.runInNewContext(source, {self: scope, URL, Response, DOMException, Uint8Array: TrackedBytes, fetch: async (url, requestOptions) => {
    fetchOptions.push(requestOptions);
    requests.push(url); if (options.offline) throw new Error('NetworkError');
    if (options.stream) {
      const bytes = data.get(url); let offset = 0, unlock;
      const reader = {
        async read() {
          if (options.stall && offset > 0) return new Promise(resolve => { unlock = resolve; options.waiting?.(); });
          if (offset === bytes.length) return {done: true};
          const value = bytes.subarray(offset, offset + 2); offset += value.length; return {done: false, value};
        },
        async cancel() { canceled.push(url); unlock?.({done: true}); }, releaseLock() { options.released = true; }
      };
      return {ok: true, body: {getReader: () => reader}};
    }
    return new Response(data.get(url), {status: 200});
  }});
  const loader = () => new scope.ReaderResourceCache(base, assets, p => { progress.push(p); options.progress?.(p); });
  return {loader, assets, data, stored, requests, progress, scope, options, allocations, canceled, fetchOptions};
}
test('冷启动保存完整且校验通过的四个模型文件，后续读取不发下载请求', async () => {
  const f = fixture(), cold = f.loader();
  const files = await cold.load(); assert.equal(files.size, 4);
  assert.equal(cold.stats.persistent, true); assert.equal(cold.stats.storedAssets, 4);
  assert.equal(cold.stats.downloadedBytes, 28); assert.equal(f.requests.length, 4);
  const percentages = f.progress.filter(p => p.phase === 'download').map(p => p.percent);
  assert.ok(percentages.every((p, i) => i === 0 || p >= percentages[i - 1]));
  f.options.offline = true; f.requests.length = 0; f.progress.length = 0;
  const warm = f.loader(); assert.equal((await warm.load()).size, 4);
  assert.equal(warm.stats.cacheHits, 4); assert.equal(warm.stats.downloadedBytes, 0);
  assert.equal(f.requests.length, 0); assert.ok(!f.progress.some(p => p.phase === 'download'));
});

test('分块下载只为每个文件预分配一个清单大小的缓冲，进度与摘要仍精确', async () => {
  const f = fixture({stream: true}), loader = f.loader(), files = await loader.load();
  assert.deepEqual(f.allocations, f.assets.map(asset => asset.bytes));
  for (const asset of f.assets) assert.deepEqual(Buffer.from(files.get(asset.name)), f.data.get(asset.url));
  assert.equal(loader.stats.storedAssets, 4); assert.equal(loader.stats.downloadedBytes, 28);
  assert.equal(f.progress.filter(p => p.phase === 'download').at(-1).percent, 100);
});

test('开始前取消不访问网络；流读取中取消会结束等待并释放reader，不保存未完成资源', async () => {
  const canceled = new AbortController(); canceled.abort();
  const early = fixture(); await assert.rejects(early.loader().load(canceled.signal), {name: 'AbortError'});
  assert.equal(early.requests.length, 0);
  const controller = new AbortController(), f = fixture({stream: true, stall: true});
  f.options.waiting = () => controller.abort();
  await assert.rejects(f.loader().load(controller.signal), {name: 'AbortError'});
  assert.ok(f.canceled.includes(base + names[0])); assert.equal(f.options.released, true);
  assert.equal(f.stored.size, 0); assert.equal(f.requests.length, 1);
  assert.equal(f.fetchOptions[0].signal, controller.signal);
});

test('流文件超过或少于清单大小时取消reader且不保存坏资源', async () => {
  for (const body of ['short', 'asset-0-extra']) {
    const f = fixture({stream: true}); f.data.set(base + names[0], Buffer.from(body));
    await assert.rejects(f.loader().load(), /大小/);
    assert.equal(f.stored.size, 0); assert.equal(f.options.released, true); assert.equal(f.canceled.length, 1);
  }
});
test('中断后只补齐缺失文件，缓存键按文件摘要区分', async () => {
  const f = fixture(); await f.loader().load();
  const key = [...f.stored.keys()].find(k => k.includes('.data?')); f.stored.delete(key);
  f.requests.length = 0; const partial = f.loader(); await partial.load();
  assert.equal(partial.stats.cacheHits, 3); assert.equal(partial.stats.storedAssets, 1);
  assert.deepEqual(f.requests, [base + names[3]]);
  assert.ok([...f.stored.keys()].every(key => /reader-sha256=[a-f0-9]{64}$/.test(key)));
});
test('同长度但摘要损坏的缓存重新获取，不传给语音引擎', async () => {
  const f = fixture(); await f.loader().load();
  const key = [...f.stored.keys()].find(k => k.includes('.wasm?'));
  f.stored.set(key, new Response(Buffer.from('wrong!!'))); f.requests.length = 0;
  const repaired = f.loader(), files = await repaired.load();
  assert.equal(repaired.stats.cacheHits, 3); assert.deepEqual(f.requests, [base + names[2]]);
  assert.equal(Buffer.from(files.get(names[2])).toString(), 'asset-2');
});
test('缓存禁用或空间不足时仍可使用网络资源', async () => {
  for (const options of [{denied: true}, {failPut: true}]) {
    const f = fixture(options), loader = f.loader(); assert.equal((await loader.load()).size, 4);
    assert.equal(loader.stats.persistent, false); assert.equal(loader.stats.storedAssets, 0);
  }
});
test('错误大小、摘要和下载失败不会保存坏文件', async () => {
  for (const options of [{body: 'short'}, {body: 'wrong!!'}, {offline: true}]) {
    const f = fixture(options); if (options.body) f.data.set(base + names[0], Buffer.from(options.body));
    await assert.rejects(f.loader().load()); assert.equal(f.stored.size, 0);
  }
});
test('只接受固定同源公开模型文件，章节和外部资源不能进入缓存', () => {
  const f = fixture();
  for (const url of ['/book/reader/chapter?bookId=1', 'https://example.com/model.js', base + names[0] + '?v=1']) {
    const invalid = structuredClone(f.assets); invalid[0].url = url;
    assert.throws(() => new f.scope.ReaderResourceCache(base, invalid), /清单/);
  }
  assert.throws(() => new f.scope.ReaderResourceCache(base, f.assets.slice(1)), /清单/);
  assert.throws(() => new f.scope.ReaderResourceCache(base, [...f.assets.slice(1), f.assets[1]]), /清单/);
});
