"""Offline tests for scoped book-description previews, guarded writes and recovery.

Only synthetic in-memory records are used. No Docker, MySQL or network is started.
"""
import contextlib
import copy
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import sqlite3
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch


SCRIPT = Path(__file__).resolve().parents[2] / 'scripts/clean-chapter-ads.py'
spec = importlib.util.spec_from_file_location('book_description_tool_tests', SCRIPT)
cleaner = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = cleaner
with contextlib.ExitStack() as stack:
    for target in ('subprocess.run', 'subprocess.Popen', 'os.system', 'socket.create_connection'):
        stack.enter_context(patch(target, side_effect=AssertionError('import side effect: ' + target)))
    spec.loader.exec_module(cleaner)


TARGET = {'server_uuid': 'unit-test-server', 'database': 'novel_plus'}
OLD_TIME = '2026-10-08 09:31:00.000000'
NEW_TIME = '2026-10-09 10:00:00.000000'


def book(bid=123, **updates):
    value = {'book_id': bid, 'book_name': '测试《书》', 'author_name': '作者', 'source_id': 12,
             'source_book_id': '013752', 'before': r'【百合\u002F反派】\n正常简介。',
             'before_update_time': OLD_TIME}
    value.update(updates)
    return value


def proposal(bid=123, **updates):
    value = {**book(bid), 'after': '【百合/反派】\n正常简介。', 'after_update_time': NEW_TIME,
             'warnings': [], 'changes': [], 'kind': 'description', 'batch_id': 1}
    value.update(updates)
    value['before_sha256'] = cleaner.digest(value['before'])
    value['after_sha256'] = cleaner.digest(value['after'])
    return value


def raw_row(record):
    encode = lambda value: 'NULL' if value is None else str(value).encode('utf-8').hex()
    return [str(record['book_id']), encode(record['book_name']), encode(record['author_name']),
            str(record['source_id']) if record['source_id'] is not None else 'NULL',
            encode(record['source_book_id']), encode(record['before']),
            record['before_update_time'] if record['before_update_time'] is not None else 'NULL']


class FakeClient:
    """A result/transport fixture, not an implementation of MySQL CAS semantics."""
    def __init__(self, books=None):
        self.books = {value['book_id']: copy.deepcopy(value) for value in books or [book()]}
        self.queries, self.mutations, self.before_execute = [], [], None
        self.counts, self.bad_result, self.bad_readback = {}, False, False
        self.identity = dict(TARGET)

    def target(self):
        return dict(self.identity)

    def rows(self, sql):
        self.queries.append(sql)
        if sql.startswith('START TRANSACTION;'):
            self.mutations.append(sql)
            if self.before_execute:
                self.before_execute(sql)
            expression = (r"UPDATE book SET book_desc=CONVERT\(X'([0-9A-Fa-f]+)' USING utf8mb4\),"
                          r"update_time=CAST\(CONVERT\(X'([0-9A-Fa-f]+)' USING utf8mb4\) AS DATETIME\(6\)\) WHERE id=(\d+)")
            changes, total = [], 0
            for match in re.finditer(expression, sql):
                after, timestamp, bid = bytes.fromhex(match[1]).decode(), bytes.fromhex(match[2]).decode(), int(match[3])
                count = self.counts.get(bid, 1)
                if count == 1 and bid in self.books:
                    self.books[bid].update(before=after, before_update_time=timestamp)
                changes.append(['CHANGED', str(bid), str(count)])
                total += count
            return changes if self.bad_result else changes + [['TOTAL', str(total)]]
        if sql.startswith('SELECT DATE_FORMAT(CURRENT_TIMESTAMP'):
            return [['2026-10-09 10:00:00']]
        if sql.startswith('SELECT id, HEX(book_name)'):
            selected = sorted(self.books)
            cursor = re.search(r'WHERE id > (\d+)', sql)
            ids = re.search(r'WHERE id IN \(([\d,]+)\)', sql)
            if cursor:
                selected = [bid for bid in selected if bid > int(cursor[1])]
            elif ids:
                selected = [bid for bid in selected if bid in set(map(int, ids[1].split(',')))]
            else:
                raise AssertionError('unexpected description SELECT: ' + sql)
            rows = [raw_row(self.books[bid]) for bid in selected]
            if self.bad_readback and ids and rows:
                rows[0][-1] = '2026-10-09 11:00:00.000000'
            return rows
        raise AssertionError('unexpected SQL: ' + sql)


