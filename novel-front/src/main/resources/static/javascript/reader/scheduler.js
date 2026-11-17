const cancelled = () => new DOMException('朗读会话已更新', 'AbortError');
export class TtsScheduler {
  constructor(engine) {
    this.engine = engine; this.cache = new Map(); this.revision = 0; this.sessionId = 'idle';
    this.speed = 1; this.enabled = false;
    this.stats = {generated: 0, hits: 0, discarded: 0, peakBytes: 0, peakSeconds: 0, peakCount: 0,
      elapsedMs: 0, audioSeconds: 0, wasmBytes: 0};
    this.limits = {count: 2, seconds: 30, bytes: 16 * 1024 * 1024};
  }
  reset(segments, sessionId) { this.segments = segments; this.sessionId = sessionId; this.invalidate(); }
  invalidate() { this.revision++; this.cache.clear(); }
  setSpeed(speed) { if (speed !== this.speed) { this.speed = speed; this.invalidate(); } }
  token() { return {session: this.sessionId, revision: this.revision}; }
  valid(token) { return token.session === this.sessionId && token.revision === this.revision; }
  key(index) {
    const s = this.segments[index], m = this.engine.manifest;
    return JSON.stringify([m?.engineVersion, m?.modelId, m?.speakerId, this.speed,
      'reader-text-v1', s.contentVersion, s.chapterId, s.segmentId, s.text]);
  }
  usage() {
    return [...this.cache.values()].reduce((a, x) => ({bytes: a.bytes + x.samples.byteLength,
      seconds: a.seconds + x.duration, count: a.count + 1}), {bytes: 0, seconds: 0, count: 0});
  }
  measure() {
    const u = this.usage();
    this.stats.peakBytes = Math.max(this.stats.peakBytes, u.bytes);
    this.stats.peakSeconds = Math.max(this.stats.peakSeconds, u.seconds);
    this.stats.peakCount = Math.max(this.stats.peakCount, u.count);
  }
  async get(index, prefetch = false) {
    const token = this.token();
    if (!this.segments[index]) throw new Error('没有可朗读片段');
    await this.engine.ready();
    if (!this.valid(token)) throw cancelled();
    const key = this.key(index);
    if (this.cache.has(key)) {
      this.stats.hits++; const audio = this.cache.get(key);
      if (!prefetch) this.cache.delete(key);
      return audio;
    }
    // This lock survives resets. Synchronous WASM cannot be preempted by a message.
    while (this.inflight) {
      try { await this.inflight; }
      catch (error) {
        if (!this.valid(token)) throw cancelled();
        // A failed Worker must reach the current playback UI, including when
        // this request was waiting behind a now obsolete prefetch operation.
        if (error.name !== 'AbortError') throw error;
      }
      if (!this.valid(token)) throw cancelled();
      if (this.cache.has(key)) {
        this.stats.hits++; const audio = this.cache.get(key);
        if (!prefetch) this.cache.delete(key);
        return audio;
      }
    }
    if (prefetch && !this.enabled) return null;
    const segment = this.segments[index], speed = this.speed;
    const task = this.engine.synthesize(segment, token.session, token.revision, speed);
    this.inflight = task;
    let audio;
    try { audio = await task; }
    finally { if (this.inflight === task) this.inflight = null; }
    this.stats.generated++; this.stats.elapsedMs += audio.elapsedMs; this.stats.audioSeconds += audio.duration;
    this.stats.wasmBytes = audio.wasmBytes;
    if (!this.valid(token)) { this.stats.discarded++; throw cancelled(); }
    const u = this.usage();
    if (prefetch) {
      if (u.count >= this.limits.count || u.seconds + audio.duration > this.limits.seconds ||
          u.bytes + audio.samples.byteLength > this.limits.bytes) return null;
      this.cache.set(key, audio); this.measure();
    }
    return audio;
  }
  async prefetch(start) {
    const token = this.token();
    try {
      for (let i = start; i < Math.min(start + 2, this.segments.length); i++) {
        if (!this.enabled || !this.valid(token)) break;
        const u = this.usage();
        if (u.count >= this.limits.count || u.seconds >= this.limits.seconds || u.bytes >= this.limits.bytes) break;
        if (!await this.get(i, true)) break;
      }
    } catch (error) { if (error.name !== 'AbortError') this.prefetchError = error; }
  }
}
