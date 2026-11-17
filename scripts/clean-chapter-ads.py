#!/usr/bin/env python3
"""章节与简介广告清理 CLI。Python 3.10+，仅标准库，默认预览。

file：离线清理 UTF-8 正文或简介；scan：显式目标 Docker Compose/MySQL 8；
restore：从章节 JSONL 备份回滚；scan-descriptions/restore-descriptions：简介扫描/回滚；
extract-response：离线提取误采接口包中的正文。
清理规则共用 ../novel-repair/repair/cleaning.py，分发时需保留该目录关系。
路径按本文件定位，不依赖调用时的当前目录；导入不会连接数据库或启动 WSL。
"""
from __future__ import annotations

import argparse
from collections import Counter
from dataclasses import dataclass, field
from datetime import datetime, timezone
import difflib
import hashlib
import html
from itertools import islice
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from typing import Any

BACKUP_SCHEMA = 'novel-plus-chapter-ads/v1'
TABLE_RE = re.compile(r'book_content[0-9]?\Z')
MAX_ID = 2**63 - 1

# CLEAN_ENGINE_BEGIN
# One shared stdlib ruleset, also bundled in the independent Web/Worker image.
_SHARED_PROJECT = Path(__file__).resolve().parents[1] / "novel-repair"
if str(_SHARED_PROJECT) not in sys.path:
    sys.path.insert(0, str(_SHARED_PROJECT))
from repair.cleaning import (CleanResult, clean_content, clean_description,
                             looks_like_response_metadata, extract_response_content,
                             _add_warning)
# CLEAN_ENGINE_END
def positive_id(value: Any) -> int:
    if isinstance(value, bool) or not re.fullmatch(r'[0-9]+', str(value)):
        raise ValueError('ID 必须是正整数')
    number = int(value)
    if not 0 < number <= MAX_ID:
        raise ValueError('ID 超出 MySQL BIGINT 正整数范围')
    return number


def table_name(value: str) -> str:
    if not isinstance(value, str) or not TABLE_RE.fullmatch(value):
        raise ValueError('仅允许 book_content 和 book_content0..9')
    return value


def sql_text(value: str) -> str:
    """十六进制传输正文，不依赖 SQL 反斜杠模式，不破坏制表/换行/NUL。"""
    if not isinstance(value, str):
        raise ValueError('正文必须是字符串')
    return "CONVERT(X'%s' USING utf8mb4)" % value.encode('utf-8').hex()


def digest(text: str) -> str:
    return hashlib.sha256(text.encode('utf-8')).hexdigest()


def build_update_sql(records: list[dict], restore: bool = False) -> str:
    """单事务、逐章字节级比较更新；过期原文不会覆盖并发写入。"""
    statements = ['START TRANSACTION;', 'SET @clean_total = 0;']
    seen = set()
    for record in records:
        table = table_name(record['table'])
        index_id = positive_id(record['index_id'])
        content_id = positive_id(record['content_id']) if 'content_id' in record else None
        identity = (table, index_id)
        if identity in seen:
            raise ValueError('同一批次有重复章节')
        seen.add(identity)
        before, after = record['before'], record['after']
        expected, replacement = (after, before) if restore else (before, after)
        constraint = f' AND id = {content_id}' if content_id is not None else ''
        statements.append(
            f'UPDATE `{table}` SET content = {sql_text(replacement)} '
            f'WHERE index_id = {index_id}{constraint} '
            f'AND BINARY content = BINARY {sql_text(expected)};')
        statements.extend([
            'SET @clean_rows = ROW_COUNT();',
            f"SELECT 'ROW', '{table}', {index_id}, @clean_rows;",
            'SET @clean_total = @clean_total + @clean_rows;',
        ])
    statements.extend(['COMMIT;', "SELECT 'TOTAL', @clean_total;"])
    return '\n'.join(statements)


def read_env(path: Path) -> dict[str, str]:
    values = {}
    if not path.is_file():
        raise ValueError(f'找不到环境文件：{path}')
    for line in path.read_text(encoding='utf-8-sig').splitlines():
        line = line.strip()
        if line.startswith('export '):
            line = line[7:]
        if not line or line.startswith('#') or '=' not in line:
            continue
        key, value = line.split('=', 1)
        value = value.strip()
        if value.startswith(('"', "'")):
            quote = value[0]
            closing = None
            escaped = False
            for position, character in enumerate(value[1:], 1):
                if character == quote and not escaped:
                    closing = position
                    break
                escaped = character == '\\' and not escaped if quote == '"' else False
            if closing is None or (value[closing + 1:].strip() and not value[closing + 1:].lstrip().startswith('#')):
                raise ValueError(f'.env 中 {key.strip()} 的引号未闭合')
            value = value[1:closing]
            if quote == '"':
                escapes = {'n': '\n', 'r': '\r', 't': '\t', '\\': '\\', '"': '"'}
                value = re.sub(r'\\([nrt\\"])', lambda match: escapes[match[1]], value)
        else:
            value = re.split(r'\s+#', value, maxsplit=1)[0].rstrip()
        values[key.strip()] = value
    return values


def wsl_path(path: Path) -> str:
    text = str(path)
    if re.match(r'^[A-Za-z]:[\\/]', text):
        return '/mnt/' + text[0].lower() + text[2:].replace('\\', '/')
    return text.replace('\\', '/')


class MysqlClient:
    """只在显式数据库子命令创建；密码通过 stdin，不放进进程参数。"""

    def __init__(self, args: argparse.Namespace):
        directory = Path(args.compose_dir).resolve()
        if not directory.is_dir():
            raise ValueError('--compose-dir 必须是本机可访问的部署目录；也可在 WSL 内运行脚本')
        env_file = Path(args.env_file).resolve() if args.env_file else directory / '.env'
        self.env = read_env(env_file)
        self.database = args.database or self.env.get('MYSQL_DATABASE', 'novel_plus')
        self.user = args.db_user or self.env.get('MYSQL_USERNAME') or self.env.get('MYSQL_USER', 'novel')
        if not re.fullmatch(r'[A-Za-z0-9_]+', self.database):
            raise ValueError('数据库名称仅允许字母、数字和下划线')
        if not re.fullmatch(r'[A-Za-z0-9_.-]+', args.mysql_service):
            raise ValueError('MySQL Compose 服务名不合法')
        self.password = (os.environ.get('NOVEL_CLEAN_MYSQL_PASSWORD') or
                         self.env.get('MYSQL_PASSWORD') or self.env.get('DB_PASSWORD'))
        if not self.password or any(c in self.password for c in '\r\n\x00'):
            raise ValueError('需要 MYSQL_PASSWORD / DB_PASSWORD 或 NOVEL_CLEAN_MYSQL_PASSWORD，口令不能含换行/NUL')
        if re.search(r'\$\{[^}]+\}', self.password):
            raise ValueError('口令的 .env 变量引用不支持；请设置 NOVEL_CLEAN_MYSQL_PASSWORD 为实际值')
        files = args.compose_file or ['compose.yaml']
        paths = [Path(p).resolve() if Path(p).is_absolute() else (directory / p).resolve() for p in files]
        if any(not p.is_file() for p in paths):
            raise ValueError('找不到指定 Compose 文件')
        convert = wsl_path if args.wsl_distro else str
        prefix = ['wsl.exe', '-d', args.wsl_distro, '--exec'] if args.wsl_distro else []
        self.command = prefix + ['docker', 'compose', '--project-directory', convert(directory),
                                 '--env-file', convert(env_file)]
        for path in paths:
            self.command.extend(['-f', convert(path)])
        # 固定 shell 源码，读一行口令后将剩余 stdin 原样交给 mysql。
        self.command.extend([
            'exec', '-T', args.mysql_service, 'sh', '-c',
            'IFS= read -r MYSQL_PWD || exit 90; export MYSQL_PWD; exec mysql "$@"',
            'clean-chapter-ads', '--default-character-set=utf8mb4', '--batch', '--raw',
            '--skip-column-names', '--binary-mode', '--user=' + self.user,
            '--database=' + self.database,
        ])
        self.timeout = args.timeout

    def rows(self, sql: str) -> list[list[str]]:
        try:
            # Bytes avoid Windows translating the password delimiter to CRLF.
            # The container shell reads one LF-delimited password before SQL.
            result = subprocess.run(self.command, input=(self.password + '\n' + sql + '\n').encode('utf-8'),
                                    capture_output=True,
                                    timeout=self.timeout)
        except subprocess.TimeoutExpired as exc:
            raise RuntimeError('MySQL 命令超时；如发生在写入阶段，请保留备份核对实际结果') from exc
        if result.returncode:
            message = result.stderr.decode('utf-8', errors='replace').replace(self.password, '***').strip()[-1000:]
            raise RuntimeError('MySQL 命令失败：' + message)
        return [line.split('\t') for line in result.stdout.decode('utf-8').split('\n') if line.strip()]

    def target(self) -> dict:
        rows = self.rows('SELECT @@server_uuid, DATABASE();')
        if len(rows) != 1 or len(rows[0]) != 2:
            raise RuntimeError('不能识别 MySQL 8 数据库目标')
        return {'server_uuid': rows[0][0], 'database': rows[0][1]}


