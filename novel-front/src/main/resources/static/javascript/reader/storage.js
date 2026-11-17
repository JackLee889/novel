const prefix = 'novel.reader.v1';
export class ReaderStorage {
  constructor(storage) {
    try { this.storage = storage || globalThis.localStorage; } catch { this.storage = null; }
  }
  read(key, fallback = null) { try { return JSON.parse(this.storage.getItem(prefix + '.' + key)) ?? fallback; } catch { return fallback; } }
  write(key, value) { try { this.storage.setItem(prefix + '.' + key, JSON.stringify(value)); return true; } catch { return false; } }
  settings() {
    let old = {};
    try {
      const size = Number(this.storage.getItem('fontNum'));
      if (size >= 16 && size <= 32) old.fontSize = size;
      if (this.storage.getItem('colorNum') === '6' || /(?:^|;\s*)readbg=6(?:;|$)/.test(globalThis.document?.cookie || '')) old.theme = 'dark';
    } catch {}
    const saved = this.read('settings', {});
    if (!old.theme && !saved.theme) {
      try { const theme = this.storage.getItem('novel.site.theme'); if (theme === 'light' || theme === 'dark') old.theme = theme; } catch {}
    }
    return sanitize({...old, ...saved});
  }
  progress(chapter) { return this.read('progress.' + chapter.accountScope + '.' + chapter.bookId); }
  saveProgress(chapter, value) { return this.write('progress.' + chapter.accountScope + '.' + chapter.bookId, value); }
}
export function sanitize(value = {}) {
  const allowed = (name, options, fallback) => options.includes(value[name]) ? value[name] : fallback;
  return {theme: allowed('theme', ['auto', 'light', 'green', 'dark'], 'auto'),
    font: allowed('font', ['serif', 'sans'], 'serif'),
    fontSize: Math.min(32, Math.max(16, Number(value.fontSize) || 20)),
    lineHeight: allowed('lineHeight', [1.6, 1.8, 2, 2.2], 2),
    width: allowed('width', [540, 620, 740], 620),
    speed: allowed('speed', [0.8, 1, 1.25, 1.5], 1),
    follow: value.follow !== false, autoNext: value.autoNext !== false};
}
