"""Read template routes, forms and API contracts before a visual overhaul."""
from pathlib import Path
import re,json
root=Path(__file__).resolve().parents[1]
templates=root/'novel-front/src/main/resources/templates'
result=[]
for p in sorted(templates.rglob('*.html')):
    s=p.read_text(encoding='utf-8-sig')
    result.append({
        'path':p.relative_to(templates).as_posix(),
        'title':re.findall(r'<title[^>]*>(.*?)</title>',s,re.S),
        'forms':re.findall(r'<form\b[^>]*>',s),
        'inputs':re.findall(r'<(?:input|textarea|select)\b[^>]*>',s),
        'scripts':re.findall(r'<script\b[^>]*src=[^>]*>',s),
        'apiUrls':sorted(set(re.findall(r'\burl\s*:\s*["\']([^"\']+)',s))),
        'payloads':re.findall(r'\bdata\s*:\s*(\{.*?\})(?:,|\s*$)',s,re.S),
        'onclick':re.findall(r'onclick\s*=\s*"([^"]+)',s),
        'inlineScripts':len(re.findall(r'<script(?![^>]*src)',s))
    })
out=root/'doc/frontend-evidence'
out.mkdir(parents=True,exist_ok=True)
(out/'template-audit.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
for x in result:
    if x['path'].startswith(('mobile/','common/')):continue
    print(json.dumps({k:x[k] for k in ['path','forms','inputs','apiUrls','payloads']},ensure_ascii=False))