def discover_tables(client: MysqlClient, mode: str, writing: bool = False) -> list[str]:
    rows = client.rows("SELECT TABLE_NAME, ENGINE FROM information_schema.TABLES "
                       "WHERE TABLE_SCHEMA = DATABASE() AND "
                       "(TABLE_NAME = 'book_content' OR TABLE_NAME REGEXP '^book_content[0-9]$') "
                       "ORDER BY TABLE_NAME;")
    engines = {table_name(name): engine.upper() for name, engine in rows}
    tables = sorted(engines)
    if mode == 'unsharded':
        tables = [t for t in tables if t == 'book_content']
    elif mode == 'sharded':
        tables = [t for t in tables if t != 'book_content']
    elif mode == 'auto':
        nonempty = [t for t in tables if client.rows(f'SELECT 1 FROM `{t}` LIMIT 1;')]
        if 'book_content' in nonempty and len(nonempty) > 1:
            raise ValueError('单表和分表同时有数据，请用 --tables unsharded/sharded/all 明确选择')
        tables = nonempty or tables
    if not tables:
        raise ValueError('目标中未找到指定正文表')
    if writing and any(engines[t] != 'INNODB' for t in tables):
        raise ValueError('写入仅支持 InnoDB 正文表，以保证事务可回滚')
    if writing:
        names = ','.join(sql_text(t) for t in tables)
        triggers = client.rows('SELECT TRIGGER_NAME FROM information_schema.TRIGGERS '
                               'WHERE TRIGGER_SCHEMA = DATABASE() AND EVENT_OBJECT_TABLE '
                               f'IN ({names});')
        if triggers:
            raise ValueError('正文表存在触发器，请先核验其副作用；本工具拒绝直接写入')
    return tables


def source_rules(client: MysqlClient) -> dict[int, dict]:
    result = {}
    for sid, name_hex, rule_hex in client.rows('SELECT id, HEX(source_name), HEX(crawl_rule) FROM crawl_source;'):
        rule = json.loads(bytes.fromhex(rule_hex).decode('utf-8')) if rule_hex != 'NULL' else {}
        result[positive_id(sid)] = {'name': bytes.fromhex(name_hex).decode('utf-8'), 'rule': rule}
    return result


def filters_from_rule(rule: Any) -> tuple[str, ...]:
    if isinstance(rule, list) and all(isinstance(item, str) for item in rule):
        return tuple(rule)
    if not isinstance(rule, dict) or not isinstance(rule.get('filterContent', ''), str):
        raise ValueError('规则 JSON 需要 filterContent 字符串或正则字符串数组')
    return tuple(line for line in rule.get('filterContent', '').replace('\r\n', '\n').split('\n') if line.strip())


def file_filters(args: argparse.Namespace) -> tuple[str, ...]:
    if args.filters_json and not args.use_filters:
        raise ValueError('--filters-json 需要同时指定 --use-filters')
    return filters_from_rule(json.loads(Path(args.filters_json).read_text(encoding='utf-8-sig'))) if args.filters_json else ()


def fetch_batch(client: MysqlClient, tables: list[str], args: argparse.Namespace,
                source_ids: list[int], after: tuple[int, str], count: int) -> list[dict]:
    queries = []
    for table in tables:
        operator = '>=' if table > after[1] else '>'
        conditions = [f'c.index_id {operator} {after[0]}', 'c.content IS NOT NULL']
        if args.max_id:
            conditions.append(f'c.index_id <= {args.max_id}')
        if source_ids:
            conditions.append('b.crawl_source_id IN (' + ','.join(map(str, source_ids)) + ')')
        if args.book:
            conditions.append('i.book_id IN (' + ','.join(map(str, args.book)) + ')')
        if args.index:
            conditions.append('c.index_id IN (' + ','.join(map(str, args.index)) + ')')
        if args.anchor:
            conditions.append('(' + ' OR '.join(f'LOCATE({sql_text(a)}, c.content) > 0' for a in args.anchor) + ')')
        queries.append(
            f"SELECT '{table}' AS table_name, c.id AS content_id, c.index_id AS index_id, "
            'i.book_id AS book_id, HEX(c.content) AS body_hex, '
            f"IFNULL(b.crawl_source_id, '') AS source_id FROM `{table}` c "
            'JOIN book_index i ON i.id = c.index_id JOIN book b ON b.id = i.book_id '
            'WHERE ' + ' AND '.join(conditions))
    sql = ' UNION ALL '.join(queries) + f' ORDER BY index_id, table_name LIMIT {count};'
    records = []
    for table, content_id, index_id, book_id, body_hex, source_id in client.rows(sql):
        records.append({'table': table_name(table), 'content_id': positive_id(content_id),
                        'index_id': positive_id(index_id), 'book_id': positive_id(book_id),
                        'before': bytes.fromhex(body_hex).decode('utf-8'),
                        'source_id': positive_id(source_id) if source_id else None})
    return records


def guard_result(result: CleanResult, max_ratio: float) -> list[str]:
    warnings = list(result.warnings)
    if result.after != result.before:
        if not re.sub(r'<[^>]*>|\s|&nbsp;', '', result.after):
            warnings.append('清理后正文为空，需人工核验')
        ratio = (len(result.before) - len(result.after)) / max(1, len(result.before))
        if ratio > max_ratio:
            warnings.append(f'删除比例 {ratio:.1%} 超过阈值 {max_ratio:.1%}')
    return list(dict.fromkeys(warnings))


def json_line(handle, record: dict) -> None:
    handle.write(json.dumps(record, ensure_ascii=False) + '\n')


def durable_flush(handle) -> None:
    handle.flush()
    os.fsync(handle.fileno())


def execute_updates(client: MysqlClient, records: list[dict], restore: bool = False) -> dict:
    rows = client.rows(build_update_sql(records, restore))
    changed, conflicts = [], []
    total = None
    for row in rows:
        if row[0] == 'ROW' and len(row) == 4:
            key = (table_name(row[1]), positive_id(row[2]))
            affected = int(row[3])
            if affected not in (0, 1):
                raise RuntimeError('章节 ID 不唯一；保留备份并核对已提交结果')
            (changed if affected else conflicts).append(key)
        elif row[0] == 'TOTAL' and len(row) == 2:
            total = int(row[1])
    if total != len(changed) or len(changed) + len(conflicts) != len(records):
        raise RuntimeError('事务结果不完整；保留备份并核对实际写入')
    return {'changed': changed, 'conflicts': conflicts}


