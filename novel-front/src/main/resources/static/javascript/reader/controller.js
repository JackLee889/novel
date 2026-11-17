import {getGlobalEngine} from './engine.js?v=20261008.3';
import {bootGlobalPlayer} from './global-player.js?v=20261020.1';
import {TtsScheduler} from './scheduler.js';
import {AudioPlayer} from './audio-player.js';
import {segmentChapter} from './segmenter.js';
import {ReaderStorage} from './storage.js';
import {ChapterRepository} from './chapter-repository.js?v=20261020.1';
import {onRateLimit} from '../site/rate-limit.js?v=20261020.1';
import {ChapterFeedback} from './feedback.js?v=20261008.1';
import {applyChapterMetadata} from './seo.js?v=20261008.1';
const $ = selector => document.querySelector(selector);
const create = (tag, text, className) => {
  const element = document.createElement(tag); if (text != null) element.textContent = text;
  if (className) element.className = className; return element;
};
const activeStates = ['playing', 'buffering', 'preparing'];
export class NovelReader {
  constructor(chapter) {
    // Clear any utterance left by the legacy mobile page before using Sherpa.
    window.speechSynthesis?.cancel();
    this.root = $('#reader'); this.chapter = chapter; this.op = 0; this.sessionSequence = 0;
    this.paymentEnabled = this.root.dataset.paymentEnabled === 'true';
    this.paragraphAction = $('#paragraph-action');
    this.index = 0; this.offset = 0; this.state = 'idle'; this.wantRun = false; this.events = [];
    this.storage = new ReaderStorage(); this.settings = this.storage.settings(); this.followSuspended = false;
    this.repository = new ChapterRepository();
    this.player = new AudioPlayer(() => {
      this.wantRun = false; this.scheduler.enabled = false; this.op++;
      this.setState('paused', '声音被系统挂起，请点击继续'); this.save();
    });
    this.engine = getGlobalEngine(this.root.dataset.manifest, (state, info) => {
      if (state === 'error') this.engineFailed(info);
      if (state === 'loading' && this.wantRun && this.state !== 'playing') {
        this.setState('preparing', (info.message || '正在初始化语音') +
          (info.percent != null ? ' · ' + Math.round(info.percent) + '%' : ''));
      }
      if (state === 'ready' && this.wantRun && info.resources?.persistent && info.resources.downloadedAssets) {
        this.notice('语音资源已保存在此浏览器，后续播放会复用。');
      }
    });
    this.scheduler = new TtsScheduler(this.engine);
    this.supported = !!(window.Worker && window.WebAssembly && (window.AudioContext || window.webkitAudioContext));
    this.ttsEnabled = this.root.dataset.ttsEnabled === 'true';
    this.applySettings(); this.install(chapter, false); this.bind(); this.offerProgress(); this.entered();
    this.feedback = new ChapterFeedback(() => this.chapter, text => this.notice(text));
    this.unsubscribeRateLimit = onRateLimit(error => this.rateLimited(error));
    if (!this.supported && this.ttsEnabled) this.setState('idle', '此浏览器不支持朗读，可继续阅读正文');
    this.installMediaSession();
    this.globalPlayer = bootGlobalPlayer(this);
  }
  event(type, data = {}) {
    this.events.push({type, chapterId: this.chapter.bookIndexId, sessionId: this.sessionId, at: performance.now(), ...data});
    if (this.events.length > 150) this.events.shift();
  }
  setState(state, message) {
    this.state = state;
    const texts = {idle:'中英双语朗读，随时开始',preparing:'正在准备语音',playing:'正在朗读',
      paused:'已暂停，点击继续',buffering:'正在缓冲下一片段',ended:'本章朗读结束',error:'朗读中断，可重试'};
    $('#audio-state').textContent = message || texts[state];
    const button = $('[data-action="play"]');
    button.querySelector('.rd-control-label').textContent = activeStates.includes(state) ? '暂停' : state === 'paused' ? '继续' : '播放';
    button.dataset.playing = String(activeStates.includes(state));
    button.setAttribute('aria-label', activeStates.includes(state) ? '暂停朗读' : state === 'paused' ? '继续朗读' : '开始朗读');
    button.disabled = !this.supported || this.chapter.access !== 'allowed';
    $('#segment-progress').disabled = !this.segments?.length;
    $('.rd-error').hidden = state !== 'error';
    if ('mediaSession' in navigator) navigator.mediaSession.playbackState = state === 'playing' ? 'playing' : state === 'paused' ? 'paused' : 'none';
    this.globalPlayer?.changed();
  }
  notice(text) {
    const element = $('#reader-notice'); element.textContent = text; element.hidden = false;
    clearTimeout(this.noticeTimer); this.noticeTimer = setTimeout(() => { element.hidden = true; }, 4500);
  }
  engineFailed(info) {
    if (!this.wantRun && !this.player.source && !activeStates.includes(this.state)) return;
    this.op++; this.playAbort?.abort(); this.repository.cancel();
    this.scheduler.enabled = false; this.scheduler.invalidate();
    cancelAnimationFrame(this.animation);
    const error = Object.assign(new Error(info.message || '语音引擎已中断，请重试'), {code: info.code, diagnostics: info.diagnostics});
    this.lastError = error;
    this.fail(error);
  }
  fail(error) {
    if (error.name === 'AbortError') return;
    if (error.code === 429) { this.rateLimited(error); return; }
    if (this.player.source) this.offset = this.player.position();
    this.wantRun = false; this.scheduler.enabled = false; this.player.stop(); this.active = null;
    this.setState('error', error.message); $('#error-text').textContent = error.message; this.save();
    this.event('error', {message:error.message, code:error.code});
  }
  rateLimited(error) {
    if (this.player.source) this.offset = this.player.position();
    this.op++; this.playAbort?.abort(); this.repository.cancel();
    this.wantRun = false; this.pendingNavigation = null; this.scheduler.enabled = false;
    this.scheduler.invalidate(); this.player.stop(); this.active = null;
    this.setState('paused', error.message); this.notice(error.message); this.save();
    this.event('rate-limit', {retryAt:error.retryAt});
  }
  install(chapter, render = true) {
    this.player.stop(); this.active = null; this.chapter = chapter;
    this.feedback?.chapterChanged();
    this.sessionId = chapter.bookIndexId + ':' + (++this.sessionSequence);
    this.segments = segmentChapter(chapter);
    this.index = 0; this.offset = 0; this.selectedParagraph = null;
    this.scheduler.reset(this.segments, this.sessionId); this.scheduler.setSpeed(this.settings.speed);
    if (render) {
      $('#reader-data').textContent = '';
      const article = $('#reader-prose');
      article.after(this.paragraphAction);
      article.replaceChildren();
      for (const paragraph of chapter.paragraphs) {
        const p = create('p', paragraph.text, 'rd-paragraph');
        p.tabIndex = 0; p.dataset.paragraph = paragraph.id; article.append(p);
      }
    }
    this.paragraphAction.hidden = true;
    $('#chapter-title').textContent = chapter.chapterName; $('#audio-title').textContent = chapter.chapterName;
    $('.rd-book').textContent = chapter.bookName; $('.rd-book-page').textContent = chapter.bookName;
    $('.rd-book').setAttribute('aria-label',chapter.bookName + '，打开目录');
    applyChapterMetadata(chapter.seo);
    for (const a of document.querySelectorAll('[data-book-page],.rd-book-page')) a.href = '/book/' + chapter.bookId + '.html';
    $('[data-comments]').href = '/book/comment-' + chapter.bookId + '.html';
    for (const direction of ['previous','next']) {
      const id = chapter[direction === 'previous' ? 'previousChapterId' : 'nextChapterId'];
      const a = $('[data-chapter="' + direction + '"]');
      a.href = id ? this.url(id) : '/book/indexList-' + chapter.bookId + '.html';
      a.setAttribute('aria-disabled', id ? 'false' : 'true');
    }
    $('#segment-progress').max = String(Math.max(0, this.segments.length - 1));
    const access = $('#access-message'); access.replaceChildren(); access.hidden = chapter.access === 'allowed';
    if (chapter.access !== 'allowed') {
      access.append(create('p', !this.paymentEnabled ? '付费章节订阅暂时关闭，可继续阅读免费章节。' :
        chapter.access === 'need_login' ? '此章为 VIP 章节，请先登录后订阅。' : '此章为 VIP 章节，需要订阅后才能继续阅读。'));
      const login = create('a','登录'); login.href = '/user/login.html?originUrl=' + encodeURIComponent(this.url(chapter.bookIndexId)); access.append(login);
      if (this.paymentEnabled && chapter.access === 'need_purchase') {
        const buy = create('button','购买本章 · ' + (chapter.price || 0) + ' 屋币'); buy.dataset.action = 'purchase'; access.append(buy);
      }
    }
    this.updatePosition(); this.setState('idle');
  }
  url(id) { return '/book/' + this.chapter.bookId + '/' + id + '.html'; }
  async play() {
    if (!this.ttsEnabled || !this.supported || this.chapter.access !== 'allowed' || this.wantRun) return;
    if (this.expired()) return;
    if (this.engine.state === 'idle' && !this.storage.read('resource-notice')) {
      this.notice('首次听书需准备约 156 MiB 语音资源，后续复用本地缓存。');
      this.storage.write('resource-notice', true);
    }
    const op = ++this.op; this.wantRun = true; this.scheduler.enabled = true;
    $('.rd-resume').hidden = true;
    this.setState('preparing', '正在确认本章朗读权限');
    const abort = new AbortController(); this.playAbort?.abort(); this.playAbort = abort;
    let timeout, permissionTimedOut = false;
    try {
      // Unlock immediately in the click gesture, except for an existing paused source.
      if (!this.player.source) await this.player.unlock();
      await this.globalPlayer.claim();
      // This deadline belongs only to chapter authorization. A slow model must
      // retain its own initialization/OOM error rather than a stale fetch timeout.
      timeout = setTimeout(() => { permissionTimedOut = true; abort.abort(); }, 15000);
      let current;
      try { current = await this.repository.fetch(this.chapter.bookId, this.chapter.bookIndexId, abort.signal); }
      finally { clearTimeout(timeout); timeout = null; }
      if (op !== this.op || !this.wantRun) return;
      if (current.access !== 'allowed') {
        this.wantRun = false; this.scheduler.enabled = false; this.install(current);
        this.setState('idle', current.access === 'need_login' ? '登录已失效，请登录后订阅' : '本章需要购买'); return;
      }
      if (current.contentVersion !== this.chapter.contentVersion || current.accountScope !== this.chapter.accountScope) {
        this.install(current); this.notice('正文或账户已更新，从本章开头朗读。');
      } else this.chapter = current;
      if (this.index >= this.segments.length) { this.index = 0; this.offset = 0; }
      if (this.engine.state === 'loading' && this.engine.lastProgress?.message)
        this.setState('preparing', this.engine.lastProgress.message);
      await this.engine.ready();
      if (op !== this.op || !this.wantRun) return;
      if (this.player.source && this.active?.speed === this.settings.speed) {
        await this.player.resume();
        if (op !== this.op || !this.wantRun) return;
        this.scheduler.enabled = true; this.setState('playing'); this.tick();
        this.scheduler.prefetch(this.index + 1);
      } else {
        await this.player.unlock();
        if (op !== this.op || !this.wantRun) return;
        await this.step(op);
      }
    } catch (error) { if (op === this.op) this.fail(permissionTimedOut ? new Error('章节校验超时，请重试') : error); }
    finally { clearTimeout(timeout); }
  }
  async step(op) {
    if (!this.wantRun || op !== this.op || this.expired()) return;
    if (this.index >= this.segments.length) { await this.chapterEnded(op); return; }
    this.setState(this.player.stats.starts ? 'buffering' : 'preparing', '正在生成本段语音');
    try {
      const index = this.index, session = this.sessionId;
      const audio = await this.scheduler.get(index);
      if (op !== this.op || !this.wantRun || session !== this.sessionId || this.expired()) return;
      this.active = audio;
      this.player.play(audio, this.offset, () => {
        if (session !== this.sessionId) return;
        this.event('ended', {segmentId:this.segments[index].segmentId});
        this.active = null; this.offset = 0; this.index = index + 1; this.save();
        if (this.wantRun) this.step(this.op);
      });
      this.offset = 0;
      this.event('started', {segmentId:this.segments[index].segmentId, speed:audio.speed, duration:audio.duration});
      this.setState('playing'); this.highlight(); this.tick(); this.save();
      this.scheduler.enabled = true; this.scheduler.prefetch(index + 1);
      if (this.settings.autoNext && this.index >= this.segments.length - 2 && this.sleepMode !== 'chapter') this.repository.prefetch(this.chapter);
    } catch (error) { if (op === this.op) this.fail(error); }
  }
  async pause() {
    this.wantRun = false; this.scheduler.enabled = false;
    if (this.pendingNavigation) { this.pendingNavigation.resume = false; this.pendingNavigation.paused = true; }
    else this.op++;
    this.playAbort?.abort();
    await this.player.pause(); this.setState('paused'); this.save(); this.event('paused');
  }
  stop(message = '已停止，可从当前位置继续') {
    this.save(); this.offset = this.player.source ? this.player.position() : this.offset;
    this.wantRun = false; this.op++; this.playAbort?.abort(); this.repository.cancel();
    this.pendingNavigation = null;
    this.scheduler.enabled = false; this.scheduler.invalidate(); this.player.stop(); this.active = null;
    this.followSuspended = false; $('[data-action="follow-current"]').hidden = true;
    this.setState('idle', message); this.event('stopped');
  }
  async jump(index, forcePlay = false) {
    this.save();
    const wasRunning = this.wantRun, paused = this.state === 'paused';
    this.stop(); this.index = Math.max(0, Math.min(this.segments.length - 1, index)); this.offset = 0;
    this.updatePosition();
    for (const p of document.querySelectorAll('.rd-paragraph.is-current')) p.classList.remove('is-current');
    if (forcePlay || wasRunning) await this.play(); else if (paused) this.setState('paused');
  }
  async navigate(id, push = true, auto = false) {
    if (!id) { if (auto) this.stop('已到最后一章'); else this.notice('已经没有更多章节'); return; }
    const resume = this.wantRun || !!this.pendingNavigation?.resume;
    const paused = this.state === 'paused' || !!this.pendingNavigation?.paused;
    this.save(); this.wantRun = false; this.scheduler.enabled = false;
    this.player.stop(); this.active = null; this.scheduler.invalidate(); this.playAbort?.abort();
    const op = ++this.op; this.pendingNavigation = {id, push, resume, paused};
    this.setState('buffering', '正在加载章节');
    try {
      const chapter = await this.repository.load(this.chapter.bookId, String(id));
      if (op !== this.op) return;
      const shouldResume = this.pendingNavigation.resume;
      const shouldPause = this.pendingNavigation.paused;
      this.install(chapter); this.pendingNavigation = null;
      const url = this.url(chapter.bookIndexId);
      if (push) history.pushState({chapterId:chapter.bookIndexId},'',url);
      window.scrollTo({top:0,behavior:'instant'});
      this.entered(); this.offerProgress(); this.event('chapter');
      window.dispatchEvent(new CustomEvent('novel:chapter-view', {detail:{bookId:chapter.bookId, chapterId:chapter.bookIndexId}}));
      if (chapter.access !== 'allowed') {
        this.setState('idle', chapter.access === 'need_login' ? '本章需要登录和订阅' : '本章需要购买'); return;
      }
      if (shouldResume) { $('.rd-resume').hidden = true; this.wantRun = true; this.scheduler.enabled = true; await this.step(op); }
      else if (shouldPause) this.setState('paused');
    } catch (error) { if (op === this.op) this.fail(error); }
  }
  async chapterEnded(op) {
    if (op !== this.op) return;
    if (this.sleepMode === 'chapter') { this.stop('已按设置在本章结束时停止'); this.sleepMode = 'off'; return; }
    if (this.settings.autoNext && this.chapter.nextChapterId) await this.navigate(this.chapter.nextChapterId,true,true);
    else { this.wantRun = false; this.scheduler.enabled = false; this.setState('ended'); this.save(); }
  }
  highlight() {
    if (!this.player.source) return;
    const segment = this.segments[this.index]; if (!segment) return;
    for (const p of document.querySelectorAll('.rd-paragraph')) p.classList.toggle('is-current', p.dataset.paragraph === segment.paragraphId);
    if (this.settings.follow && !this.followSuspended && this.state === 'playing') {
      const p = document.querySelector('.rd-paragraph.is-current'), box = p?.getBoundingClientRect();
      const panel = $('.rd-player');
      const playerTop = panel.hidden ? window.innerHeight : panel.getBoundingClientRect().top;
      if (box && (box.top < 76 || box.bottom > playerTop - 20)) p.scrollIntoView({block:'center',behavior:'instant'});
    }
  }
  updatePosition() {
    $('#segment-progress').value = String(Math.min(this.index, Math.max(0, this.segments.length - 1)));
    const offset = this.player.source ? this.player.position() : this.offset;
    $('#segment-count').textContent = this.segments.length ? Math.min(this.index + 1,this.segments.length) + ' / ' + this.segments.length + ' · ' + Math.floor(offset) + '秒' : '无可朗读正文';
  }
  tick() {
    cancelAnimationFrame(this.animation);
    const tick = () => {
      if (this.state !== 'playing' || !this.wantRun || this.expired()) return;
      this.updatePosition();
      if (!this.lastSaved || performance.now() - this.lastSaved > 2000) { this.save(); this.lastSaved = performance.now(); }
      this.animation = requestAnimationFrame(tick);
    };
    tick();
  }
  save() {
    const segment = this.segments[this.index];
    if (!segment || this.chapter.access !== 'allowed') return;
    this.storage.saveProgress(this.chapter, {bookId:this.chapter.bookId, bookIndexId:this.chapter.bookIndexId,
      contentVersion:this.chapter.contentVersion, paragraphId:segment.paragraphId, segmentId:segment.segmentId,
      offset:this.player.source ? this.player.position() : this.offset, speed:this.active?.speed || this.settings.speed,
      modelId:'matcha-icefall-zh-en', savedAt:Date.now()});
    this.globalPlayer?.changed();
  }
  offerProgress() {
    const saved = this.storage.progress(this.chapter); this.savedProgress = saved;
    $('.rd-resume').hidden = !saved || !this.ttsEnabled || !this.supported || this.chapter.access !== 'allowed';
    if (saved) $('#resume-description').textContent = saved.bookIndexId === this.chapter.bookIndexId ? '已保存本章朗读位置' : '已保存本书另一章的朗读位置';
  }
  async resumeSaved() {
    const saved = this.savedProgress; if (!saved) return;
    // Unlock in the original click, before any navigation request.
    try { await this.player.unlock(); } catch (error) { this.fail(error); return; }
    if (saved.bookIndexId !== this.chapter.bookIndexId) await this.navigate(saved.bookIndexId);
    if (this.chapter.bookIndexId !== saved.bookIndexId || this.chapter.access !== 'allowed') return;
    if (this.chapter.contentVersion === saved.contentVersion) {
      this.index = Math.max(0,this.segments.findIndex(s => s.segmentId === saved.segmentId));
      this.offset = saved.speed === this.settings.speed && saved.modelId === 'matcha-icefall-zh-en' ? Math.max(0,saved.offset || 0) : 0;
      if (saved.speed !== this.settings.speed) this.notice('语速已改变，从保存片段的开头继续。');
    } else { this.index = 0; this.offset = 0; this.notice('正文版本已更新，从本章开头继续。'); }
    $('.rd-resume').hidden = true; await this.play();
  }
  applySettings() {
    document.documentElement.dataset.theme = this.settings.theme;
    this.root.dataset.theme = this.settings.theme;
    const dark = this.settings.theme === 'dark' || (this.settings.theme === 'auto' && matchMedia('(prefers-color-scheme: dark)').matches);
    $('[data-action="theme"] span:last-child').textContent = dark ? '白天' : '夜间';
    $('[data-action="theme"]').setAttribute('aria-label',dark ? '切换纸白主题' : '切换夜间主题');
    for (const [key,value] of Object.entries({'--rd-body-size':this.settings.fontSize+'px','--rd-line-height':this.settings.lineHeight,
      '--rd-reading-width':this.settings.width+'px','--rd-font':this.settings.font === 'serif' ? '"Source Han Serif SC","Noto Serif SC","Songti SC","SimSun",serif' : '"Microsoft YaHei","PingFang SC",sans-serif'}))
      document.documentElement.style.setProperty(key,String(value));
    $('.rd-speed').textContent = this.settings.speed.toFixed(2).replace(/0$/,'') + '×';
  }
  changeSetting(name, value) {
    this.settings[name] = value; this.storage.write('settings',this.settings); this.applySettings();
    if (name === 'speed') {
      this.scheduler.setSpeed(value);
      if (this.state === 'paused') { this.player.stop(); this.active = null; this.offset = 0; }
      else if (this.wantRun && !this.active) this.step(this.op);
      this.notice(this.active ? '语速从下一片段生效' : '新语速已应用');
    }
    if (name === 'follow' && value) { this.followSuspended = false; $('[data-action="follow-current"]').hidden = true; this.highlight(); }
  }
  sleep(mode) {
    this.sleepMode = mode; this.deadline = ['15','30'].includes(mode) ? Date.now() + Number(mode) * 60000 : null;
    clearTimeout(this.sleepTimer);
    if (this.deadline) this.sleepTimer = setTimeout(() => this.expired(), this.deadline - Date.now());
  }
  expired() {
    if (this.deadline && Date.now() >= this.deadline) {
      this.deadline = null; this.sleepMode = 'off'; this.stop('定时时间已到，朗读已停止'); return true;
    }
    return false;
  }
  async entered() {
    // Side effects only on entry, never in ChapterRepository.prefetch.
    this.post('/book/addVisitCount',{bookId:this.chapter.bookId}).catch(() => {});
    if (this.chapter.accountScope !== 'anonymous') this.post('/user/addReadHistory',{bookId:this.chapter.bookId,preContentId:this.chapter.bookIndexId}).catch(() => {});
    try {
      const books = JSON.parse(localStorage.getItem('historyBooks') || '[]');
      const ids = books.filter(b => typeof b === 'string' || typeof b === 'number').map(String);
      localStorage.setItem('historyBooks',JSON.stringify([...ids.filter(id => id !== this.chapter.bookId),this.chapter.bookId].slice(-50)));
    } catch {}
  }
  async post(url, values) {
    const response = await fetch(url,{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams(values)});
    const result = await response.json();
    if (result.code === 1001) {
      location.href = '/user/login.html?originUrl=' + encodeURIComponent(this.url(this.chapter.bookIndexId));
      throw new Error('请先登录');
    }
    if (!response.ok || result.code !== 200) throw new Error(result.msg || '操作失败');
    return result.data;
  }
  async purchase() {
    if (!this.paymentEnabled) { this.notice('付费章节订阅暂时关闭'); return; }
    if (!confirm('确认购买本章？价格 ' + (this.chapter.price || 0) + ' 屋币。')) return;
    try {
      await this.post('/user/buyBookIndex',{bookId:this.chapter.bookId,bookName:this.chapter.bookName,
        bookIndexId:this.chapter.bookIndexId,bookIndexName:this.chapter.chapterName});
      await this.navigate(this.chapter.bookIndexId,false);
    } catch (error) { this.notice(error.message); }
  }
  openPanel(kind, trigger) {
    this.panelRevision = (this.panelRevision || 0) + 1;
    this.panelKind = kind;
    this.panelTrigger = trigger || document.activeElement; const dialog = $('#reader-panel'), body = $('#panel-body'); body.replaceChildren();
    $('#panel-title').textContent = {reading:'阅读设置',voice:'听书设置',catalog:'章节目录'}[kind];
    dialog.showModal();
    if (kind === 'catalog') { this.catalog(body); return; }
    const select = (name, label, values) => {
      const row = create('div',null,'rd-setting'), l = create('label',label), input = create('select');
      input.id = 'setting-' + name; l.htmlFor = input.id;
      for (const [value,text] of values) { const option = create('option',text); option.value = String(value); input.append(option); }
      input.value = String(name === 'sleep' ? this.sleepMode || 'off' : this.settings[name]);
      input.onchange = () => name === 'sleep' ? this.sleep(input.value) : this.changeSetting(name,
        ['fontSize','lineHeight','width','speed'].includes(name) ? Number(input.value) : input.value);
      row.append(l,input); body.append(row);
    };
    const check = (name,label) => {
      const row = create('div',null,'rd-setting'), l = create('label'), input = create('input'); input.type = 'checkbox'; input.checked = this.settings[name];
      input.id = 'setting-' + name; input.onchange = () => this.changeSetting(name,input.checked); l.append(input,document.createTextNode(label)); row.append(l); body.append(row);
    };
    if (kind === 'reading') {
      select('font','正文字体',[['serif','宋体'],['sans','黑体']]);
      select('fontSize','字号', [16,18,20,22,24,28,32].map(x => [x,x+' px']));
      select('lineHeight','行距',[1.6,1.8,2,2.2].map(x => [x,x+' 倍']));
      select('width','行宽',[[540,'窄'],[620,'标准'],[740,'宽']]);
      select('theme','主题',[['auto','跟随系统'],['light','纸白'],['green','护眼'],['dark','夜间']]);
    } else {
      body.append(create('p','中英双语 · 一个音色', 'rd-help'));
      select('speed','语速',[0.8,1,1.25,1.5].map(x => [x,x+'×']));
      body.append(create('p','语速在当前片段播完后生效；暂停时调整会从当前片段开头重读。','rd-help'));
      select('sleep','定时停止',[['off','不启用'],['chapter','本章结束'],['15','15 分钟'],['30','30 分钟']]);
      check('follow','跟随朗读位置'); check('autoNext','自动朗读下一章');
      body.append(create('p','语音引擎全站共用，刷新和切章会复用已初始化的模型。首次资源会保存在本地；关闭所有站点页面或浏览器回收引擎后，可从缓存重新恢复。手机锁屏或后台运行可能中断，返回后可点击继续。','rd-help'));
    }
    body.querySelector('select,input,button')?.focus();
  }
  async catalog(body, page = 1) {
    const panel = body;
    const panelRevision = this.panelRevision;
    const requestVersion = this.catalogRequest = (this.catalogRequest || 0) + 1;
    if (page === 1) {
      const l = create('label','查找当前目录页'), input = create('input'); input.type = 'search'; input.id = 'catalog-search'; l.htmlFor = input.id;
      input.oninput = () => { for (const b of panel.querySelectorAll('.rd-catalog button')) b.hidden = !b.textContent.includes(input.value); };
      body.append(l,input,create('div',null,'rd-catalog'));
      const full = create('a','打开完整目录'); full.href = '/book/indexList-' + this.chapter.bookId + '.html'; body.append(full);
    }
    const op = this.sessionId;
    try {
      const response = await fetch('/book/reader/catalog?' + new URLSearchParams({bookId:this.chapter.bookId,page}),{cache:'no-store',credentials:'same-origin'});
      const result = await response.json();
      if (!response.ok || result.code !== 200) throw new Error('目录加载失败');
      if (op !== this.sessionId || !$('#reader-panel').open || this.panelKind !== 'catalog' ||
          panelRevision !== this.panelRevision || requestVersion !== this.catalogRequest) return;
      $('.rd-catalog').replaceChildren();
      for (const entry of result.data.entries) {
        const button = create('button',entry.name + (entry.vip ? ' · VIP' : '')); button.setAttribute('aria-current',String(entry.id === this.chapter.bookIndexId));
        button.onclick = () => { $('#reader-panel').close(); this.navigate(entry.id); }; $('.rd-catalog').append(button);
      }
      body.querySelector('.rd-catalog-pages')?.remove();
      const pages = create('div',null,'rd-catalog-pages');
      if (page > 1) { const prev = create('button','上一页'); prev.onclick = () => this.catalog(body,page-1); pages.append(prev); }
      if (result.data.hasMore) { const next = create('button','下一页'); next.onclick = () => this.catalog(body,page+1); pages.append(next); }
      body.append(pages); $('#catalog-search').value = '';
      body.querySelector('[aria-current="true"]')?.scrollIntoView({block:'nearest'});
    } catch (error) {
      if (!$('#reader-panel').open || this.panelKind !== 'catalog' || panelRevision !== this.panelRevision ||
          requestVersion !== this.catalogRequest) return;
      this.notice(error.message);
      const retry = create('button','重试目录'); retry.onclick = () => { retry.remove(); this.catalog(body,page); }; body.append(retry);
    }
  }
  bind() {
    this.root.addEventListener('click', async event => {
      const target = event.target.closest('[data-action],[data-chapter]'); if (!target) return;
      const action = target.dataset.action;
      if (target.dataset.chapter) {
        event.preventDefault(); this.navigate(this.chapter[target.dataset.chapter === 'next' ? 'nextChapterId' : 'previousChapterId']); return;
      }
      event.preventDefault();
      if (['reading','voice','catalog'].includes(action)) { this.openPanel(action,target); return; }
      switch(action) {
        case 'close': $('#reader-panel').close(); break;
        case 'play': activeStates.includes(this.state) ? await this.pause() : await this.play(); break;
        case 'stop': this.stop(); break;
        case 'retry':
          if (this.pendingNavigation) { const p = this.pendingNavigation; this.wantRun = p.resume; this.navigate(p.id,p.push); }
          else await this.play();
          break;
        case 'previous-segment': await this.jump(this.paragraphStart(-1)); break;
        case 'next-segment': await this.jump(this.paragraphStart(1)); break;
        case 'from-paragraph': await this.jump(this.segments.findIndex(s => s.paragraphId === this.selectedParagraph),true); break;
        case 'resume-saved': await this.resumeSaved(); break;
        case 'theme':
          this.changeSetting('theme',(this.settings.theme === 'dark' ||
            (this.settings.theme === 'auto' && matchMedia('(prefers-color-scheme: dark)').matches)) ? 'light' : 'dark'); break;
        case 'focus': this.root.classList.toggle('rd-focus'); target.setAttribute('aria-pressed',String(this.root.classList.contains('rd-focus'))); break;
        case 'follow-current': this.followSuspended = false; target.hidden = true; this.highlight(); break;
        case 'bookmark':
          this.bookmarkPanel(target); break;
        case 'shelf':
          try { await this.post('/user/addToBookShelf',{bookId:this.chapter.bookId,preContentId:this.chapter.bookIndexId}); target.textContent = '已加入书架'; }
          catch(error) { this.notice(error.message); } break;
        case 'purchase': await this.purchase(); break;
      }
    });
    $('#reader-prose').addEventListener('click',event => {
      if (window.getSelection()?.toString()) return;
      const p = event.target.closest('[data-paragraph]'); if (p) this.selectParagraph(p);
    });
    $('#reader-prose').addEventListener('keydown',event => {
      if (event.key === 'Enter') { const p = event.target.closest('[data-paragraph]'); if(p) this.selectParagraph(p); }
    });
    $('#segment-progress').addEventListener('change',event => this.jump(Number(event.target.value)));
    $('#reader-panel').addEventListener('close',() => {
      if (!$('#reader-panel').open) { this.panelRevision = (this.panelRevision || 0) + 1; this.panelTrigger?.focus(); }
    });
    $('#reader-panel').addEventListener('click',event => { if (event.target === $('#reader-panel')) $('#reader-panel').close(); });
    const manual = () => {
      if (this.state !== 'playing' || !this.settings.follow) return;
      this.followSuspended = true; $('[data-action="follow-current"]').hidden = false;
    };
    window.addEventListener('wheel',manual,{passive:true}); $('#reader-prose').addEventListener('touchmove',manual,{passive:true});
    document.addEventListener('keydown',event => {
      if (event.ctrlKey || event.metaKey || event.altKey || $('#reader-panel').open || this.feedback?.dialog.open ||
          event.target.closest('input,textarea,select,button,a,[contenteditable="true"]') || window.getSelection()?.toString()) return;
      if (event.code === 'Space') { event.preventDefault(); activeStates.includes(this.state) ? this.pause() : this.play(); }
      if (['ArrowUp','ArrowDown','PageUp','PageDown'].includes(event.key)) manual();
    });
    window.addEventListener('popstate',() => {
      const match = location.pathname.match(/^\/book\/(\d+)\/(\d+)\.html$/);
      if (match?.[1] === this.chapter.bookId) this.navigate(match[2],false);
      else { this.stop(); location.reload(); }
    });
    document.addEventListener('visibilitychange',() => { this.save(); this.expired(); });
    window.addEventListener('pagehide',event => {
      this.save(); this.globalPlayer?.handoff(); this.stop(); cancelAnimationFrame(this.animation); clearTimeout(this.sleepTimer);
      this.engine.dispose(); this.player.dispose();
    });
    window.addEventListener('pageshow',event => { if (event.persisted) { this.player.context = null; this.setState('idle','已返回阅读页，点击继续朗读'); } });
  }
  paragraphStart(direction) {
    const current = this.segments[this.index]?.paragraphId;
    if (direction > 0) {
      const next = this.segments.findIndex((s,i) => i > this.index && s.paragraphId !== current);
      return next >= 0 ? next : this.segments.length - 1;
    }
    let i = Math.max(0,this.index - 1), p = this.segments[i]?.paragraphId;
    while (i > 0 && this.segments[i-1].paragraphId === p) i--;
    return i;
  }
  bookmarkPanel(trigger) {
    this.panelRevision = (this.panelRevision || 0) + 1;
    this.panelKind = 'bookmark';
    const key = 'bookmark.'+this.chapter.accountScope+'.'+this.chapter.bookId;
    const saved = this.storage.read(key), body = $('#panel-body');
    this.panelTrigger = trigger; body.replaceChildren(); $('#panel-title').textContent = '本地书签';
    body.append(create('p','书签保存在此浏览器，不会同步到其他设备。','rd-help'));
    if (saved) {
      const open = create('button','打开已保存的书签');
      open.onclick = async () => {
        $('#reader-panel').close(); this.stop();
        if (saved.chapterId !== this.chapter.bookIndexId) await this.navigate(saved.chapterId);
        const p = [...document.querySelectorAll('.rd-paragraph')].find(e => e.dataset.paragraph === saved.paragraphId);
        if (p) { this.selectParagraph(p); p.scrollIntoView({block:'center',behavior:'instant'}); }
      }; body.append(open);
    }
    const save = create('button','保存当前位置');
    save.onclick = () => {
      const ok = this.storage.write(key,{chapterId:this.chapter.bookIndexId,paragraphId:this.selectedParagraph || this.segments[this.index]?.paragraphId});
      this.notice(ok ? '本地书签已保存到此浏览器' : '浏览器存储不可用，书签未保存'); $('#reader-panel').close();
    };
    body.append(save); $('#reader-panel').showModal(); body.querySelector('button')?.focus();
  }
  selectParagraph(p) {
    this.selectedParagraph = p.dataset.paragraph;
    for (const element of document.querySelectorAll('.rd-paragraph')) element.classList.toggle('is-selected',element === p);
    this.paragraphAction.hidden = !this.ttsEnabled || !this.supported || !this.segments.some(s => s.paragraphId === p.dataset.paragraph);
    p.after(this.paragraphAction);
  }
  installMediaSession() {
    if (!('mediaSession' in navigator)) return;
    try {
      navigator.mediaSession.setActionHandler('play',() => this.play());
      navigator.mediaSession.setActionHandler('pause',() => this.pause());
      navigator.mediaSession.setActionHandler('stop',() => this.stop());
      navigator.mediaSession.setActionHandler('nexttrack',() => this.jump(this.paragraphStart(1)));
      navigator.mediaSession.setActionHandler('previoustrack',() => this.jump(this.paragraphStart(-1)));
    } catch {}
  }
}
try {
  window.novelReader = new NovelReader(JSON.parse($('#reader-data').textContent));
} catch(error) {
  $('#audio-state').textContent = '朗读加载失败，正文仍可阅读。';
  console.error(error);
}
