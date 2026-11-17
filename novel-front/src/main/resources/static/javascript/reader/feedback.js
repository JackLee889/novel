import {authToken, authSnapshot, isCurrentAuth, onAuthChange} from '../site/auth-state.js?v=20261008.1';

/** A report captures the displayed chapter, independently of playback and navigation. */
export class ChapterFeedback {
  constructor(chapter, notice) {
    this.chapter = chapter; this.notice = notice; this.revision = 0;
    this.dialog = document.querySelector('#chapter-feedback');
    this.form = this.dialog.querySelector('form');
    this.submitButton = this.form.querySelector('[type="submit"]');
    this.status = this.dialog.querySelector('[data-feedback-status]');
    this.login = this.dialog.querySelector('[data-feedback-login]');
    this.success = this.dialog.querySelector('[data-feedback-success]');
    document.querySelector('[data-feedback-open]').addEventListener('click', event => this.open(event.currentTarget));
    this.dialog.querySelector('[data-feedback-close]').addEventListener('click', () => this.dialog.close());
    this.dialog.addEventListener('click', event => { if (event.target === this.dialog) this.dialog.close(); });
    this.dialog.addEventListener('close', () => { if (!this.dialog.open) { this.clear(); this.trigger?.focus({preventScroll:true}); } });
    this.form.addEventListener('submit', event => { event.preventDefault(); this.submit(); });
    this.form.elements.type.addEventListener('change', () => {
      this.form.elements.details.required = this.form.elements.type.value === 'other';
      this.dialog.querySelector('[data-feedback-detail-label]').textContent =
        this.form.elements.details.required ? '补充说明' : '补充说明（选填）';
    });
    onAuthChange(() => {
      if (this.context) { this.clear(); this.dialog.close(); this.notice('登录账号已变化，请重新打开本章反馈。'); }
    });
    window.addEventListener('pagehide', () => this.clear());
  }
  clear() {
    ++this.revision; this.pending?.abort(); this.pending = null; this.busy = false; this.context = null;
    this.form.reset(); this.form.elements.type.disabled = false; this.form.elements.details.disabled = false;
    this.form.elements.details.required = false; this.form.removeAttribute('aria-busy');
  }
  chapterChanged() {
    if (this.context && !this.sameChapter()) {
      this.clear(); this.dialog.close(); this.notice('已切换章节，请重新打开当前章节的反馈。');
    }
  }
  sameChapter() {
    const current = this.chapter();
    return this.context?.bookId === current.bookId && this.context?.bookIndexId === current.bookIndexId;
  }
  requireLogin() {
    this.login.hidden = false; this.submitButton.disabled = true;
    this.login.querySelector('a').href = '/user/login.html?originUrl=' + encodeURIComponent(
      '/book/' + this.context.bookId + '/' + this.context.bookIndexId + '.html');
  }
  open(trigger) {
    if (this.dialog.open) return;
    this.clear(); this.trigger = trigger; const current = this.chapter();
    this.context = {bookId:current.bookId, bookIndexId:current.bookIndexId}; this.identity = authSnapshot();
    this.dialog.querySelector('[data-feedback-book]').textContent = current.bookName;
    this.dialog.querySelector('[data-feedback-chapter]').textContent = current.chapterName;
    this.dialog.querySelector('[data-feedback-detail-label]').textContent = '补充说明（选填）';
    this.status.textContent = ''; this.success.hidden = true; this.form.hidden = false;
    this.login.hidden = true; this.submitButton.disabled = false; this.submitButton.textContent = '提交反馈';
    if (!authToken()) { this.form.hidden = true; this.requireLogin(); }
    this.dialog.showModal();
    (this.form.hidden ? this.login.querySelector('a') : this.form.elements.type).focus();
  }
  async submit() {
    if (this.busy || !this.context || !this.form.reportValidity()) return;
    if (!this.sameChapter()) { this.chapterChanged(); return; }
    if (!isCurrentAuth(this.identity)) { this.clear(); this.dialog.close(); this.notice('登录账号已变化，请重新打开本章反馈。'); return; }
    if (!authToken()) { this.requireLogin(); return; }
    const details = this.form.elements.details.value.trim(), type = this.form.elements.type.value;
    if (type === 'other' && !details) { this.status.textContent = '请简要描述遇到的问题。'; this.form.elements.details.focus(); return; }
    const revision = this.revision, identity = this.identity;
    this.busy = true; this.status.textContent = ''; this.submitButton.disabled = true;
    this.submitButton.textContent = '正在提交…'; this.form.setAttribute('aria-busy', 'true');
    this.form.elements.type.disabled = true; this.form.elements.details.disabled = true;
    const abort = new AbortController(); this.pending = abort; const timer = setTimeout(() => abort.abort(), 15000);
    try {
      const response = await fetch('/book/reader/feedback', {method:'POST', credentials:'same-origin', cache:'no-store',
        signal:abort.signal, headers:{'Content-Type':'application/x-www-form-urlencoded;charset=UTF-8', Accept:'application/json'},
        body:new URLSearchParams({...this.context, type, details})});
      const result = await response.json();
      if (revision !== this.revision || !isCurrentAuth(identity)) return;
      if (response.status === 401 || result.code === 1001) { this.status.textContent = '登录已失效，请登录后再提交。'; this.requireLogin(); return; }
      if (!response.ok || result.code !== 200) throw new Error(result.msg || '提交失败，请稍后重试。');
      this.form.hidden = true; this.success.hidden = false;
      this.success.querySelector('a').focus();
    } catch (error) {
      if (revision === this.revision && isCurrentAuth(identity)) this.status.textContent = error.name === 'AbortError'
        ? '提交超时，请到“我的反馈”确认是否已收到，再重试。'
        : error instanceof TypeError || error instanceof SyntaxError ? '网络连接失败，请稍后重试。' : error.message;
    } finally {
      clearTimeout(timer);
      if (revision === this.revision) {
        this.busy = false; this.pending = null; this.form.removeAttribute('aria-busy');
        this.form.elements.type.disabled = false; this.form.elements.details.disabled = false;
        this.submitButton.disabled = !this.login.hidden; this.submitButton.textContent = '提交反馈';
      }
    }
  }
}