class SqlTests(unittest.TestCase):
    def test_cas_protects_bytes_time_and_all_identity_without_touching_chapters(self):
        record = proposal(author_name="作者' OR 1=1;--\t", source_book_id="013752'\\x")
        sql = cleaner.build_description_update_sql([record])
        self.assertTrue(sql.startswith('START TRANSACTION;'))
        self.assertIn('COMMIT;', sql)
        self.assertIn('BINARY book_desc=BINARY ' + cleaner.sql_text(record['before']), sql)
        self.assertIn('update_time <=> CAST(' + cleaner.sql_text(OLD_TIME), sql)
        for field, value in (('book_name', record['book_name']), ('author_name', record['author_name']),
                             ('crawl_book_id', record['source_book_id'])):
            self.assertIn('BINARY ' + field, sql)
            self.assertIn(cleaner.sql_text(value), sql)
        self.assertIn('crawl_source_id <=> 12', sql)
        self.assertNotIn(record['author_name'], sql)
        self.assertNotIn('book_content', sql)
        self.assertNotIn('book_index', sql)
        self.assertNotIn('last_index_update_time=', sql)
        self.assertNotIn('update_time=CURRENT_TIMESTAMP', sql)
        self.assertIn(cleaner.sql_text(NEW_TIME), sql)

    def test_seo_sql_has_site_then_book_lock_and_every_dirty_write_is_conditional(self):
        sql = cleaner.build_description_update_sql([proposal(124), proposal(123)], True)
        self.assertLess(sql.index('FROM seo_site_config WHERE id=1 FOR UPDATE'),
                        sql.index('FROM book WHERE id IN (123,124) ORDER BY id FOR UPDATE'))
        self.assertEqual(sql.count('WHERE @changed=1'), 4)  # Two state and visibility inserts per book.
        self.assertEqual(sql.count('WHERE id=1 AND @changed=1'), 2)
        self.assertIn('EXISTS(SELECT id FROM seo_site_config WHERE id=1)', sql)
        self.assertIn('EXISTS(SELECT id FROM seo_sitemap_control WHERE id=1)', sql)
        for forbidden in ('observed_hash=', 'built_files=', 'built_revision=', 'DELETE ', 'seo_audit_log'):
            self.assertNotIn(forbidden, sql)
        self.assertIn('visibility_revision=visibility_revision+IF(@previous_published IS NOT NULL', sql)

    def test_invalid_or_ambiguous_records_are_rejected_before_sql(self):
        cases = [proposal(after=''), proposal(after='长' * 2001), proposal(warnings=['需复核']),
                 proposal(book_id=True), proposal(source_id="12 OR 1=1"),
                 proposal(before_update_time="2026-10-09';DROP TABLE book;--"),
                 proposal(after_update_time=None), proposal(author_name=123), proposal(source_book_id=[])]
        for record in cases:
            with self.subTest(record=repr(record)[:120]), self.assertRaises((ValueError, TypeError)):
                cleaner.build_description_update_sql([record])
        with self.assertRaises(ValueError):
            cleaner.build_description_update_sql([proposal(), proposal()])

    def test_conflict_and_post_commit_verification_are_distinct(self):
        client = FakeClient([book(123), book(124)])
        client.counts[124] = 0
        outcome = cleaner.execute_description_updates(client, [proposal(123), proposal(124)])
        self.assertEqual(outcome['changed'], [{'book_id': 123, 'after_update_time': NEW_TIME}])
        self.assertEqual(outcome['conflicts'], [124])
        self.assertEqual(client.books[124]['before'], book(124)['before'])
        self.assertTrue(outcome['readback_verified'])
        for attribute in ('bad_result', 'bad_readback'):
            with self.subTest(attribute=attribute):
                client = FakeClient()
                setattr(client, attribute, True)
                with self.assertRaisesRegex(RuntimeError, '不完整|不一致'):
                    cleaner.execute_description_updates(client, [proposal()])