def command_file(args: argparse.Namespace) -> int:
    if args.kind == 'description':
        if not args.book_name:
            raise ValueError('--kind description 需要 --book-name 完整书名')
        if args.use_filters or args.filters_json or args.keep_html:
            raise ValueError('简介模式不接受章节过滤器或 --keep-html；请使用简介保守规则')
    elif args.book_name or args.decode_escapes or args.strip_bare_footer:
        raise ValueError('--book-name、--decode-escapes 和 --strip-bare-footer 仅用于 --kind description')
    path = Path(args.input).resolve()
    with path.open('r', encoding='utf-8', newline='') as handle:
        before = handle.read()
    result = (clean_description(before, args.book_name, args.decode_escapes,
                                strip_bare_footer=args.strip_bare_footer) if args.kind == 'description'
              else clean_content(before, file_filters(args), args.use_filters, not args.keep_html))
    warnings = guard_result(result, args.max_remove_ratio)
    label = '简介' if args.kind == 'description' else '正文'
    print(f'离线{label}：{len(before)} → {len(result.after)} 字符；{len(result.changes)} 处变化')
    for warning in warnings:
        print('需复核：' + warning)
    if args.output:
        output = Path(args.output).resolve()
        if output == path:
            raise ValueError('输出必须是独立文件，不能覆盖输入原文')
        with output.open('x', encoding='utf-8', newline='') as handle:
            handle.write(result.after)
        print('清理结果已写入：' + str(output))
    else:
        diff = difflib.unified_diff(before.splitlines(), result.after.splitlines(),
                                    fromfile='原文', tofile='清理预览', lineterm='')
        for number, line in enumerate(diff):
            if number >= 80:
                print('…预览已截断，使用 --output 保存完整结果')
                break
            print(line[:1000])
    if args.report:
        with Path(args.report).open('x', encoding='utf-8', newline='\n') as handle:
            json_line(handle, {'kind': 'file', 'before': before, 'after': result.after,
                               'content_kind': args.kind, 'book_name': args.book_name,
                               'decode_escapes': args.decode_escapes,
                               'strip_bare_footer': args.strip_bare_footer, 'database_written': False,
                               'changes': result.changes, 'warnings': warnings})
    return 0


def command_extract_response(args: argparse.Namespace) -> int:
    """生成独立修复候选；不在此模式连接或写入数据库。"""
    path = Path(args.input).resolve()
    with path.open('r', encoding='utf-8', newline='') as handle:
        before = handle.read()
    result = extract_response_content(before, args.expected_book, args.expected_chapter)
    warnings = guard_result(result, 0.35)
    print(f'接口元数据提取：{len(before)} → {len(result.after)} 字符')
    for warning in warnings:
        print('需复核：' + warning)
    if args.report:
        with Path(args.report).open('x', encoding='utf-8', newline='\n') as handle:
            json_line(handle, {'kind': 'response-extraction', 'before': before, 'after': result.after,
                               'changes': result.changes, 'warnings': warnings,
                               'expected_book': args.expected_book, 'expected_chapter': args.expected_chapter,
                               'database_written': False})
    if not result.changes:
        print('没有提取可确认的正文，保留原文。')
        return 2
    if args.output:
        output = Path(args.output).resolve()
        if output == path:
            raise ValueError('输出必须是独立文件，不能覆盖输入原文')
        with output.open('x', encoding='utf-8', newline='') as handle:
            handle.write(result.after)
        print('正文候选已写入：' + str(output))
    else:
        print('正文候选：' + result.after[:2000])
    print('本模式仅生成本地候选，不写入数据库；损坏接口包需人工复核。')
    return 0


def command_scan(args: argparse.Namespace) -> int:
    scoped = args.source or args.source_id or args.book or args.index or args.min_id or args.max_id
    if args.apply and not (scoped or args.all):
        raise ValueError('写入需要来源/书籍/章节/ID范围，或显式 --all')
    if args.apply and not args.backup:
        raise ValueError('--apply 必须同时给 --backup，写入前保存完整原文')
    if args.backup and not args.apply:
        raise ValueError('预览请使用 --report；--backup 仅配合 --apply')
    if args.min_id > MAX_ID or args.min_id < 0 or args.max_id < 0 or args.max_id > MAX_ID:
        raise ValueError('ID范围不合法')
    if args.max_id and args.max_id <= args.min_id:
        raise ValueError('--max-id 必须大于 --min-id（下界不包含，上界包含）')
    custom_filters = file_filters(args)
    client = MysqlClient(args)
    target = client.target()
    tables = discover_tables(client, args.tables, args.apply)
    rules = source_rules(client) if args.source or (args.use_filters and not args.filters_json) else {}
    sources = set(args.source_id or [])
    for name in args.source or []:
        matches = [sid for sid, value in rules.items() if value['name'] == name]
        if not matches:
            raise ValueError('找不到采集源：' + name)
        sources.update(matches)
    print('目标：' + json.dumps(target, ensure_ascii=False) + '；表：' + ', '.join(tables))
    print('模式：' + ('写入（有完整备份与并发原文检查）' if args.apply else '预览（不写数据库）'))
    counts = Counter()
    after = (args.min_id, '\uffff')
    backup = report = None
    try:
        if args.backup:
            backup = Path(args.backup).open('x', encoding='utf-8', newline='\n')
            json_line(backup, {'kind': 'header', 'schema': BACKUP_SCHEMA, 'target': target,
                               'created_at': datetime.now(timezone.utc).isoformat(), 'tables': tables})
            durable_flush(backup)
        if args.report:
            report = Path(args.report).open('x', encoding='utf-8', newline='\n')
        while not args.limit or counts['scanned'] < args.limit:
            size = min(args.batch_size, args.limit - counts['scanned']) if args.limit else args.batch_size
            records = fetch_batch(client, tables, args, sorted(sources), after, size)
            if not records:
                break
            after = (records[-1]['index_id'], records[-1]['table'])
            counts['scanned'] += len(records)
            updates = []
            for record in records:
                selected_filters = custom_filters
                if args.use_filters and not args.filters_json:
                    rule = rules.get(record['source_id'], {}).get('rule', {})
                    selected_filters = filters_from_rule(rule)
                result = clean_content(record['before'], selected_filters, args.use_filters, not args.keep_html)
                warnings = guard_result(result, args.max_remove_ratio)
                if result.after == result.before and not warnings:
                    continue
                counts['candidates'] += 1
                record.update(kind='chapter', after=result.after, changes=result.changes,
                              warnings=warnings, before_sha256=digest(result.before), after_sha256=digest(result.after))
                if report:
                    json_line(report, record)
                if warnings:
                    counts['review'] += 1
                elif result.after != result.before:
                    counts['eligible'] += 1
                    updates.append(record)
                if counts['shown'] < args.samples:
                    counts['shown'] += 1
                    print(f"章节 {record['index_id']}：{len(result.before)} → {len(result.after)} 字符" +
                          ('；需复核：' + '；'.join(warnings) if warnings else ''))
                    for change in result.changes[:3]:
                        print('  删除：' + re.sub(r'\s+', ' ', change.get('removed', ''))[:160])
            if args.apply and updates:
                for record in updates:
                    json_line(backup, record)
                durable_flush(backup)  # 完整原文先持久化，再提交数据库事务。
                outcome = execute_updates(client, updates)
                counts['written'] += len(outcome['changed'])
                counts['conflicts'] += len(outcome['conflicts'])
                json_line(backup, {'kind': 'result', **outcome})
                durable_flush(backup)
            print(f"进度：扫描 {counts['scanned']}，可清理 {counts['eligible']}，需复核 {counts['review']}，写入 {counts['written']}，冲突 {counts['conflicts']}")
        if report:
            durable_flush(report)
    finally:
        if backup:
            backup.close()
        if report:
            report.close()
    print('完成：' + json.dumps(dict(counts), ensure_ascii=False))
    return 0


