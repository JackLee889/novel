import {assertRequestAllowed, rateLimitResponse, remainingRetrySeconds} from '../site/rate-limit.js?v=20261020.1';
export class ChapterRepository {
  constructor() { this.revision = 0; }
  cancel() {
    this.revision++; this.navigation?.abort(); this.prefetchController?.abort();
    this.prefetched = null;
  }
  async fetch(bookId, chapterId, signal) {
    const url = '/book/reader/chapter?' + new URLSearchParams({bookId, bookIndexId: chapterId});
    if (signal?.aborted) throw new DOMException('章节请求已取消', 'AbortError');
    assertRequestAllowed(url);
    const response = await fetch(url, {credentials: 'same-origin', cache: 'no-store', signal, headers:{Accept:'application/json'}});
    const limited = rateLimitResponse(response, url);
    if (limited) throw limited;
    let body;
    try { body = await response.json(); } catch { throw new Error('章节响应无效'); }
    if (!body.data || ![200,1001,5002].includes(body.code)) throw new Error(response.status === 404 ? '章节不存在' : '章节加载失败，请重试');
    const chapter = body.data;
    if (typeof chapter.bookId !== 'string' || typeof chapter.bookIndexId !== 'string' ||
        chapter.bookId !== bookId || chapter.bookIndexId !== chapterId) throw new Error('章节归属校验失败');
    if (chapter.access === 'allowed' && (!response.ok || body.code !== 200)) throw new Error('章节授权校验失败');
    if (chapter.access !== 'allowed') chapter.paragraphs = [];
    return chapter;
  }
  async load(bookId, chapterId) {
    this.cancel(); const revision = this.revision;
    const controller = new AbortController(); this.navigation = controller;
    const timeout = setTimeout(() => controller.abort(), 15000);
    try {
      // Always reauthorize at entry, including after an earlier prefetch.
      const chapter = await this.fetch(bookId, chapterId, controller.signal);
      if (revision !== this.revision) throw new DOMException('章节请求已过期', 'AbortError');
      return chapter;
    } catch (error) {
      if (error.code !== 429 && revision === this.revision && controller.signal.aborted) throw new Error('章节加载超时，请重试');
      throw error;
    } finally { clearTimeout(timeout); }
  }
  prefetch(chapter) {
    if (remainingRetrySeconds()) return;
    if (!chapter.nextChapterId || this.prefetched?.id === chapter.nextChapterId) return;
    this.prefetchController?.abort();
    const controller = new AbortController(); this.prefetchController = controller;
    const revision = this.revision;
    this.prefetched = {id: chapter.nextChapterId, access: 'loading'};
    const timeout = setTimeout(() => controller.abort(), 15000);
    this.fetch(chapter.bookId, chapter.nextChapterId, controller.signal).then(next => {
      if (revision === this.revision && !controller.signal.aborted) {
        // Retain only authorization metadata. Text is fetched afresh at entry.
        this.prefetched = {id: next.bookIndexId, access: next.access, accountScope: next.accountScope};
      }
    }).catch(() => {}).finally(() => clearTimeout(timeout));
  }
}
