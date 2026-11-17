/** Apply the server's public metadata after navigation without exposing paid text. */
export function applyChapterMetadata(meta, doc = document) {
  if (!meta || typeof meta !== 'object') return;
  if (typeof meta.title === 'string') doc.title = meta.title;
  for (const name of ['description', 'keywords', 'robots']) {
    let node = doc.querySelector(`meta[name="${name}"]`);
    if (!node) { node = doc.createElement('meta'); node.setAttribute('name', name); doc.head.append(node); }
    node.setAttribute('content', typeof meta[name] === 'string' ? meta[name] : name === 'robots' ? 'noindex,follow' : '');
  }
  let canonical = doc.querySelector('link[rel="canonical"]');
  let url = '';
  try { const parsed = new URL(meta.canonical); if (parsed.protocol === 'https:' && !parsed.username && !parsed.password) url = parsed.href; } catch {}
  if (url) {
    if (!canonical) { canonical = doc.createElement('link'); canonical.setAttribute('rel', 'canonical'); doc.head.append(canonical); }
    canonical.setAttribute('href', url);
  } else canonical?.remove();
  let jsonLd = doc.querySelector('#seo-jsonld');
  let data;
  try { data = typeof meta.jsonLd === 'string' && meta.jsonLd ? JSON.parse(meta.jsonLd) : null; } catch { data = null; }
  if (data) {
    if (!jsonLd) { jsonLd = doc.createElement('script'); jsonLd.id = 'seo-jsonld'; jsonLd.setAttribute('type', 'application/ld+json'); doc.head.append(jsonLd); }
    jsonLd.textContent = JSON.stringify(data).replace(/</g, '\\u003c').replace(/>/g, '\\u003e').replace(/&/g, '\\u0026');
  } else jsonLd?.remove();
}