def read_backup_header(path: Path) -> dict:
    with path.open(encoding='utf-8') as handle:
        header = json.loads(handle.readline())
        if header.get('kind') != 'header' or header.get('schema') != BACKUP_SCHEMA:
            raise ValueError('不是本工具的完整备份文件')
        target = header.get('target')
        if not isinstance(target, dict) or set(target) != {'server_uuid', 'database'} or not all(isinstance(v, str) and v for v in target.values()):
            raise ValueError('备份缺少目标标识')
        return header


def iter_backup_records(path: Path):
    """只保留 ID 去重集合，整章文本按批读取，避免大备份占满内存。"""
    read_backup_header(path)
    seen = set()
    with path.open(encoding='utf-8') as handle:
        handle.readline()
        for number, line in enumerate(handle, 2):
            if not line.strip():
                continue
            record = json.loads(line)
            if record.get('kind') == 'result':
                continue
            if record.get('kind') != 'chapter':
                raise ValueError(f'备份第 {number} 行类型不合法')
            table_name(record['table'])
            record['content_id'] = positive_id(record['content_id'])
            record['index_id'] = positive_id(record['index_id'])
            key = (record['table'], record['index_id'])
            if key in seen:
                raise ValueError('备份中有重复章节')
            seen.add(key)
            for side in ('before', 'after'):
                if not isinstance(record[side], str):
                    raise ValueError(f'备份第 {number} 行正文类型不合法')
                if digest(record[side]) != record[side + '_sha256']:
                    raise ValueError(f'备份第 {number} 行原文/新文校验失败')
            if record['before'] == record['after']:
                raise ValueError('备份包含没有变化的章节')
            yield record


def load_backup(path: Path) -> tuple[dict, list[dict]]:
    """小备份的便利接口；CLI 回滚使用迭代器分批处理。"""
    return read_backup_header(path), list(iter_backup_records(path))


def command_restore(args: argparse.Namespace) -> int:
    path = Path(args.backup)
    header = read_backup_header(path)
    # 先完整校验文件，任何损坏均在第一条写入之前报错。
    backup_tables = set()
    record_count = 0
    for record in iter_backup_records(path):
        backup_tables.add(record['table'])
        record_count += 1
    client = MysqlClient(args)
    if client.target() != header['target']:
        raise ValueError('数据库标识与备份不符，拒绝跨库回滚')
    tables = discover_tables(client, 'all', args.apply)
    if any(table not in tables for table in backup_tables):
        raise ValueError('备份中的正文表在目标数据库不存在')
    print(f'备份已完整校验：{record_count} 章')
    counts = Counter()
    records = iter_backup_records(path)
    while True:
        chunk = list(islice(records, args.batch_size))
        if not chunk:
            break
        ready = []
        for table in sorted({r['table'] for r in chunk}):
            selected = [r for r in chunk if r['table'] == table]
            conditions = ' OR '.join(f"(id = {positive_id(r['content_id'])} AND index_id = {positive_id(r['index_id'])})" for r in selected)
            current = {(positive_id(cid), positive_id(iid)): bytes.fromhex(body).decode('utf-8')
                       for cid, iid, body in client.rows(f'SELECT id, index_id, HEX(content) FROM `{table}` WHERE {conditions};') if body != 'NULL'}
            for record in selected:
                body = current.get((record['content_id'], record['index_id']))
                if body == record['after']:
                    ready.append(record)
                elif body == record['before']:
                    counts['already_original'] += 1
                else:
                    counts['conflicts'] += 1
        counts['ready'] += len(ready)
        if args.apply and ready:
            outcome = execute_updates(client, ready, restore=True)
            counts['restored'] += len(outcome['changed'])
            counts['conflicts'] += len(outcome['conflicts'])
        print('回滚进度：' + json.dumps(dict(counts), ensure_ascii=False))
    print('回滚完成' if args.apply else '回滚预览完成，未写库；加 --apply 执行')
    return 0


DESCRIPTION_BACKUP_SCHEMA = 'novel-plus-book-descriptions/v1'
DESCRIPTION_SEO_TABLES = ('seo_site_config', 'seo_book_override', 'seo_book_state',
                          'seo_audit_log', 'seo_sitemap_control', 'seo_book_visibility')
DESCRIPTION_WRITE_TABLES = ('book', 'seo_site_config', 'seo_book_state',
                            'seo_book_visibility', 'seo_sitemap_control')


def description_time(value: Any) -> str | None:
    if value is None:
        return None
    if not isinstance(value, str) or not re.fullmatch(r'\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}(?:\.\d{1,6})?', value):
        raise ValueError('简介更新时间格式不合法')
    try:
        parsed = datetime.strptime(value, '%Y-%m-%d %H:%M:%S.%f' if '.' in value else '%Y-%m-%d %H:%M:%S')
    except ValueError as exc:
        raise ValueError('简介更新时间不合法') from exc
    return parsed.strftime('%Y-%m-%d %H:%M:%S.%f')


def validate_description_record(record: dict, require_hashes: bool = False) -> None:
    if not isinstance(record, dict):
        raise ValueError('简介记录类型不合法')
    record['book_id'] = positive_id(record['book_id'])
    if not isinstance(record['book_name'], str) or not record['book_name'].strip():
        raise ValueError('简介记录缺少完整书名')
    for key in ('author_name', 'source_book_id'):
        if record[key] is not None and not isinstance(record[key], str):
            raise ValueError('简介身份字段类型不合法：' + key)
    if record['source_id'] is not None:
        record['source_id'] = positive_id(record['source_id'])
    for side in ('before', 'after'):
        value = record[side]
        if not isinstance(value, str) or not value.strip() or len(value) > 2000:
            raise ValueError('简介原文/新文必须非空且不超过2000字符')
        calculated = digest(value)  # Also rejects invalid Unicode surrogates.
        if require_hashes and record.get(side + '_sha256') != calculated:
            raise ValueError('简介备份原文/新文SHA256校验失败')
    if record['before'] == record['after']:
        raise ValueError('简介记录没有变化')
    warnings = record.get('warnings', [])
    if not isinstance(warnings, list) or warnings:
        raise ValueError('有警告的简介必须人工复核，拒绝写入')
    record['before_update_time'] = description_time(record['before_update_time'])
    record['after_update_time'] = description_time(record['after_update_time'])
    if record['after_update_time'] is None:
        raise ValueError('简介记录缺少预先持久化的新更新时间')


def description_select_columns() -> str:
    return ("SELECT id, HEX(book_name), IFNULL(HEX(author_name),'NULL'), "
            "IFNULL(crawl_source_id,'NULL'), IFNULL(HEX(crawl_book_id),'NULL'), HEX(book_desc), "
            "IFNULL(DATE_FORMAT(update_time,'%Y-%m-%d %H:%i:%s.%f'),'NULL') FROM book ")


def description_rows(rows: list[list[str]]) -> list[dict]:
    records, seen = [], set()
    for row in rows:
        if len(row) != 7:
            raise RuntimeError('简介读取结果格式不完整')
        bid, name, author, source, source_book, body, updated = row
        bid = positive_id(bid)
        if bid in seen:
            raise ValueError('简介查询返回重复书籍ID')
        seen.add(bid)
        decode = lambda value: None if value == 'NULL' else bytes.fromhex(value).decode('utf-8')
        records.append({'book_id': bid, 'book_name': decode(name), 'author_name': decode(author),
                        'source_id': positive_id(source) if source != 'NULL' else None,
                        'source_book_id': decode(source_book), 'before': decode(body),
                        'before_update_time': description_time(updated if updated != 'NULL' else None)})
    return records