class IndependentQueryTests(unittest.TestCase):
    def setUp(self):
        self.connection = sqlite3.connect(':memory:')
        self.addCleanup(self.connection.close)
        self.connection.execute('CREATE TABLE book(id INTEGER PRIMARY KEY,book_name TEXT,author_name TEXT,'
                                'crawl_source_id INTEGER,crawl_book_id TEXT,book_desc TEXT,update_time TEXT)')
        for bid, source in ((1, 12), (2, 12), (3, 4), (4, 12), (5, 12)):
            self.connection.execute('INSERT INTO book VALUES(?,?,?,?,?,?,?)',
                                    (bid, '书' + str(bid), '作者', source, str(bid), '简介\x00\x01\t\r\n百分比100%_尾部', OLD_TIME))
        self.connection.create_function('HEX', 1, lambda value: value.encode().hex() if value is not None else None)
        self.connection.create_function('DATE_FORMAT', 2, lambda value, _pattern: value)
        self.connection.create_function('LOCATE', 2, lambda needle, body: body.find(needle) + 1)
        self.sql = []

    def rows(self, sql):
        self.sql.append(sql)
        sql = re.sub(r"CONVERT\((X'[0-9a-fA-F]*') USING utf8mb4\)", r'CAST(\1 AS TEXT)', sql)
        return [[str(value) for value in row] for row in self.connection.execute(sql)]

    def test_book_source_range_anchor_intersection_and_keyset_progress(self):
        args = cleaner.make_parser().parse_args(['scan-descriptions', '--compose-dir', 'unused',
                                                 '--book', '2', '--book', '3', '--book', '4', '--max-id', '4',
                                                 '--anchor', '100%_'])
        first = cleaner.fetch_description_batch(self, args, [12], 1, 1)
        second = cleaner.fetch_description_batch(self, args, [12], first[-1]['book_id'], 1)
        self.assertEqual([record['book_id'] for record in first + second], [2, 4])
        self.assertEqual(first[0]['before'], '简介\x00\x01\t\r\n百分比100%_尾部')
        self.assertFalse(cleaner.fetch_description_batch(self, args, [12], 4, 1))
        self.assertTrue(all('LOCATE(' in sql and ' LIKE ' not in sql for sql in self.sql))

    def test_anchor_is_a_literal_not_sql_or_like_syntax(self):
        args = cleaner.make_parser().parse_args(['scan-descriptions', '--compose-dir', 'unused',
                                                 '--anchor', "' OR 1=1;--%_"])
        self.assertEqual(cleaner.fetch_description_batch(self, args, [], 0, 100), [])
        self.assertNotIn("' OR 1=1;--%_", self.sql[-1])


