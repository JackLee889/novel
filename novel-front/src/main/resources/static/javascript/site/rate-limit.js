// Search and chapter requests share the same Cloudflare per-IP allowance.
const storageKey = 'novel.search-chapter.retry-at.v1';
const paths = new Set(['/book/searchByPage', '/book/bookclass.html', '/book/reader/chapter']);
const listeners = new Set(), controls = new Map();
let retryAt = 0, timer, notice;

export function isRateLimitedPath(input) {
  try {
    const base = globalThis.location?.origin || 'http://localhost';
    const url = new URL(input, base);
    return url.origin === base && (paths.has(url.pathname) || /^\/book\/.*\/.*\.html$/.test(url.pathname));
  } catch { return false; }
}
function storedDeadline() {
  try {
    const value = Number(globalThis.localStorage?.getItem(storageKey));
    return Number.isFinite(value) && value > Date.now() && value <= Date.now() + 86400000 ? value : 0;
  } catch { return 0; }
}
export function remainingRetrySeconds() {
  retryAt = Math.max(retryAt, storedDeadline());
  return Math.max(0, Math.ceil((retryAt - Date.now()) / 1000));
}
export class RateLimitError extends Error {
  constructor() {
    const seconds = remainingRetrySeconds();
    super(`访问过于频繁，请等待 ${seconds} 秒后重试。`);
    this.name = 'RateLimitError'; this.code = 429; this.status = 429; this.kind = 'rate-limit';
    this.retryAt = retryAt; this.retryAfter = seconds;
  }
}
export function assertRequestAllowed(path) {
  if (isRateLimitedPath(path) && remainingRetrySeconds()) throw new RateLimitError();
}
export function retryAfterSeconds(value, now = Date.now()) {
  if (typeof value !== 'string' || !value.trim()) return 10;
  const text = value.trim();
  const seconds = /^\d+$/.test(text) ? Number(text) : (Date.parse(text) - now) / 1000;
  return Number.isFinite(seconds) && seconds >= 0 ? Math.min(86400, Math.max(1, Math.ceil(seconds))) : 10;
}
function publish() {
  const error = new RateLimitError();
  for (const listener of listeners) { try { listener(error); } catch { /* UI listeners must not hide the original HTTP 429. */ } }
  renderCooldown();
}
export function rateLimitResponse(response, path) {
  if (response.status !== 429 || !isRateLimitedPath(path)) return null;
  const seconds = retryAfterSeconds(response.headers?.get('Retry-After'));
  retryAt = Math.max(retryAt, storedDeadline(), Date.now() + seconds * 1000);
  try { globalThis.localStorage?.setItem(storageKey, String(retryAt)); } catch { /* A blocked storage API must not break reading. */ }
  publish();
  return new RateLimitError();
}
export function onRateLimit(listener) {
  listeners.add(listener);
  if (remainingRetrySeconds()) listener(new RateLimitError());
  return () => listeners.delete(listener);
}
export function bindRateLimitRetry(button) {
  if (!button) return;
  controls.set(button, {label: button.textContent, disabled: button.disabled});
  renderCooldown();
}
function renderCooldown() {
  clearTimeout(timer);
  const seconds = remainingRetrySeconds();
  if (seconds && typeof document !== 'undefined' && document.createElement && document.body?.prepend) {
    if (!notice?.isConnected) {
      notice = document.createElement('div'); notice.dataset.rateLimitNotice = '';
      notice.setAttribute('role', 'status'); notice.setAttribute('aria-live', 'polite');
      Object.assign(notice.style, {padding:'12px 20px', background:'#fff3cf', color:'#493b16', textAlign:'center', lineHeight:'1.6'});
      document.body.prepend(notice);
    }
    notice.textContent = `访问过于频繁，搜索和章节加载已暂停。请在 ${seconds} 秒后重试。`;
  } else if (!seconds && notice) notice.textContent = '现在可以重试搜索或章节加载。';
  for (const [button, original] of controls) {
    if (button.isConnected === false) { controls.delete(button); continue; }
    button.disabled = seconds > 0 || original.disabled;
    button.textContent = seconds ? `${seconds} 秒后可重试` : original.label;
    if (!seconds) controls.delete(button);
  }
  // Update the display only. Expiry never retries a request or resumes playback.
  if (seconds && (notice || controls.size)) { timer = setTimeout(renderCooldown, 1000); timer?.unref?.(); }
}
if (typeof window !== 'undefined') {
  window.addEventListener('storage', event => {
    if (event.key !== storageKey) return;
    const deadline = storedDeadline();
    if (deadline > retryAt) { retryAt = deadline; publish(); }
  });
  window.addEventListener('pageshow', () => { if (remainingRetrySeconds()) publish(); });
}
if (typeof document !== 'undefined' && document.addEventListener) {
  const guardNavigation = (event, path) => {
    if (!isRateLimitedPath(path) || !remainingRetrySeconds()) return;
    event.preventDefault(); event.stopImmediatePropagation(); renderCooldown();
  };
  document.addEventListener('submit', event => {
    guardNavigation(event, event.target.action || globalThis.location?.href);
  }, true);
  document.addEventListener('click', event => {
    const link = event.target.closest?.('a[href]');
    if (link) guardNavigation(event, link.href);
  }, true);
  if (remainingRetrySeconds()) renderCooldown();
}
