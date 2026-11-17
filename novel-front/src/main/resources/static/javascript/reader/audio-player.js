export class AudioPlayer {
  constructor(onInterrupted = () => {}) {
    this.onInterrupted = onInterrupted; this.serial = 0; this.expectedPause = false;
    this.stats = {starts: 0, completed: 0, stopped: 0};
  }
  async unlock() {
    if (!this.context) {
      const Context = window.AudioContext || window.webkitAudioContext;
      if (!Context) throw new Error('此浏览器不支持 Web Audio 朗读');
      this.context = new Context();
      this.context.onstatechange = () => {
        if (this.source && this.context.state !== 'running' && !this.expectedPause) this.onInterrupted();
      };
    }
    this.expectedPause = false;
    await this.context.resume();
    if (this.context.state !== 'running') throw new Error('请点击继续以启用声音');
  }
  play(audio, offset, ended) {
    this.stop();
    if (this.context?.state !== 'running') throw new Error('音频被系统挂起，请点击继续');
    const buffer = this.context.createBuffer(1, audio.samples.length, audio.sampleRate);
    buffer.copyToChannel(audio.samples, 0);
    const node = this.context.createBufferSource();
    node.buffer = buffer; node.connect(this.context.destination);
    const serial = ++this.serial;
    this.duration = audio.duration; this.offset = Math.max(0, Math.min(offset || 0, Math.max(0, audio.duration - 0.01)));
    this.startedAt = this.context.currentTime; this.source = node;
    node.onended = () => {
      if (serial !== this.serial || node !== this.source) return;
      node.disconnect(); this.source = null; node.buffer = null;
      this.offset = this.duration; this.stats.completed++; ended();
    };
    node.start(0, this.offset); this.stats.starts++;
  }
  position() {
    return this.source ? Math.min(this.duration, this.offset + this.context.currentTime - this.startedAt) : (this.offset || 0);
  }
  async pause() { this.expectedPause = true; if (this.context) await this.context.suspend(); }
  async resume() { await this.unlock(); }
  stop() {
    this.offset = this.position();
    this.serial++;
    if (this.source) {
      this.source.onended = null; this.source.stop(); this.source.disconnect(); this.source.buffer = null;
      this.source = null; this.stats.stopped++;
    }
  }
  async dispose() { this.stop(); if (this.context) { this.context.onstatechange = null; await this.context.close(); } }
}
