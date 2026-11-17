import {ReaderStorage} from './storage.js';
const make = (tag, className, text) => {
  const node = document.createElement(tag); node.className = className;
  if (text) node.textContent = text;
  return node;
};

// Presentation only. Folding the controls never changes the audio session.
export class PlayerDock {
  constructor(panel, storage = new ReaderStorage()) {
    this.panel = panel; this.storage = storage;
    this.pinned = this.storage.read('player-pinned') === true;
    this.expanded = this.pinned;
    panel.id ||= 'listen-controls'; panel.dataset.dockReady = 'true';
    this.orb = make('button', 'player-orb'); this.orb.type = 'button';
    this.orb.setAttribute('aria-controls', panel.id);
    const book = make('span', 'player-orb-book'); book.setAttribute('aria-hidden', 'true');
    const waves = make('span', 'player-orb-waves'); waves.setAttribute('aria-hidden', 'true');
    for (let i = 0; i < 3; i++) waves.append(make('i', ''));
    const badge = make('span', 'player-orb-badge'); badge.setAttribute('aria-hidden', 'true');
    this.orb.append(book, waves, make('span', 'player-orb-label', '听书'), badge);
    const toolbar = make('div', 'player-dock-toolbar');
    const actions = make('div', 'player-dock-actions');
    this.pin = make('button', 'player-dock-pin'); this.pin.type = 'button';
    const pinIcon = make('span', 'player-dock-pin-icon'); pinIcon.setAttribute('aria-hidden', 'true');
    this.pinLabel = make('span', '', '固定'); this.pin.append(pinIcon, this.pinLabel);
    this.close = make('button', 'player-dock-close'); this.close.type = 'button';
    this.close.setAttribute('aria-label', '收起播放器'); this.close.title = '收起播放器';
    const closeIcon = make('span', 'player-dock-close-icon'); closeIcon.setAttribute('aria-hidden', 'true');
    this.close.append(closeIcon); actions.append(this.pin, this.close);
    toolbar.append(make('span', 'player-dock-heading', '听书'), actions); panel.prepend(toolbar);
    panel.after(this.orb);
    this.abort = new AbortController();
    const listen = (node, type, handler, options = {}) => node.addEventListener(type, handler,
      {...options, signal: this.abort.signal});
    listen(this.orb, 'click', () => this.open(this.orb.matches(':focus-visible')));
    listen(this.pin, 'click', () => this.setPinned(!this.pinned));
    listen(this.close, 'click', () => this.collapse(true));
    for (const type of ['pointermove', 'input', 'keydown', 'focusin', 'focusout'])
      listen(panel, type, () => this.schedule());
    listen(panel, 'pointerdown', () => { this.dragging = true; clearTimeout(this.timer); });
    listen(document, 'pointerup', () => { if (this.dragging) { this.dragging = false; this.schedule(); } });
    listen(document, 'pointercancel', () => { this.dragging = false; this.schedule(); });
    // Let the target receive its click before folding changes the page's bottom padding.
    listen(document, 'click', event => {
      if (!this.pinned && this.expanded && !panel.contains(event.target) && !this.orb.contains(event.target) &&
          !event.target.closest('dialog')) this.collapse();
    });
    listen(document, 'keydown', event => {
      if (event.key === 'Escape' && this.expanded && !document.querySelector('dialog[open]')) {
        event.preventDefault(); this.collapse(true);
      }
    });
    listen(window, 'storage', event => {
      if (event.key === 'novel.reader.v1.player-pinned') {
        this.pinned = this.storage.read('player-pinned') === true;
        this.expanded = this.pinned; this.render();
      }
    });
    listen(window, 'pagehide', event => { clearTimeout(this.timer); if (!event.persisted) this.dispose(); });
    listen(window, 'pageshow', event => {
      if (event.persisted) { this.pinned = this.storage.read('player-pinned') === true; this.expanded = this.pinned; this.render(); }
    });
    this.sizeObserver = new ResizeObserver(() => {
      if (this.expanded) document.body.style.setProperty('--player-dock-height', panel.offsetHeight + 'px');
    });
    this.sizeObserver.observe(panel);
    this.render(); this.update('idle');
  }
  open(keyboard = false) {
    this.expanded = true; this.render();
    if (keyboard) this.pin.focus();
    this.schedule();
  }
  setPinned(value) {
    this.pinned = value; this.expanded = value;
    this.storage.write('player-pinned', value); this.render();
    if (!value) this.orb.focus({preventScroll: true});
  }
  collapse(manual = false) {
    if (this.pinned && !manual) return;
    if (manual && this.pinned) { this.pinned = false; this.storage.write('player-pinned', false); }
    const focusInside = this.panel.contains(document.activeElement);
    this.expanded = false; this.render();
    if (focusInside) this.orb.focus({preventScroll: true});
  }
  schedule() {
    clearTimeout(this.timer);
    if (!this.expanded || this.pinned) return;
    this.timer = setTimeout(() => {
      const focused = document.activeElement;
      if (this.dragging || document.querySelector('dialog[open]') ||
          (this.panel.contains(focused) && focused.matches(':focus-visible'))) { this.schedule(); return; }
      this.collapse();
    }, 3000);
  }
  render() {
    clearTimeout(this.timer);
    this.panel.hidden = !this.expanded; this.orb.hidden = this.expanded;
    this.orb.setAttribute('aria-expanded', String(this.expanded));
    this.pin.setAttribute('aria-pressed', String(this.pinned));
    this.pin.setAttribute('aria-label', this.pinned ? '取消固定播放器' : '固定播放器');
    this.pin.title = this.pinned ? '取消固定，收成听书圆球' : '固定在底部，保持展开';
    this.pinLabel.textContent = this.pinned ? '已固定' : '固定';
    this.panel.dataset.pinned = String(this.pinned);
    document.body.classList.toggle('player-dock-collapsed', !this.expanded);
    document.body.classList.toggle('player-dock-expanded', this.expanded);
    if (this.expanded) document.body.style.setProperty('--player-dock-height', this.panel.offsetHeight + 'px');
  }
  update(state, chapter = '') {
    this.orb.dataset.state = state;
    const label = ({playing: '正在朗读', preparing: '正在准备语音', buffering: '正在生成语音',
      paused: '已暂停', ended: '本章已读完', error: '朗读中断'})[state] || '可以开始听书';
    this.orb.setAttribute('aria-label', '展开听书播放器：' + label + (chapter ? '，' + chapter : ''));
    this.orb.title = label + '，点击展开听书播放器';
  }
  dispose() { clearTimeout(this.timer); this.sizeObserver.disconnect(); this.abort.abort(); }
}
