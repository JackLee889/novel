/* Classic Worker helper: cache only the four public, versioned model assets. */
self.ReaderResourceCache = class ReaderResourceCache {
  constructor(baseUrl, assets, progress = () => {}) {
    this.base = new URL(baseUrl, self.location.href);
    const names = ['sherpa-onnx-tts.js', 'sherpa-onnx-wasm-main-tts.js',
      'sherpa-onnx-wasm-main-tts.wasm', 'sherpa-onnx-wasm-main-tts.data'];
    if (this.base.origin !== self.location.origin ||
        this.base.pathname !== '/reader/tts/1.13.8-matcha-icefall-zh-en/' ||
        this.base.search || this.base.hash || !Array.isArray(assets) || assets.length !== names.length)
      throw new Error('语音资源清单无效');
    this.assets = names.map(name => {
      const matches = assets.filter(asset => asset.name === name);
      if (matches.length !== 1) throw new Error('语音资源清单不完整');
      const asset = matches[0], url = new URL(asset.url, self.location.href);
      if (url.href !== new URL(name, this.base).href || !Number.isSafeInteger(asset.bytes) ||
          asset.bytes <= 0 || asset.bytes > 200 * 1024 * 1024 || !/^[a-f0-9]{64}$/.test(asset.sha256))
        throw new Error('语音资源清单无效');
      const key = new URL(url); key.searchParams.set('reader-sha256', asset.sha256);
      return {...asset, url: url.href, key: key.href};
    });
    this.progress = progress;
    this.stats = {cacheHits: 0, downloadedAssets: 0, downloadedBytes: 0, storedAssets: 0, persistent: false};
  }
  async valid(buffer, asset) {
    if (buffer.byteLength !== asset.bytes) return false;
    if (!self.crypto?.subtle) return true; // HTTP-only browsers retain the existing network fallback.
    const digest = await self.crypto.subtle.digest('SHA-256', buffer);
    return Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, '0')).join('') === asset.sha256;
  }
  async load(signal) {
    const check = () => { if (signal?.aborted) throw new DOMException('语音资源加载已取消', 'AbortError'); };
    check();
    let cache;
    try {
      if (self.crypto?.subtle && self.caches) cache = await self.caches.open('novel.reader.tts.v1.1.13.8-matcha-icefall-zh-en');
    } catch {} // Storage denial or quota failures must not prevent playback.
    const files = new Map(), missing = [];
    try {
    this.progress({phase: 'cache', message: '正在读取已缓存的语音资源'});
    for (const asset of this.assets) {
      check();
      let buffer;
      try {
        const stored = await cache?.match(asset.key);
        if (stored) {
          const candidate = await stored.arrayBuffer();
          check();
          if (await this.valid(candidate, asset)) buffer = candidate;
        }
      } catch {}
      check();
      if (buffer) { files.set(asset.name, buffer); this.stats.cacheHits++; }
      else missing.push(asset);
    }
    const total = missing.reduce((sum, asset) => sum + asset.bytes, 0);
    let completed = 0;
    for (const asset of missing) {
      check();
      const report = loaded => this.progress({phase: 'download', message: '正在获取并缓存语音资源',
        percent: Math.min(100, (completed + loaded) / total * 100)});
      report(0);
      const response = await fetch(asset.url, {cache: 'force-cache', credentials: 'same-origin', signal});
      check();
      if (!response.ok) throw new Error('语音资源下载失败，请重试');
      let buffer;
      if (response.body?.getReader) {
        const reader = response.body.getReader();
        // The manifest has already bounded and validated this allocation. Keep
        // only this final buffer and the current chunk, rather than two full copies.
        const bytes = new Uint8Array(asset.bytes);
        let loaded = 0;
        const cancel = () => { Promise.resolve(reader.cancel()).catch(() => {}); };
        signal?.addEventListener('abort', cancel, {once: true});
        try {
          while (true) {
            check();
            const {done, value} = await reader.read(); if (done) break;
            check();
            if (loaded + value.byteLength > bytes.byteLength) throw new Error('语音资源大小不匹配');
            bytes.set(value, loaded); loaded += value.byteLength; report(loaded);
          }
          check();
          if (loaded !== asset.bytes) throw new Error('语音资源大小不匹配');
          buffer = bytes.buffer;
        } catch (error) {
          try { await reader.cancel(error); } catch {}
          throw error;
        } finally { signal?.removeEventListener('abort', cancel); reader.releaseLock(); }
      } else buffer = await response.arrayBuffer();
      check();
      if (!await this.valid(buffer, asset)) throw new Error('语音资源校验失败，请重试');
      check();
      files.set(asset.name, buffer);
      this.stats.downloadedAssets++; this.stats.downloadedBytes += buffer.byteLength;
      try {
        if (cache) {
          await cache.put(asset.key, new Response(buffer, {headers: {
            'Content-Type': asset.name.endsWith('.js') ? 'application/javascript' :
              asset.name.endsWith('.wasm') ? 'application/wasm' : 'application/octet-stream',
            'Content-Length': String(buffer.byteLength), 'X-Reader-Sha256': asset.sha256
          }}));
          this.stats.storedAssets++;
        }
      } catch {} // Keep the verified in-memory asset even if it cannot be saved.
      check();
      report(asset.bytes); completed += asset.bytes;
    }
    this.stats.persistent = this.stats.cacheHits + this.stats.storedAssets === this.assets.length;
    this.progress({phase: 'initialize', message: this.stats.cacheHits === this.assets.length ?
      '语音资源已缓存，正在初始化模型' : '语音资源已就绪，正在初始化模型'});
    return files;
    } catch (error) { files.clear(); throw error; }
  }
};