def fetch_description_batch(client: MysqlClient, args: argparse.Namespace,
                            source_ids: list[int], after: int, count: int) -> list[dict]:
    after = int(after)
    if after < 0 or after > MAX_ID or not 1 <= count <= 1000:
        raise ValueError('简介批次边界不合法')
    conditions = [f'id > {after}', 'book_desc IS NOT NULL']
    if args.max_id:
        conditions.append(f'id <= {positive_id(args.max_id)}')
    if source_ids:
        conditions.append('crawl_source_id IN (' + ','.join(str(positive_id(value)) for value in source_ids) + ')')
    if args.book:
        conditions.append('id IN (' + ','.join(str(positive_id(value)) for value in args.book) + ')')
    if args.anchor:
        if any(not isinstance(value, str) or not value for value in args.anchor):
            raise ValueError('简介anchor必须是非空字面量')
        conditions.append('(' + ' OR '.join(f'LOCATE({sql_text(value)},book_desc)>0' for value in args.anchor) + ')')
    return description_rows(client.rows(description_select_columns() + 'WHERE ' +
                                        ' AND '.join(conditions) + f' ORDER BY id LIMIT {count};'))


def read_current_descriptions(client: MysqlClient, book_ids: list[int]) -> dict[int, dict]:
    ids = sorted({positive_id(value) for value in book_ids})
    if not ids:
        return {}
    rows = client.rows(description_select_columns() + 'WHERE id IN (' +
                       ','.join(map(str, ids)) + ');')
    return {record['book_id']: record for record in description_rows(rows)}


def description_database_layout(client: MysqlClient, writing: bool = False) -> dict:
    names = ('book',) + DESCRIPTION_SEO_TABLES
    literals = ','.join(sql_text(name) for name in names)
    rows = client.rows('SELECT TABLE_NAME,ENGINE FROM information_schema.TABLES '
                       f'WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ({literals});')
    engines = {}
    for name, engine in rows:
        if name not in names or name in engines:
            raise ValueError('简介数据库表结构结果不合法')
        engines[name] = engine.upper()
    if 'book' not in engines:
        raise ValueError('目标数据库缺少book表')
    present = set(engines).intersection(DESCRIPTION_SEO_TABLES)
    seo_mode = 'enabled' if len(present) == len(DESCRIPTION_SEO_TABLES) else ('absent' if not present else 'partial')
    layout = {'seo_mode': seo_mode, 'engines': engines}
    if not writing:
        return layout
    if seo_mode == 'partial':
        raise ValueError('SEO表只有部分存在，拒绝简介写入')
    write_tables = DESCRIPTION_WRITE_TABLES if seo_mode == 'enabled' else ('book',)
    if any(engines[name] != 'INNODB' for name in write_tables):
        raise ValueError('所有简介事务受写表和锁表必须是InnoDB')
    table_values = ','.join(sql_text(name) for name in write_tables)
    triggers = client.rows('SELECT EVENT_OBJECT_TABLE,TRIGGER_NAME FROM information_schema.TRIGGERS '
                           f'WHERE TRIGGER_SCHEMA=DATABASE() AND EVENT_OBJECT_TABLE IN ({table_values});')
    if triggers:
        raise ValueError('简介受写表存在触发器，无法确认副作用，拒绝写入')
    required = {'book': {'id', 'book_name', 'author_name', 'book_desc', 'update_time',
                         'crawl_source_id', 'crawl_book_id'}}
    if seo_mode == 'enabled':
        required.update({
            'book': required['book'] | {'status', 'word_count', 'last_index_id'},
            'seo_site_config': {'id'},
            'seo_book_override': {'book_id', 'index_enabled'},
            'seo_book_state': {'book_id', 'revision', 'modified_at', 'job_id', 'status', 'attempts',
                               'next_attempt_at', 'lease_until', 'lease_token', 'last_error'},
            'seo_book_visibility': {'book_id', 'published'},
            'seo_sitemap_control': {'id', 'revision', 'visibility_revision', 'status',
                                    'next_attempt_at', 'lease_until', 'lease_token'},
        })
    column_values = ','.join(sql_text(name) for name in required)
    columns = client.rows('SELECT TABLE_NAME,COLUMN_NAME FROM information_schema.COLUMNS '
                          f'WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ({column_values});')
    actual = {name: set() for name in required}
    for name, column in columns:
        if name not in actual:
            raise ValueError('简介列结构结果不合法')
        actual[name].add(column)
    if any(not expected.issubset(actual[name]) for name, expected in required.items()):
        raise ValueError('简介或SEO列结构不完整，拒绝写入')
    maximum = client.rows("SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS "
                          "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='book' AND COLUMN_NAME='book_desc';")
    if len(maximum) != 1 or len(maximum[0]) != 1 or int(maximum[0][0]) < 2000:
        raise ValueError('book_desc容量不足或结构未知')
    primary = client.rows('SELECT TABLE_NAME,COLUMN_NAME,SEQ_IN_INDEX FROM information_schema.STATISTICS '
                          f"WHERE TABLE_SCHEMA=DATABASE() AND INDEX_NAME='PRIMARY' AND TABLE_NAME IN ({table_values}) "
                          'ORDER BY TABLE_NAME,SEQ_IN_INDEX;')
    keys = {name: [] for name in write_tables}
    for name, column, order in primary:
        if name not in keys or int(order) != len(keys[name]) + 1:
            raise ValueError('简介事务主键结构不合法')
        keys[name].append(column)
    if any(keys[name] != [('book_id' if name in ('seo_book_state', 'seo_book_visibility') else 'id')]
           for name in write_tables):
        raise ValueError('简介事务表主键不符合预期')
    if seo_mode == 'enabled':
        for name in ('seo_site_config', 'seo_sitemap_control'):
            if client.rows(f'SELECT id FROM {name} WHERE id=1;') != [['1']]:
                raise ValueError('SEO单例控制行缺失，拒绝写入')
    return layout


def description_identity_equal(current: dict, record: dict) -> bool:
    return all(current.get(key) == record.get(key) for key in
               ('book_id', 'book_name', 'author_name', 'source_id', 'source_book_id'))


def build_description_update_sql(records: list[dict], seo_enabled: bool = False) -> str:
    seen = set()
    for record in records:
        validate_description_record(record)
        if record['book_id'] in seen:
            raise ValueError('同一简介批次存在重复书籍')
        seen.add(record['book_id'])
    statements = ['START TRANSACTION;', 'SET @description_total=0;']
    if seo_enabled:
        statements.append("SELECT 'LOCK_SITE',id FROM seo_site_config WHERE id=1 FOR UPDATE;")
    if records:
        statements.append("SELECT 'LOCK_BOOK',id FROM book WHERE id IN (" +
                          ','.join(map(str, sorted(seen))) + ') ORDER BY id FOR UPDATE;')
    if seo_enabled:
        statements.append("SELECT 'LOCK_CORE',id FROM seo_sitemap_control WHERE id=1 FOR UPDATE;")
    literal = lambda value: 'NULL' if value is None else sql_text(value)
    for record in records:
        bid = record['book_id']
        expected_time = ('NULL' if record['before_update_time'] is None else
                         f"CAST({sql_text(record['before_update_time'])} AS DATETIME(6))")
        conditions = [f'id={bid}', f"BINARY book_desc=BINARY {sql_text(record['before'])}",
                      f'update_time <=> {expected_time}',
                      f"BINARY book_name=BINARY {sql_text(record['book_name'])}",
                      f"BINARY author_name <=> BINARY {literal(record['author_name'])}",
                      'crawl_source_id <=> ' + (str(record['source_id']) if record['source_id'] is not None else 'NULL'),
                      f"BINARY crawl_book_id <=> BINARY {literal(record['source_book_id'])}",
                      f"BINARY book_desc<>BINARY {sql_text(record['after'])}"]
        if seo_enabled:
            conditions += ['EXISTS(SELECT id FROM seo_site_config WHERE id=1)',
                           'EXISTS(SELECT id FROM seo_sitemap_control WHERE id=1)']
        statements.append(f"UPDATE book SET book_desc={sql_text(record['after'])},"
                          f"update_time=CAST({sql_text(record['after_update_time'])} AS DATETIME(6)) "
                          'WHERE ' + ' AND '.join(conditions) + ';')
        statements += ['SET @changed=ROW_COUNT();', f'SET @bid={bid};']
        if seo_enabled:
            statements += [
                'SET @previous_published=(SELECT published FROM seo_book_visibility WHERE book_id=@bid);',
                'SET @published=EXISTS(SELECT b.id FROM book b LEFT JOIN seo_book_override o ON o.book_id=b.id '
                'WHERE b.id=@bid AND b.status=1 AND b.word_count>0 AND b.last_index_id IS NOT NULL '
                'AND (o.index_enabled IS NULL OR o.index_enabled=1));',
                "INSERT INTO seo_book_state(book_id,job_id,status,modified_at) "
                "SELECT @bid,UUID(),'pending',CURRENT_TIMESTAMP(3) WHERE @changed=1 "
                'ON DUPLICATE KEY UPDATE revision=revision+1,modified_at=CURRENT_TIMESTAMP(3),job_id=VALUES(job_id),'
                "status='pending',attempts=0,next_attempt_at=NULL,lease_until=NULL,lease_token=NULL,last_error=NULL;",
                'INSERT INTO seo_book_visibility(book_id,published) SELECT @bid,@published WHERE @changed=1 '
                'ON DUPLICATE KEY UPDATE published=VALUES(published);',
                'UPDATE seo_sitemap_control SET revision=revision+1,'
                'visibility_revision=visibility_revision+IF(@previous_published IS NOT NULL '
                'AND @previous_published<>@published,1,0),'
                "status='pending',next_attempt_at=NULL,lease_until=NULL,lease_token=NULL WHERE id=1 AND @changed=1;",
            ]
        statements += ["SELECT 'CHANGED',@bid,@changed;", 'SET @description_total=@description_total+@changed;']
    statements += ['COMMIT;', "SELECT 'TOTAL',@description_total;"]
    return '\n'.join(statements)