class LayoutTests(unittest.TestCase):
    COLUMNS = {
        'book': 'id book_name author_name book_desc update_time crawl_source_id crawl_book_id status word_count last_index_id',
        'seo_site_config': 'id', 'seo_book_override': 'book_id index_enabled',
        'seo_book_state': 'book_id revision modified_at job_id status attempts next_attempt_at lease_until lease_token last_error observed_hash',
        'seo_book_visibility': 'book_id published',
        'seo_sitemap_control': 'id revision visibility_revision status next_attempt_at lease_until lease_token',
    }

    def schema_client(self, mode='enabled', bad_engine=None, triggers=None, singleton_missing=False, bad_key=False):
        queries = []
        tables = ['book'] + (list(cleaner.DESCRIPTION_SEO_TABLES) if mode == 'enabled' else
                            (['seo_book_state'] if mode == 'partial' else []))
        def rows(sql):
            queries.append(sql)
            names = [bytes.fromhex(value).decode() for value in re.findall(r"X'([0-9a-fA-F]+)'", sql)]
            if 'information_schema.TABLES' in sql:
                return [[name, 'MyISAM' if name == bad_engine else 'InnoDB'] for name in tables]
            if 'information_schema.TRIGGERS' in sql:
                return triggers or []
            if 'CHARACTER_MAXIMUM_LENGTH' in sql:
                return [['2000']]
            if 'information_schema.COLUMNS' in sql:
                return [[name, column] for name in names for column in self.COLUMNS[name].split()]
            if 'information_schema.STATISTICS' in sql:
                return [[name, ('wrong' if bad_key and name == 'book' else
                                'book_id' if name in ('seo_book_state', 'seo_book_visibility') else 'id'), '1']
                        for name in sorted(names)]
            if sql.startswith('SELECT id FROM seo_'):
                return [] if singleton_missing else [['1']]
            raise AssertionError(sql)
        client = Mock()
        client.rows.side_effect = rows
        client.queries = queries
        return client

    def test_all_or_none_seo_and_readonly_partial_preview(self):
        for mode in ('enabled', 'absent', 'partial'):
            with self.subTest(mode=mode):
                client = self.schema_client(mode)
                self.assertEqual(cleaner.description_database_layout(client)['seo_mode'], mode)
                self.assertEqual(len(client.queries), 1)
        with self.assertRaisesRegex(ValueError, '部分'):
            cleaner.description_database_layout(self.schema_client('partial'), True)
        self.assertEqual(cleaner.description_database_layout(self.schema_client('absent'), True)['seo_mode'], 'absent')
        self.assertEqual(cleaner.description_database_layout(self.schema_client(), True)['seo_mode'], 'enabled')

    def test_each_written_table_rejects_nontransactional_engine_and_triggers(self):
        for table in cleaner.DESCRIPTION_WRITE_TABLES:
            with self.subTest(table=table), self.assertRaisesRegex(ValueError, 'InnoDB'):
                cleaner.description_database_layout(self.schema_client(bad_engine=table), True)
            with self.subTest(trigger_table=table), self.assertRaisesRegex(ValueError, '触发器'):
                cleaner.description_database_layout(self.schema_client(triggers=[[table, 'unsafe_trigger']]), True)
        for kwargs in ({'singleton_missing': True}, {'bad_key': True}):
            with self.subTest(kwargs=kwargs), self.assertRaises(ValueError):
                cleaner.description_database_layout(self.schema_client(**kwargs), True)


