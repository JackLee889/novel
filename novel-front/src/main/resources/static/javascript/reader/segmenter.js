export function segmentChapter(chapter, target = 64, hardLimit = 180) {
  const segments = [];
  for (const paragraph of chapter.paragraphs || []) {
    const text = paragraph.text;
    if (!text || !/[\p{L}\p{N}]/u.test(text)) continue;
    let start = 0, count = 0, boundary = 0;
    // Unicode iteration keeps surrogate pairs intact; offsets are UTF-16 DOM offsets.
    for (let offset = 0; offset < text.length;) {
      const char = String.fromCodePoint(text.codePointAt(offset));
      offset += char.length; count++;
      const next = text[offset];
      const decimal = char === '.' && /\d/.test(text[offset - 2] || '') && /\d/.test(next || '');
      const abbreviation = char === '.' && /(?:\b(?:Mr|Mrs|Ms|Dr|Prof|St)|\b[A-Z])\.$/.test(text.slice(start, offset));
      const sentence = /[。！？；!?;\n]/.test(char) || (char === '.' && !decimal && !abbreviation && (!next || /\s/.test(next)));
      if (sentence || /[，,：:、\s]/.test(char)) boundary = offset;
      if (count >= hardLimit || (count >= target && sentence)) {
        let end = count >= hardLimit && boundary > start ? boundary : offset;
        // Include closing quotation marks with their sentence.
        while (end === offset && /[”’」』）)\]]/.test(text[end] || '') && count < hardLimit) { end++; count++; }
        append(start, end);
        start = end; offset = end; count = 0; boundary = end;
      }
    }
    if (start < text.length) append(start, text.length);
    function append(from, to) {
      const raw = text.slice(from, to);
      if (!/[\p{L}\p{N}]/u.test(raw)) return;
      segments.push({chapterId: chapter.bookIndexId, contentVersion: chapter.contentVersion,
        paragraphId: paragraph.id, segmentId: paragraph.id + ':' + from + ':' + to,
        startOffset: from, endOffset: to, text: raw});
    }
  }
  return segments;
}