def execute_description_updates(client: MysqlClient, records: list[dict], seo_enabled: bool = False) -> dict:
    rows = client.rows(build_description_update_sql(records, seo_enabled))
    expected = {record['book_id']: record for record in records}
    changed, conflicts, seen, total = [], [], set(), None
    for row in rows:
        if row[0] in ('LOCK_SITE', 'LOCK_BOOK', 'LOCK_CORE') and len(row) == 2:
            continue
        if row[0] == 'CHANGED' and len(row) == 3:
            bid, count = positive_id(row[1]), int(row[2])
            if bid not in expected or bid in seen or count not in (0, 1):
                raise RuntimeError('简介事务结果不合法，结果不确定，请保留备份并读取实际数据库')
            seen.add(bid)
            (changed if count else conflicts).append(bid)
        elif row[0] == 'TOTAL' and len(row) == 2 and total is None:
            total = int(row[1])
        else:
            raise RuntimeError('简介事务结果无法确认，请保留备份并读取实际数据库')
    if seen != set(expected) or total != len(changed):
        raise RuntimeError('简介事务结果不完整，拒绝自动回滚；请读取实际数据库')
    current = read_current_descriptions(client, changed)
    for bid in changed:
        record, actual = expected[bid], current.get(bid)
        if (actual is None or not description_identity_equal(actual, record)
                or actual['before'] != record['after']
                or actual['before_update_time'] != record['after_update_time']):
            raise RuntimeError('简介提交后读回不一致，结果不确定；请保留备份并读取实际数据库')
    return {'changed': [{'book_id': bid, 'after_update_time': current[bid]['before_update_time']} for bid in changed],
            'conflicts': conflicts, 'readback_verified': True}


def require_description_write_platform() -> None:
    if os.name == 'nt':
        raise ValueError('简介数据库写入须在Linux/WSL内运行整个脚本，以保证备份目录fsync；Windows可预览')


def durable_description_flush(handle) -> None:
    durable_flush(handle)
    descriptor = os.open(str(Path(handle.name).resolve().parent), os.O_RDONLY | getattr(os, 'O_DIRECTORY', 0))
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def private_description_backup(handle) -> None:
    os.fchmod(handle.fileno(), 0o600)


def description_server_time(client: MysqlClient) -> str:
    rows = client.rows("SELECT DATE_FORMAT(CURRENT_TIMESTAMP,'%Y-%m-%d %H:%i:%s');")
    if len(rows) != 1 or len(rows[0]) != 1:
        raise RuntimeError('无法确定简介写入前的服务端更新时间')
    return description_time(rows[0][0])


def description_header(target: dict, action: str, layout: dict) -> dict:
    return {'kind': 'header', 'schema': DESCRIPTION_BACKUP_SCHEMA, 'target': target,
            'action': action, 'created_at': datetime.now(timezone.utc).isoformat(),
            'seo_mode': layout['seo_mode'], 'fields_set': ['book_desc', 'update_time']}


def write_description_batch(client: MysqlClient, records: list[dict], backup,
                            batch_id: int, seo_enabled: bool) -> dict:
    planned_time = description_server_time(client)
    for record in records:
        record.update(kind='description', batch_id=batch_id, after_update_time=planned_time,
                      before_sha256=digest(record['before']), after_sha256=digest(record['after']))
        validate_description_record(record, True)
    # Validate the complete batch before adding any records or issuing any write.
    build_description_update_sql(records, seo_enabled)
    for record in records:
        json_line(backup, record)
    durable_description_flush(backup)
    try:
        outcome = execute_description_updates(client, records, seo_enabled)
    except Exception:
        json_line(backup, {'kind': 'result', 'batch_id': batch_id, 'completed': False,
                           'status': 'uncertain', 'instruction': '读取实际数据库后人工核对，禁止自动回滚'})
        durable_description_flush(backup)
        raise
    json_line(backup, {'kind': 'result', 'batch_id': batch_id, 'completed': True, **outcome})
    durable_description_flush(backup)
    return outcome


def description_scope(args: argparse.Namespace) -> set[int]:
    for name in ('min_id', 'max_id'):
        value = getattr(args, name)
        if not isinstance(value, int) or isinstance(value, bool) or not 0 <= value <= MAX_ID:
            raise ValueError('简介书籍ID范围不合法')
    if args.max_id and args.max_id <= args.min_id:
        raise ValueError('--max-id必须大于--min-id（下界不包含，上界包含）')
    books = {positive_id(value) for value in args.book or []}
    confirmed = {positive_id(value) for value in args.confirm_bare_footer or []}
    if not confirmed.issubset(books):
        raise ValueError('--confirm-bare-footer的每个ID必须同时出现在--book中')
    scoped = args.source or args.source_id or books or args.min_id or args.max_id
    if args.apply and not (scoped or args.all):
        raise ValueError('简介写入需要来源/书籍/书籍ID范围，或显式--all；anchor不能替代写入范围')
    if args.limit == 0 and not (scoped or args.all):
        raise ValueError('不限量简介扫描需要明确范围或--all')
    if args.apply:
        if not args.backup:
            raise ValueError('简介写入需要独立--backup完整备份')
        require_description_write_platform()
    elif args.backup:
        raise ValueError('简介预览请使用--report；--backup仅用于--apply')
    if args.backup and args.report and Path(args.backup).resolve() == Path(args.report).resolve():
        raise ValueError('简介备份与报告必须使用不同文件')
    for value in args.anchor or []:
        if not isinstance(value, str) or not value:
            raise ValueError('简介anchor必须为非空字面量')
    return confirmed


