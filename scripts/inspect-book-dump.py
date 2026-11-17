"""Stream a mysqldump gzip without exposing account records or chapter text."""
import argparse
import gzip
import hashlib
import json
import re
import time
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('dump', type=Path)
parser.add_argument('evidence', type=Path)
args = parser.parse_args()
args.evidence.mkdir(exist_ok=True)
allowed = {'book', 'book_author', 'book_category', 'book_comment',
           'book_comment_reply', 'book_index', 'book_screen_bullet', 'book_setting',
           'book_content', *(f'book_content{i}' for i in range(10))}
tables = {}
ddl = {}
special = []
uncompressed = 0
lines = 0
current_ddl = None
ddl_lines = []
started = time.monotonic()
last_progress = started
tail = b''
with args.dump.open('rb') as raw, gzip.GzipFile(fileobj=raw) as stream:
    while line := stream.readline(64 * 1024 * 1024):
        if not line.endswith(b'\n') and len(line) == 64 * 1024 * 1024:
            raise ValueError('A dump line exceeds the bounded 64 MiB parser limit.')
        lines += 1
        uncompressed += len(line)
        tail = (tail + line[-1024:])[-2048:]
        create = re.match(rb'CREATE TABLE `([a-zA-Z0-9_]+)` \(', line)
        if create:
            current_ddl = create[1].decode('ascii')
            tables.setdefault(current_ddl, {'insertStatements': 0, 'insertBytes': 0})
            ddl_lines = [line]
        elif current_ddl:
            ddl_lines.append(line)
            if line.rstrip().endswith(b';'):
                ddl[current_ddl] = b''.join(ddl_lines).decode('utf-8')
                current_ddl = None
                ddl_lines = []
        insert = re.match(rb'INSERT INTO `([a-zA-Z0-9_]+)` ', line[:200])
        if insert:
            table = insert[1].decode('ascii')
            entry = tables.setdefault(table, {'insertStatements': 0, 'insertBytes': 0})
            entry['insertStatements'] += 1
            entry['insertBytes'] += len(line)
            if not line.rstrip().endswith(b';'):
                raise ValueError(f'INSERT for {table} is not a complete single dump line.')
        elif re.match(rb'(?:USE |CREATE DATABASE|DROP DATABASE|CREATE .*TRIGGER|DELIMITER|/\*![0-9]+ .*DEFINER)', line, re.I):
            special.append({'line': lines, 'prefix': line[:250].decode('utf-8', 'replace').strip()})
        now = time.monotonic()
        if now - last_progress >= 25:
            print(json.dumps({'phase': 'scan', 'compressedBytesRead': raw.tell(),
                              'compressedBytes': args.dump.stat().st_size,
                              'uncompressedBytes': uncompressed, 'tablesSeen': len(tables)}), flush=True)
            last_progress = now
compressed_digest = hashlib.sha256()
with args.dump.open('rb') as stream:
    while chunk := stream.read(4 * 1024 * 1024):
        compressed_digest.update(chunk)
assert b'Dump completed on' in tail, 'Dump completion marker missing.'
assert not current_ddl, 'An unfinished table definition was encountered.'
assert allowed <= set(ddl), f'Required tables missing: {allowed-set(ddl)}'
result = {'path': str(args.dump.resolve()), 'compressedBytes': args.dump.stat().st_size,
          'uncompressedBytes': uncompressed, 'sha256': compressed_digest.hexdigest(),
          'gzipIntegrityVerified': True, 'dumpCompleted': True, 'lines': lines,
          'tables': tables, 'bookTables': sorted(allowed), 'specialStatements': special,
          'elapsedSeconds': round(time.monotonic()-started, 2)}
(args.evidence / 'source-dump-inventory.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
(args.evidence / 'book-table-definitions.json').write_text(json.dumps({t: ddl[t] for t in sorted(allowed)}, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(result, ensure_ascii=False), flush=True)
