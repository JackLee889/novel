import {getGlobalEngine} from './engine.js?v=20261008.3';
import {AudioPlayer} from './audio-player.js';
import {TtsScheduler} from './scheduler.js';
import {ChapterRepository} from './chapter-repository.js?v=20261020.1';
import {onRateLimit} from '../site/rate-limit.js?v=20261020.1';
import {ReaderStorage} from './storage.js';
import {segmentChapter} from './segmenter.js';
import {PlayerDock} from './player-dock.js?v=20261007.5';
import {scheduleModelPreload} from './model-preload.js?v=20261008.1';
const active = ['playing', 'preparing', 'buffering'];
const make = (tag, text) => { const node = document.createElement(tag); if (text) node.textContent = text; return node; };

class MiniSession {
  constructor(host) {
    this.host = host; this.engine = host.engine; this.storage = host.storage;
    this.settings = this.storage.settings(); this.repository = new ChapterRepository();
    this.scheduler = new TtsScheduler(this.engine); this.index = 0; this.offset = 0; this.op = 0;
    this.state = 'paused'; this.wantRun = false; this.sessionSequence = 0;
    this.player = new AudioPlayer(() => this.pause('声音被系统挂起，请点击继续'));
    this.engine.subscribe((state, detail) => {
      if (state === 'error') this.engineFailed(detail);
      if (state === 'loading' && this.wantRun) this.setState('preparing', detail.message);
    });
  }
  setState(state, message) { this.state = state; this.message = message; this.host.changed(); }
  rateLimited(error) {
    if (this.player.source) this.offset = this.player.position();
    this.op++; this.abort?.abort(); this.repository.cancel(); this.wantRun = false;
    this.scheduler.enabled = false; this.scheduler.invalidate(); this.player.stop(); this.active = null;
    clearInterval(this.clock); this.setState('paused', error.message); this.save();
  }
  engineFailed(detail) {
    if (!this.wantRun && !this.player.source && !active.includes(this.state)) return;
    this.op++; this.abort?.abort(); this.repository.cancel();
    if (this.player.source) this.offset = this.player.position();
    this.wantRun = false; this.scheduler.enabled = false; this.scheduler.invalidate();
    this.player.stop(); this.active = null; clearInterval(this.clock);
    this.lastError = Object.assign(new Error(detail.message || '语音引擎已中断，请重试'), {code: detail.code, diagnostics: detail.diagnostics});
    this.setState('error', this.lastError.message); this.save();
  }
  save() {
    if (!this.chapter || !this.segments?.length) return;
    const segment = this.segments[this.index]; if (!segment) return;
    this.storage.saveProgress(this.chapter, {bookId: this.chapter.bookId, bookIndexId: this.chapter.bookIndexId,
      contentVersion: this.chapter.contentVersion, segmentId: segment.segmentId, paragraphId: segment.paragraphId,
      offset: this.player.source ? this.player.position() : this.offset, speed: this.active?.speed || this.settings.speed,
      modelId: 'matcha-icefall-zh-en', savedAt: Date.now()});
    this.host.changed();
  }
  async play() {
    if (this.wantRun) return;
    const saved = this.host.last; if (!saved) return;
    const op = ++this.op; this.wantRun = true; this.setState('preparing', '正在确认本章朗读权限');
    const abort = new AbortController(); this.abort?.abort(); this.abort = abort;
    let timer, permissionTimedOut = false;
    try {
      // Resume the output in the original click before any authorization requests.
      await this.player.unlock(); await this.host.claim();
      // End the authorization deadline before model loading and audio recovery.
      timer = setTimeout(() => { permissionTimedOut = true; abort.abort(); }, 15000);
      let chapter;
      try { chapter = await this.repository.fetch(saved.bookId, saved.bookIndexId, abort.signal); }
      finally { clearTimeout(timer); timer = null; }
      if (op !== this.op || !this.wantRun) return;
      if (chapter.access !== 'allowed') {
        this.chapter = chapter; this.segments = []; this.scheduler.reset([], 'denied'); this.active = null;
        throw new Error(chapter.access === 'need_login' ? '本章需要登录和订阅' : '本章需要购买');
      }
      const same = this.chapter?.bookIndexId === chapter.bookIndexId &&
        this.chapter.contentVersion === chapter.contentVersion && this.chapter.accountScope === chapter.accountScope;
      if (!same) {
        this.player.stop(); this.active = null; this.chapter = chapter; this.segments = segmentChapter(chapter);
        const valid = saved.contentVersion === chapter.contentVersion && saved.accountScope === chapter.accountScope;
        this.index = valid ? Math.max(0, this.segments.findIndex(s => s.segmentId === saved.segmentId)) : 0;
        this.offset = valid && saved.speed === this.settings.speed ? saved.offset : 0;
        this.sessionId = chapter.bookIndexId + ':global:' + (++this.sessionSequence);
        this.scheduler.reset(this.segments, this.sessionId); this.scheduler.setSpeed(this.settings.speed);
      } else this.chapter = chapter;
      this.scheduler.enabled = true;
      await this.engine.ready();
      if (op !== this.op || !this.wantRun) return;
      if (this.player.source && this.active?.speed === this.settings.speed) {
        await this.player.resume();
        if (op !== this.op || !this.wantRun) return;
        this.setState('playing'); this.tick(); this.scheduler.prefetch(this.index + 1);
      } else await this.step(op);
    } catch (error) {
      if (op !== this.op) return;
      this.wantRun = false; this.scheduler.enabled = false; this.player.stop();
      this.setState('paused', permissionTimedOut ? '章节校验超时，请重试' :
        error.message.includes('启用声音') ? '点击继续即可接续听书' : error.message);
    } finally { clearTimeout(timer); }
  }
  async step(op) {
    if (!this.wantRun || op !== this.op) return;
    if (this.index >= this.segments.length) {
      if (this.settings.autoNext && this.chapter.nextChapterId) return this.navigate(this.chapter.nextChapterId);
      this.wantRun = false; this.setState('ended', '本章朗读结束'); return;
    }
    this.setState('buffering', '正在生成本段语音');
    try {
      const audio = await this.scheduler.get(this.index);
      if (op !== this.op || !this.wantRun) return;
      this.active = audio;
      this.player.play(audio, this.offset, () => {
        this.active = null; this.offset = 0; this.index++; this.save();
        if (this.wantRun) this.step(op);
      });
      this.offset = 0; this.setState('playing'); this.tick(); this.save(); this.scheduler.prefetch(this.index + 1);
    } catch (error) {
      if (op !== this.op || error.name === 'AbortError') return;
      this.wantRun = false; this.scheduler.enabled = false; this.player.stop(); this.setState('error', error.message);
    }
  }
  tick() {
    clearInterval(this.clock);
    this.clock = setInterval(() => { if (this.wantRun) this.save(); }, 1000);
  }
  async pause(message) {
    this.wantRun = false; this.op++; this.abort?.abort(); this.scheduler.enabled = false;
    await this.player.pause(); this.setState('paused', message); this.save(); clearInterval(this.clock);
  }
  stop() {
    this.offset = this.player.source ? this.player.position() : this.offset;
    this.wantRun = false; this.op++; this.abort?.abort(); this.scheduler.enabled = false;
    this.scheduler.invalidate(); this.player.stop(); this.active = null; clearInterval(this.clock);
    this.setState('idle', '已停止，可继续听书'); this.save();
  }
  async navigate(id) {
    if (!id) return;
    const running = this.wantRun; this.stop();
    try {
      const chapter = await this.repository.load(this.host.last.bookId, id);
      if (chapter.access !== 'allowed') throw new Error('下一章需要登录或订阅');
      this.host.last = {bookId: chapter.bookId, bookIndexId: chapter.bookIndexId, bookName: chapter.bookName,
        chapterName: chapter.chapterName, contentVersion: chapter.contentVersion, accountScope: chapter.accountScope,
        index: 0, offset: 0, segmentId: '', speed: this.settings.speed, state: 'paused'};
      this.chapter = null;
      if (running) await this.play(); else this.setState('paused');
    } catch (error) { this.setState('paused', error.message); }
  }
}