def command_scan_descriptions(args: argparse.Namespace) -> int:
    confirmed = description_scope(args)
    client = MysqlClient(args)
    target, layout = client.target(), description_database_layout(client, args.apply)
    sources = {positive_id(value) for value in args.source_id or []}
    rules = source_rules(client) if args.source else {}
    for name in args.source or []:
        matches = {sid for sid, rule in rules.items() if rule['name'] == name}
        if not matches:
            raise ValueError('找不到简介采集来源：' + name)
        sources.update(matches)
    print('简介目标：' + json.dumps(target, ensure_ascii=False) + '；SEO：' + layout['seo_mode'])
    print('模式：' + ('写入（完整备份、身份/原文/时间CAS）' if args.apply else '预览（不写数据库）'))
    print('派生缓存需按部署另行核验；本命令不调用Redis。' +
          (' SEO不存在，需另核简介元数据与缓存。' if layout['seo_mode'] == 'absent' else ''))
    counts, cursor, backup, report, batch_id = Counter(), args.min_id, None, None, 0
    try:
        if args.backup:
            backup = Path(args.backup).open('x', encoding='utf-8', newline='\n')
            private_description_backup(backup)
            json_line(backup, description_header(target, 'scan', layout))
            durable_description_flush(backup)
        if args.report:
            report = Path(args.report).open('x', encoding='utf-8', newline='\n')
        while not args.limit or counts['scanned'] < args.limit:
            size = min(args.batch_size, args.limit - counts['scanned']) if args.limit else args.batch_size
            records = fetch_description_batch(client, args, sorted(sources), cursor, size)
            if not records:
                break
            if any(record['book_id'] <= cursor for record in records) or records != sorted(records, key=lambda value: value['book_id']):
                raise RuntimeError('简介书籍分页顺序异常')
            cursor, counts['scanned'] = records[-1]['book_id'], counts['scanned'] + len(records)
            eligible = []
            for record in records:
                result = clean_description(record['before'], record['book_name'],
                                           decode_escapes=args.decode_escapes,
                                           strip_bare_footer=record['book_id'] in confirmed)
                warnings = guard_result(result, args.max_remove_ratio)
                if not result.after.strip() or len(result.after) > 2000:
                    _add_warning(warnings, '简介清理后为空或超过2000字符，拒绝写入')
                if not record['before'].strip() or len(record['before']) > 2000:
                    _add_warning(warnings, '简介原文为空或超过2000字符，需人工核验')
                if result.after == result.before and not warnings:
                    continue
                counts['candidates'] += 1
                record.update(kind='description', after=result.after, changes=result.changes, warnings=warnings,
                              decode_escapes=args.decode_escapes, strip_bare_footer=record['book_id'] in confirmed,
                              before_sha256=digest(result.before), after_sha256=digest(result.after))
                if report:
                    json_line(report, record)
                if warnings:
                    counts['review'] += 1
                elif result.after != result.before:
                    counts['eligible'] += 1
                    eligible.append(record)
                if counts['shown'] < args.samples:
                    counts['shown'] += 1
                    print(f"书籍 {record['book_id']}：{len(result.before)} → {len(result.after)} 字符" +
                          ('；需复核：' + '；'.join(warnings) if warnings else ''))
            if args.apply and eligible:
                batch_id += 1
                outcome = write_description_batch(client, eligible, backup, batch_id, layout['seo_mode'] == 'enabled')
                counts['written'] += len(outcome['changed'])
                counts['conflicts'] += len(outcome['conflicts'])
            print('简介进度：' + json.dumps(dict(counts), ensure_ascii=False))
        if report:
            durable_flush(report)
    finally:
        if backup:
            backup.close()
        if report:
            report.close()
    print('简介完成：' + json.dumps(dict(counts), ensure_ascii=False))
    return 0


def load_description_backup(path: Path) -> tuple[dict, list[dict]]:
    with path.open(encoding='utf-8') as handle:
        header = json.loads(handle.readline())
        if (header.get('kind') != 'header' or header.get('schema') != DESCRIPTION_BACKUP_SCHEMA
                or header.get('action') not in ('scan', 'restore')):
            raise ValueError('不是完整简介备份文件')
        target = header.get('target')
        if (not isinstance(target, dict) or set(target) != {'server_uuid', 'database'}
                or not all(isinstance(value, str) and value for value in target.values())):
            raise ValueError('简介备份缺少数据库目标标识')
        records, outcomes, seen = {}, {}, set()
        for line in handle:
            if not line.strip():
                continue
            value = json.loads(line)
            batch = positive_id(value['batch_id'])
            if value.get('kind') == 'description':
                validate_description_record(value, True)
                bid = value['book_id']
                if bid in seen or batch in outcomes:
                    raise ValueError('简介备份存在重复或顺序不合法的记录')
                seen.add(bid)
                records.setdefault(batch, []).append(value)
            elif value.get('kind') == 'result':
                if (batch not in records or batch in outcomes or value.get('completed') is not True
                        or value.get('readback_verified') is not True):
                    raise ValueError('简介写入结果不确定或不完整；请读取实际数据库，拒绝自动回滚')
                outcomes[batch] = value
            else:
                raise ValueError('简介备份记录类型不合法')
    if set(records) != set(outcomes):
        raise ValueError('简介备份缺少确认写入结果；请读取实际数据库，拒绝自动回滚')
    changed_records = []
    for batch, chunk in records.items():
        outcome, identities = outcomes[batch], {record['book_id']: record for record in chunk}
        changed, conflicts = outcome.get('changed'), outcome.get('conflicts')
        if not isinstance(changed, list) or not isinstance(conflicts, list):
            raise ValueError('简介备份写入结果格式不合法')
        confirmed = set()
        for value in changed:
            bid = positive_id(value['book_id'])
            if (bid not in identities or bid in confirmed
                    or description_time(value['after_update_time']) != identities[bid]['after_update_time']):
                raise ValueError('简介备份写入时间或书籍ID不一致')
            confirmed.add(bid)
            changed_records.append(identities[bid])
        for value in conflicts:
            bid = positive_id(value)
            if bid not in identities or bid in confirmed:
                raise ValueError('简介备份冲突记录不合法')
            confirmed.add(bid)
        if confirmed != set(identities):
            raise ValueError('简介备份未确认全部记录，拒绝自动回滚')
    return header, changed_records


def command_restore_descriptions(args: argparse.Namespace) -> int:
    path = Path(args.backup).resolve()
    header, records = load_description_backup(path)  # Entire file validated before client construction.
    if args.apply:
        if not args.restore_backup or Path(args.restore_backup).resolve() == path:
            raise ValueError('简介回滚写入需要独立--restore-backup，不能覆盖原备份')
        require_description_write_platform()
    elif args.restore_backup:
        raise ValueError('--restore-backup仅用于--apply')
    client = MysqlClient(args)
    if client.target() != header['target']:
        raise ValueError('简介数据库标识与备份不符，拒绝跨库回滚')
    layout = description_database_layout(client, args.apply)
    print(f'简介备份已完整验证：{len(records)} 本确认写入；本命令不清理Redis，派生缓存需另核。')
    counts, backup, batch_id = Counter(), None, 0
    try:
        if args.restore_backup:
            backup = Path(args.restore_backup).open('x', encoding='utf-8', newline='\n')
            private_description_backup(backup)
            inverse_header = description_header(header['target'], 'restore', layout)
            inverse_header['original_backup_sha256'] = hashlib.sha256(path.read_bytes()).hexdigest()
            json_line(backup, inverse_header)
            durable_description_flush(backup)
        for start in range(0, len(records), args.batch_size):
            chunk = records[start:start + args.batch_size]
            current = read_current_descriptions(client, [record['book_id'] for record in chunk])
            ready = []
            for record in chunk:
                actual = current.get(record['book_id'])
                if actual is None or not description_identity_equal(actual, record):
                    counts['conflicts'] += 1
                elif actual['before'] == record['before']:
                    counts['already_original'] += 1
                elif actual['before'] == record['after'] and actual['before_update_time'] == record['after_update_time']:
                    ready.append({**record, 'before': record['after'], 'after': record['before'],
                                  'before_update_time': record['after_update_time'], 'warnings': [],
                                  'changes': [{'kind': 'description_restore'}]})
                else:
                    counts['conflicts'] += 1
            counts['ready'] += len(ready)
            if args.apply and ready:
                batch_id += 1
                outcome = write_description_batch(client, ready, backup, batch_id, layout['seo_mode'] == 'enabled')
                counts['restored'] += len(outcome['changed'])
                counts['conflicts'] += len(outcome['conflicts'])
            print('简介回滚进度：' + json.dumps(dict(counts), ensure_ascii=False))
    finally:
        if backup:
            backup.close()
    print('简介回滚完成' if args.apply else '简介回滚预览完成，未写库')
    return 0


