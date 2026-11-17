"""Capture actual catalog IDs and first/last chapter fingerprints for live QA."""
from pathlib import Path
import hashlib
import html
import json
import re
import subprocess

root = Path(__file__).resolve().parent.parent
run = (root / 'doc/frontend-realtest-current.txt').read_text(encoding='utf-8-sig').strip()
assert re.fullmatch(r'frontend-realtest-\d{8}-\d{6}', run)
evidence = root / 'doc' / run
runtime = json.loads((evidence / 'test-runtime.json').read_text(encoding='utf-8-sig'))
mysql = ['docker', 'compose', '--project-directory', runtime['deploymentRoot'], '-f', runtime['composeFile'],
         'exec', '-T', 'mysql', 'sh', '-c',
         'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --user=root --default-character-set=utf8mb4 --batch --raw --skip-column-names novel_plus']


def query(sql):
    response = subprocess.run(mysql, input=sql.encode('utf-8'), capture_output=True, timeout=120, check=True)
    return [json.loads(line) for line in response.stdout.decode('utf-8').splitlines()]


books = query("""SELECT JSON_OBJECT(
 'id',CAST(b.id AS CHAR),'name',b.book_name,'author',b.author_name,'catId',b.cat_id,
 'wordCount',b.word_count,'status',b.book_status,
 'chapterCount',(SELECT COUNT(*) FROM book_index i WHERE i.book_id=b.id),
 'firstId',CAST((SELECT i.id FROM book_index i WHERE i.book_id=b.id ORDER BY i.index_num,i.id LIMIT 1) AS CHAR),
 'lastId',CAST((SELECT i.id FROM book_index i WHERE i.book_id=b.id ORDER BY i.index_num DESC,i.id DESC LIMIT 1) AS CHAR)
) FROM book b ORDER BY b.word_count DESC,b.id;""")
categories = query("""SELECT JSON_OBJECT('id',c.id,'name',c.name,'direction',c.work_direction,
 'books',(SELECT COUNT(*) FROM book b WHERE b.cat_id=c.id)) FROM book_category c ORDER BY c.id;""")
news = query("SELECT JSON_OBJECT('id',CAST(id AS CHAR),'title',title) FROM news ORDER BY id;")
chapter_ids = list(dict.fromkeys(chapter for book in books for chapter in (book['firstId'], book['lastId']) if chapter))
assert chapter_ids and all(re.fullmatch(r'\d+', value) for value in chapter_ids)
samples = query("""SELECT JSON_OBJECT('id',CAST(i.id AS CHAR),'bookId',CAST(i.book_id AS CHAR),
 'name',i.index_name,'number',i.index_num,'vip',i.is_vip,'storage',i.storage_type,'html',c.content)
 FROM book_index i JOIN book_content c ON c.index_id=i.id WHERE i.id IN (""" + ','.join(chapter_ids) + ') ORDER BY i.book_id,i.index_num;')
for chapter in samples:
    text = re.sub(r'(?is)<(script|style)[^>]*>.*?</\1\s*>', '', chapter.pop('html'))
    text = re.sub(r'(?i)<br\s*/?>|</(?:p|div|li|h[1-6])\s*>', '\n', text)
    text = re.sub(r'(?s)<!--.*?-->', '', text)
    text = re.sub(r'</?[A-Za-z][^>]*>', '', text)
    text = html.unescape(text).replace('\u00a0', ' ').replace('\r', '')
    paragraphs = [line.strip() for line in re.split(r'\n+', text) if line.strip()]
    normalized = '\n'.join(paragraphs)
    chapter.update(paragraphCount=len(paragraphs), textBytes=len(normalized.encode('utf-8')),
                   contentVersion=hashlib.sha256(('reader-text-v1\n' + normalized).encode('utf-8')).hexdigest())
data = dict(books=books, categories=categories, news=news, samples=samples)
(evidence / 'actual-data-proof.json').write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(dict(books=len(books), chapters=sum(book['chapterCount'] for book in books),
                     sampledChapters=len(samples), categories=len(categories), news=len(news))))