class GlobalPlayer {
  constructor(reader) {
    this.reader = reader; this.storage = new ReaderStorage(); this.engine = getGlobalEngine();
    this.session = reader || new MiniSession(this); this.owned = false; this.handingOff = false;
    this.last = this.storage.read('global-session');
    if (!reader) this.unsubscribeRateLimit = onRateLimit(error => this.session.rateLimited(error));
    if (reader?.ttsEnabled && reader.supported) this.dock = new PlayerDock(document.querySelector('.rd-player'), this.storage);
    this.engine.playbackListeners.add(value => {
      this.last = value.playback || this.last; this.remoteOwner = value.ownerId;
      this.render();
    });
    this.engine.yieldListeners.add(() => {
      this.owned = false; this.session.pause().then(() => {
        if (reader) reader.notice('已在另一页面接续听书'); else this.session.setState('paused', '已在另一页面接续听书');
      });
    });
    this.engine.subscribe(() => this.render());
    window.addEventListener('pageshow', event => { if (event.persisted) this.boot().catch(() => this.render()); });
    if (!reader) {
      window.addEventListener('pagehide', () => {
        this.handoff(); this.session.stop(); this.session.player.dispose(); this.engine.dispose();
      });
    }
    this.boot().catch(() => this.render());
    if (!reader || (reader.ttsEnabled && reader.supported))
      this.modelPreload = scheduleModelPreload(this.engine);
  }
  async boot() {
    const snapshot = await this.engine.snapshot();
    this.last = snapshot.playback || this.last; this.remoteOwner = snapshot.ownerId;
    this.render();
    if (!this.last || this.remoteOwner) return;
    if (this.reader) {
      const r = this.reader, saved = this.last;
      if (r.chapter.bookId !== saved.bookId || r.chapter.bookIndexId !== saved.bookIndexId ||
          r.chapter.contentVersion !== saved.contentVersion || r.chapter.accountScope !== saved.accountScope) return;
      r.index = Math.max(0, r.segments.findIndex(s => s.segmentId === saved.segmentId));
      r.offset = saved.speed === r.settings.speed ? saved.offset : 0;
      r.updatePosition();
      r.setState(saved.state === 'idle' ? 'idle' : 'paused', '已恢复全局朗读位置，点击继续');
      // A refreshed document may require a new user gesture for Web Audio.
      return;
    }
    if (this.last.resumeRequested) this.session.setState('paused', '点击继续即可接续听书');
  }
  async claim() { await this.engine.claim(); this.owned = true; this.handingOff = false; }
  value() {
    const r = this.session, chapter = r.chapter, segment = r.segments?.[r.index];
    if (!chapter || !segment || chapter.access !== 'allowed') {
      const saved = this.last; if (!saved) return null;
      return {bookId: saved.bookId, bookIndexId: saved.bookIndexId, bookName: saved.bookName,
        chapterName: saved.chapterName, accountScope: saved.accountScope, contentVersion: saved.contentVersion,
        segmentId: saved.segmentId, index: saved.index, offset: saved.offset, speed: saved.speed,
        state: r.state, resumeRequested: false};
    }
    return {bookId: chapter.bookId, bookIndexId: chapter.bookIndexId, bookName: chapter.bookName,
      chapterName: chapter.chapterName, accountScope: chapter.accountScope, contentVersion: chapter.contentVersion,
      segmentId: segment.segmentId, index: r.index, offset: r.player.source ? r.player.position() : r.offset,
      speed: r.active?.speed || r.settings.speed, state: r.state, resumeRequested: false};
  }
  changed() {
    this.dock?.update(this.session.state, this.session.chapter?.chapterName);
    if (!this.owned || this.handingOff) return;
    const value = this.value(); if (!value) return;
    this.last = value; this.engine.publish(value); this.storage.write('global-session', value); this.render();
  }
  handoff() {
    if (!this.owned) return;
    const value = this.value(); if (!value) return;
    value.resumeRequested = this.session.wantRun; value.state = 'paused';
    this.last = value; this.engine.publish(value); this.storage.write('global-session', value);
    this.handingOff = true;
  }
  render() {
    if (this.reader) { this.dock?.update(this.session.state, this.session.chapter?.chapterName); return; }
    if (!this.last || !/^\d+$/.test(this.last.bookId) || !/^\d+$/.test(this.last.bookIndexId)) return;
    if (!this.root) {
      const root = make('section'); root.className = 'global-player'; root.setAttribute('aria-label', '全局听书播放器');
      const identity = make('a'); identity.className = 'global-player-identity';
      this.title = make('strong'); this.status = make('span'); identity.append(this.title, this.status);
      this.identity = identity;
      this.button = make('button', '继续听书'); this.button.type = 'button';
      this.button.onclick = () => this.owned && active.includes(this.session.state) ? this.session.pause() : this.session.play();
      const stop = make('button', '停止'); stop.type = 'button'; stop.onclick = async () => {
        if (!this.owned) await this.claim(); this.session.stop();
      };
      const back = make('a', '回到阅读页'); back.className = 'global-player-back'; this.back = back;
      this.model = make('small'); this.model.className = 'global-player-model';
      root.append(identity, this.button, stop, back, this.model); document.body.append(root);
      document.body.classList.add('has-global-player'); this.root = root;
      this.dock = new PlayerDock(root, this.storage);
    }
    const last = this.last, state = this.owned ? this.session.state : last.state;
    this.dock.update(state, last.chapterName);
    this.title.textContent = last.bookName + ' · ' + last.chapterName;
    this.identity.href = this.back.href = '/book/' + last.bookId + '/' + last.bookIndexId + '.html';
    this.status.textContent = this.owned ? this.session.message || ({playing: '正在朗读', paused: '已暂停，可继续听书',
      preparing: '正在准备语音', buffering: '正在生成本段语音', ended: '本章朗读结束'})[state] || '可继续听书' :
      this.remoteOwner ? '另一页面正在听书，可在此接续' : '已保存位置，可继续听书';
    this.button.textContent = this.owned && active.includes(state) ? '暂停听书' : '继续听书';
    this.model.textContent = this.engine.state === 'ready' ? (this.engine.shared ? '全站语音引擎已就绪' : '语音引擎已就绪') : '本地语音合成';
  }
}
let globalPlayer;
export function bootGlobalPlayer(reader) {
  if (!globalPlayer) globalPlayer = new GlobalPlayer(reader);
  window.novelGlobalPlayer = globalPlayer;
  return globalPlayer;
}