def add_clean_options(parser: argparse.ArgumentParser) -> None:
    parser.add_argument('--filters-json', help='含 filterContent 的 JSON 或正则字符串数组')
    parser.add_argument('--use-filters', action='store_true', help='明确开启任意规则正则；scan未给JSON时读取来源规则')
    parser.add_argument('--keep-html', action='store_true', help='保留 script/style 与孤立容器标签，仅处理广告文本')
    parser.add_argument('--max-remove-ratio', type=float, default=0.35, help='超过删除比例即报告并跳过写库，默认0.35')
    parser.add_argument('--report', help='独立JSONL报告路径，已存在时拒绝覆盖，报告包含完整正文')


def add_database_options(parser: argparse.ArgumentParser) -> None:
    parser.add_argument('--compose-dir', required=True, help='明确指定部署目录，不自动猜测')
    parser.add_argument('--compose-file', action='append', help='可重复指定Compose文件，默认compose.yaml')
    parser.add_argument('--env-file', help='默认部署目录的.env；不向命令行传递口令')
    parser.add_argument('--mysql-service', default='mysql')
    parser.add_argument('--database', help='默认MYSQL_DATABASE或novel_plus')
    parser.add_argument('--db-user', help='默认MYSQL_USERNAME/MYSQL_USER或novel')
    parser.add_argument('--wsl-distro', help='Windows上显式通过指定WSL发行版调用Docker，不自动重投')
    parser.add_argument('--timeout', type=int, default=120)
    parser.add_argument('--batch-size', type=int, default=100)
    parser.add_argument('--apply', action='store_true', help='真正写入；默认预览')


def make_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest='command', required=True)
    file = commands.add_parser('file', help='离线处理UTF-8正文或简介，默认展示差异')
    file.add_argument('--input', required=True)
    file.add_argument('--output', help='独立输出文件，不能覆盖原文或已有文件')
    file.add_argument('--kind', choices=['chapter', 'description'], default='chapter')
    file.add_argument('--book-name', help='简介模式必填：完整书名，只识别独立尾行中的书名签名')
    file.add_argument('--decode-escapes', action='store_true',
                      help='仅简介模式：确认原文使用JSON转义时解码一层；普通Windows路径不要开启')
    file.add_argument('--strip-bare-footer', action='store_true',
                      help='仅简介模式：已确认源站残留时，去掉独立末行的裸 --；默认保留')
    add_clean_options(file)
    file.set_defaults(func=command_file)
    extract = commands.add_parser('extract-response', help='离线提取误采接口JSON的正文，损坏包仅生成复核候选')
    extract.add_argument('--input', required=True)
    extract.add_argument('--output', help='独立正文候选文件，不覆盖输入或已有文件')
    extract.add_argument('--expected-book', required=True, help='核对响应中的书名')
    extract.add_argument('--expected-chapter', required=True, help='核对响应中的章节名')
    extract.add_argument('--report', help='独立完整JSONL提取报告')
    extract.set_defaults(func=command_extract_response)
    scan = commands.add_parser('scan', help='扫描/清理数据库章节，默认只预览100章')
    add_database_options(scan)
    add_clean_options(scan)
    scan.add_argument('--source', action='append', help='来源名称，可重复')
    scan.add_argument('--source-id', action='append', type=positive_id, help='来源ID，可重复，与来源名称取并集')
    scan.add_argument('--book', action='append', type=positive_id, help='书籍ID，可重复，与来源条件取交集')
    scan.add_argument('--index', action='append', type=positive_id, help='章节ID，可重复')
    scan.add_argument('--min-id', type=int, default=0, help='章节ID下界，不包含')
    scan.add_argument('--max-id', type=int, default=0, help='章节ID上界，包含，0为不限')
    scan.add_argument('--anchor', action='append', help='正文包含字面量；可重复，使用LOCATE避免LIKE通配扩大')
    scan.add_argument('--tables', choices=['auto', 'unsharded', 'sharded', 'all'], default='auto')
    scan.add_argument('--limit', type=int, default=100, help='全局最多读取章节数，0=不限，默认100')
    scan.add_argument('--all', action='store_true', help='明确允许跨所有书籍，仍受limit和其它条件限制')
    scan.add_argument('--samples', type=int, default=8, help='输出样本数')
    scan.add_argument('--backup', help='写入前完整原文备份，独占创建，不覆盖已有文件')
    scan.set_defaults(func=command_scan)
    restore = commands.add_parser('restore', help='从整章备份回滚，默认预览，仅当前等于清理后正文才恢复')
    add_database_options(restore)
    restore.add_argument('--backup', required=True)
    restore.set_defaults(func=command_restore)
    descriptions = commands.add_parser('scan-descriptions', help='扫描书籍简介，默认只预览100本；不操作章节')
    add_database_options(descriptions)
    descriptions.add_argument('--source', action='append', help='精确来源名称，可重复')
    descriptions.add_argument('--source-id', action='append', type=positive_id, help='来源ID，与名称选择取并集')
    descriptions.add_argument('--book', action='append', type=positive_id, help='书籍ID，与来源/范围取交集')
    descriptions.add_argument('--min-id', type=int, default=0, help='书籍ID下界，不包含')
    descriptions.add_argument('--max-id', type=int, default=0, help='书籍ID上界，包含，0为不限')
    descriptions.add_argument('--anchor', action='append', help='简介字面量筛选，可重复；不是写入范围授权')
    descriptions.add_argument('--limit', type=int, default=100)
    descriptions.add_argument('--all', action='store_true', help='明确允许全库简介范围')
    descriptions.add_argument('--samples', type=int, default=8)
    descriptions.add_argument('--decode-escapes', action='store_true', help='已确认JSON转义污染才解码一层')
    descriptions.add_argument('--max-remove-ratio', type=float, default=0.35,
                              help='超过删除比例即报告并跳过写库，默认0.35；需复核后明确调整')
    descriptions.add_argument('--confirm-bare-footer', action='append', type=positive_id,
                              help='逐本确认孤立尾行--是污染；每个ID必须同时出现在--book')
    descriptions.add_argument('--backup', help='apply必填：独立完整JSONL备份，不覆盖已有文件')
    descriptions.add_argument('--report', help='独立JSONL预览报告，不覆盖已有文件')
    descriptions.set_defaults(func=command_scan_descriptions)
    restore_descriptions = commands.add_parser('restore-descriptions', help='只回滚备份中确认写入的简介；默认预览')
    add_database_options(restore_descriptions)
    restore_descriptions.add_argument('--backup', required=True, help='完整简介备份，不接受章节备份')
    restore_descriptions.add_argument('--restore-backup', help='apply必填：独立反向备份，不覆盖原备份或已有文件')
    restore_descriptions.set_defaults(func=command_restore_descriptions)
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = make_parser()
    args = parser.parse_args(argv)
    if hasattr(args, 'max_remove_ratio') and not 0 < args.max_remove_ratio <= 1:
        parser.error('--max-remove-ratio 必须在 (0,1] 之间')
    for name in ('limit', 'samples'):
        if hasattr(args, name) and getattr(args, name) < 0:
            parser.error('--' + name + ' 不能为负数')
    if hasattr(args, 'batch_size') and not 1 <= args.batch_size <= 1000:
        parser.error('--batch-size 必须在1..1000之间')
    if hasattr(args, 'timeout') and args.timeout < 1:
        parser.error('--timeout 必须为正数')
    try:
        return args.func(args)
    except (OSError, ValueError, RuntimeError, UnicodeError, KeyError) as exc:
        print('失败：' + str(exc), file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