class WorkflowTests(unittest.TestCase):
    def run_cli(self, client, argv, layout='absent', flush=None, allow_write=True):
        with contextlib.ExitStack() as stack:
            constructor = stack.enter_context(patch.object(cleaner, 'MysqlClient', return_value=client))
            stack.enter_context(patch.object(cleaner, 'description_database_layout', return_value={'seo_mode': layout}))
            if allow_write:
                stack.enter_context(patch.object(cleaner, 'require_description_write_platform'))
                stack.enter_context(patch.object(cleaner, 'private_description_backup'))
            if flush:
                stack.enter_context(patch.object(cleaner, 'durable_description_flush', side_effect=flush))
            output, errors = io.StringIO(), io.StringIO()
            stack.enter_context(contextlib.redirect_stdout(output))
            stack.enter_context(contextlib.redirect_stderr(errors))
            code = cleaner.main(argv)
            return code, output.getvalue(), errors.getvalue(), constructor

    def scan(self, *argv):
        return ['scan-descriptions', '--compose-dir', 'unused', *map(str, argv)]

    def test_default_is_readonly_limited_and_bare_footer_is_per_book_opt_in(self):
        args = cleaner.make_parser().parse_args(self.scan())
        self.assertEqual(args.limit, 100)
        self.assertEqual(args.max_remove_ratio, 0.35)
        self.assertFalse(args.apply)
        self.assertFalse(hasattr(args, 'strip_bare_footer'))
        client = FakeClient([book(before='正常简介。\r\n--')])
        with patch.object(cleaner, 'clean_description', wraps=cleaner.clean_description) as clean:
            code, output, errors, _ = self.run_cli(client, self.scan('--book', 123))
            self.assertEqual(code, 0, errors)
            self.assertFalse(clean.call_args.kwargs['strip_bare_footer'])
            self.assertFalse(client.mutations)
            self.assertIn('预览', output)
            code, _out, errors, _ = self.run_cli(client, self.scan('--book', 123, '--confirm-bare-footer', 123))
            self.assertEqual(code, 0, errors)
            self.assertTrue(clean.call_args.kwargs['strip_bare_footer'])

    def test_scope_and_confirmation_errors_happen_before_client_construction(self):
        with tempfile.TemporaryDirectory() as directory:
            backup = Path(directory) / 'backup.jsonl'
            for options in (('--apply', '--backup', backup), ('--limit', 0),
                            ('--source-id', 12, '--confirm-bare-footer', 123),
                            ('--book', 123, '--confirm-bare-footer', 124),
                            ('--book', 123, '--apply'), ('--min-id', -1),
                            ('--min-id', 10, '--max-id', 10), ('--anchor', ''),
                            ('--anchor', '正文', '--apply', '--backup', backup)):
                with self.subTest(options=options):
                    code, _output, _errors, constructor = self.run_cli(FakeClient(), self.scan(*options))
                    self.assertEqual(code, 1)
                    constructor.assert_not_called()
                    self.assertFalse(backup.exists())

    def test_native_windows_database_apply_is_rejected_before_client(self):
        if os.name != 'nt':
            self.skipTest('native Windows platform guard')
        code, _output, errors, constructor = self.run_cli(FakeClient(), self.scan('--book', 123, '--apply',
                                                                                 '--backup', 'unused.jsonl'),
                                                       allow_write=False)
        self.assertEqual(code, 1)
        self.assertIn('Linux/WSL', errors)
        constructor.assert_not_called()

    def test_source_name_and_source_ids_union_then_intersect_books(self):
        client = FakeClient()
        rules = {4: {'name': '来源甲'}, 12: {'name': '来源乙'}}
        with patch.object(cleaner, 'source_rules', return_value=rules), \
                patch.object(cleaner, 'fetch_description_batch', return_value=[]) as fetch:
            code, _output, errors, _ = self.run_cli(client, self.scan('--source', '来源甲', '--source-id', 12,
                                                                   '--book', 123, '--book', 124))
            self.assertEqual(code, 0, errors)
            self.assertEqual(fetch.call_args.args[2], [4, 12])
            self.assertEqual(fetch.call_args.args[1].book, [123, 124])

    def test_entire_backup_and_planned_time_are_durable_before_cas(self):
        with tempfile.TemporaryDirectory() as directory:
            backup, client, events = Path(directory) / 'backup.jsonl', FakeClient(), []
            def flush(handle):
                cleaner.durable_flush(handle)
                events.extend(['file-fsync', 'directory-fsync'])
            def before_execute(_sql):
                self.assertEqual(events[-2:], ['file-fsync', 'directory-fsync'])
                saved = [json.loads(line) for line in backup.read_text(encoding='utf-8').splitlines()]
                record = saved[-1]
                self.assertEqual(record['kind'], 'description')
                self.assertEqual(record['before'], book()['before'])
                self.assertEqual(record['after'], proposal()['after'])
                self.assertEqual(record['after_update_time'], NEW_TIME)
                self.assertEqual(record['before_sha256'], cleaner.digest(record['before']))
                self.assertEqual(saved[0]['target'], TARGET)
                events.append('cas')
            client.before_execute = before_execute
            code, _output, errors, _ = self.run_cli(client, self.scan('--book', 123, '--decode-escapes',
                                                                   '--apply', '--backup', backup), flush=flush)
            self.assertEqual(code, 0, errors)
            self.assertIn('cas', events)
            header, written = cleaner.load_description_backup(backup)
            self.assertEqual(header['schema'], 'novel-plus-book-descriptions/v1')
            self.assertEqual(len(written), 1)
            self.assertEqual(written[0]['after_update_time'], NEW_TIME)
            original_bytes = backup.read_bytes()
            code, _output, _errors, _ = self.run_cli(client, self.scan('--book', 123, '--apply', '--backup', backup), flush=flush)
            self.assertEqual(code, 1)
            self.assertEqual(backup.read_bytes(), original_bytes)

    def test_backup_file_and_parent_directory_fsync_order(self):
        with tempfile.TemporaryDirectory() as directory:
            path, events = Path(directory) / 'backup.jsonl', []
            real_fsync, real_close = os.fsync, os.close
            descriptor = 987654321
            def fsync(fd):
                events.append('directory-fsync' if fd == descriptor else 'file-fsync')
                if fd != descriptor:
                    real_fsync(fd)
            def close(fd):
                if fd != descriptor:
                    real_close(fd)
            with path.open('x', encoding='utf-8') as handle, \
                    patch('os.open', return_value=descriptor) as opening, \
                    patch('os.fsync', side_effect=fsync), patch('os.close', side_effect=close):
                handle.write('backup')
                cleaner.durable_description_flush(handle)
            self.assertEqual(events, ['file-fsync', 'directory-fsync'])
            self.assertEqual(opening.call_args.args[0], str(Path(directory).resolve()))

    def test_both_backups_set_private_permissions_before_header_or_flush(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'permissions.jsonl'
            with path.open('x', encoding='utf-8') as handle, patch('os.fchmod', create=True) as chmod:
                cleaner.private_description_backup(handle)
                chmod.assert_called_once_with(handle.fileno(), 0o600)
            for action in ('scan', 'restore'):
                backup = Path(directory) / (action + '.jsonl')
                events = []
                client = FakeClient()
                args = self.scan('--book', 123, '--decode-escapes', '--apply', '--backup', backup)
                if action == 'restore':
                    source = Path(directory) / 'source.jsonl'
                    record = RestoreTests().saved_backup(source)
                    client.books[123].update(before=record['after'], before_update_time=NEW_TIME)
                    args = RestoreTests().restore(source, '--apply', '--restore-backup', backup)
                def private(handle):
                    self.assertEqual(handle.tell(), 0)
                    events.append('chmod0600')
                def flush(handle):
                    self.assertEqual(events[0], 'chmod0600')
                    cleaner.durable_flush(handle)
                    events.append('flush')
                with patch.object(cleaner, 'require_description_write_platform'), \
                        patch.object(cleaner, 'private_description_backup', side_effect=private):
                    code, _output, errors, _ = self.run_cli(client, args, flush=flush, allow_write=False)
                self.assertEqual(code, 0, errors)
                self.assertEqual(events[0], 'chmod0600')

    def test_large_removal_needs_an_explicit_reviewed_threshold(self):
        with tempfile.TemporaryDirectory() as directory:
            for ratio in (None, '0.9'):
                client = FakeClient([book(before='原文' * 50)])
                backup = Path(directory) / (str(ratio) + '.jsonl')
                options = () if ratio is None else ('--max-remove-ratio', ratio)
                with patch.object(cleaner, 'clean_description', side_effect=lambda body, _name, **_kwargs:
                                  cleaner.CleanResult(body, '正文' * 10)):
                    code, output, errors, _ = self.run_cli(client, self.scan('--book', 123, '--apply', '--backup', backup,
                                                                           *options), flush=cleaner.durable_flush)
                self.assertEqual(code, 0, errors)
                if ratio is None:
                    self.assertIn('"review": 1', output)
                    self.assertFalse(client.mutations)
                    self.assertEqual(cleaner.load_description_backup(backup)[1], [])
                else:
                    self.assertIn('"written": 1', output)
                    self.assertEqual(len(client.mutations), 1)

    def test_warnings_are_previewed_but_not_written_and_conflicts_are_not_restorable(self):
        with tempfile.TemporaryDirectory() as directory:
            client, backup = FakeClient([book(before='正常简介。\n--'), book(124)]), Path(directory) / 'backup.jsonl'
            client.counts[124] = 0
            with patch.object(cleaner, 'clean_description', side_effect=lambda body, name, **_kwargs:
                              cleaner.CleanResult(body, '新简介。' + body, warnings=['不确定'] if body.endswith('--') else [])):
                code, output, errors, _ = self.run_cli(client, self.scan('--book', 123, '--book', 124,
                                                                       '--apply', '--backup', backup),
                                                      flush=cleaner.durable_flush)
            self.assertEqual(code, 0, errors)
            self.assertIn('"review": 1', output)
            self.assertIn('"conflicts": 1', output)
            self.assertEqual(cleaner.load_description_backup(backup)[1], [])
            self.assertNotIn('WHERE id=123 AND', client.mutations[0])

    def test_uncertain_commits_block_automatic_restore(self):
        with tempfile.TemporaryDirectory() as directory:
            for attribute in ('bad_result', 'bad_readback'):
                with self.subTest(attribute=attribute):
                    client, backup = FakeClient(), Path(directory) / (attribute + '.jsonl')
                    setattr(client, attribute, True)
                    code, _output, _errors, _ = self.run_cli(client, self.scan('--book', 123, '--decode-escapes',
                                                                             '--apply', '--backup', backup),
                                                           flush=cleaner.durable_flush)
                    self.assertEqual(code, 1)
                    with self.assertRaisesRegex(ValueError, '不确定|不完整'):
                        cleaner.load_description_backup(backup)


class RestoreTests(unittest.TestCase):
    run_cli = WorkflowTests.run_cli
    def saved_backup(self, path):
        record = proposal()
        records = [cleaner.description_header(TARGET, 'scan', {'seo_mode': 'enabled'}), record,
                   {'kind': 'result', 'batch_id': 1, 'completed': True, 'readback_verified': True,
                    'changed': [{'book_id': 123, 'after_update_time': NEW_TIME}], 'conflicts': []}]
        path.write_text('\n'.join(json.dumps(value, ensure_ascii=False) for value in records) + '\n', encoding='utf-8')
        return record

    def restore(self, backup, *argv):
        return ['restore-descriptions', '--compose-dir', 'unused', '--backup', str(backup), *map(str, argv)]

    def test_corrupt_hash_or_missing_result_is_rejected_before_client(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'backup.jsonl'
            for mode in ('hash', 'result', 'schema', 'duplicate'):
                self.saved_backup(path)
                lines = [json.loads(line) for line in path.read_text(encoding='utf-8').splitlines()]
                if mode == 'hash':
                    lines[1]['after'] = '损坏'
                elif mode == 'result':
                    lines.pop()
                elif mode == 'schema':
                    lines[0]['schema'] = cleaner.BACKUP_SCHEMA
                else:
                    lines.insert(2, copy.deepcopy(lines[1]))
                path.write_text('\n'.join(json.dumps(value) for value in lines), encoding='utf-8')
                with self.subTest(mode=mode):
                    code, _output, _errors, constructor = self.run_cli(FakeClient(), self.restore(path))
                    self.assertEqual(code, 1)
                    constructor.assert_not_called()

    def test_restore_requires_target_identity_after_bytes_and_exact_after_time(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'backup.jsonl'
            record = self.saved_backup(path)
            for mode in ('target', 'time', 'identity', 'bytes', 'matching'):
                client = FakeClient([book(before=record['after'], before_update_time=NEW_TIME)])
                if mode == 'target':
                    client.identity['server_uuid'] = 'another-server'
                elif mode == 'time':
                    client.books[123]['before_update_time'] = OLD_TIME
                elif mode == 'identity':
                    client.books[123]['source_book_id'] = 'new-source-book'
                elif mode == 'bytes':
                    client.books[123]['before'] = '用户修改的简介。'
                with self.subTest(mode=mode):
                    code, output, _errors, _ = self.run_cli(client, self.restore(path))
                    self.assertEqual(code, 1 if mode == 'target' else 0)
                    self.assertFalse(client.mutations)
                    if mode == 'matching':
                        self.assertIn('"ready": 1', output)
                    elif mode != 'target':
                        self.assertIn('"conflicts": 1', output)

    def test_restore_has_independent_inverse_backup_and_never_replaces_whole_book_or_old_time(self):
        with tempfile.TemporaryDirectory() as directory:
            source, inverse = Path(directory) / 'original.jsonl', Path(directory) / 'restore.jsonl'
            record = self.saved_backup(source)
            original_bytes = source.read_bytes()
            client = FakeClient([book(before=record['after'], before_update_time=NEW_TIME)])
            code, output, errors, _ = self.run_cli(client, self.restore(source, '--apply', '--restore-backup', inverse),
                                                  layout='enabled', flush=cleaner.durable_flush)
            self.assertEqual(code, 0, errors)
            self.assertIn('"restored": 1', output)
            self.assertEqual(client.books[123]['before'], record['before'])
            self.assertEqual(client.books[123]['before_update_time'], NEW_TIME)
            self.assertEqual(source.read_bytes(), original_bytes)
            header, restored = cleaner.load_description_backup(inverse)
            self.assertEqual(header['action'], 'restore')
            self.assertEqual(restored[0]['before'], record['after'])
            self.assertEqual(restored[0]['after'], record['before'])
            self.assertNotIn('observed_hash=', client.mutations[0])
            self.assertNotIn('REPLACE INTO', client.mutations[0])
            code, _out, _errors, constructor = self.run_cli(client, self.restore(source, '--apply'))
            self.assertEqual(code, 1)
            constructor.assert_not_called()


if __name__ == '__main__':
    unittest.main()
