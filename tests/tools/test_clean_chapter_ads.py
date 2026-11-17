"""Offline regression cases for preservation and guarded chapter-content updates.

All chapters and database records here are synthetic. No CLI database entry point
is executed, and importing the tool must not start WSL, Docker, or network I/O.
"""
import contextlib
import importlib.util
import html
import io
import json
import os
from pathlib import Path
import re
import socket
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import urllib.request


SCRIPT = Path(__file__).resolve().parents[2] / "scripts/clean-chapter-ads.py"


def load_tool():
    spec = importlib.util.spec_from_file_location("clean_chapter_ads_tests", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module  # Required by dataclasses on Python 3.13.
    output = io.StringIO()
    environment = dict(os.environ)
    cwd = Path.cwd()
    with contextlib.ExitStack() as stack:
        for target in (
            "subprocess.run", "subprocess.Popen", "os.system",
            "urllib.request.urlopen", "socket.create_connection",
            "pathlib.Path.write_text", "pathlib.Path.write_bytes", "pathlib.Path.mkdir",
        ):
            stack.enter_context(patch(target, side_effect=AssertionError("import side effect: " + target)))
        stack.enter_context(contextlib.redirect_stdout(output))
        stack.enter_context(contextlib.redirect_stderr(output))
        spec.loader.exec_module(module)
    if output.getvalue() or dict(os.environ) != environment or Path.cwd() != cwd:
        raise AssertionError("import changed the environment or produced CLI output")
    return module


cleaner = load_tool()


class ChapterPreservationTests(unittest.TestCase):
    def unchanged(self, content, **kwargs):
        result = cleaner.clean_content(content, **kwargs)
        self.assertEqual(result.before, content)
        self.assertEqual(result.after, content)
        self.assertFalse(result.changes)
        return result

    def test_money_fullwidth_letters_and_common_symbols_are_prose(self):
        texts = (
            "<p>他花了￥100，售价由￥200降低了50%。</p>",
            "<p>成绩从Ａ提升到Ａ＋，型号是Ｂ２，编号１２３。</p>",
            "<p>防御90+，进度60%，a_b=2，3*4=12，路径/tmp，符号#与~。</p>",
            "<p>价格$100，邮件a@example.com，变量x|y与反斜杠\\。</p>",
            "<p>她在书上画了☆和★，算式是6÷2≤3。</p>",
        )
        for content in texts:
            with self.subTest(content=content):
                self.unchanged(content)

    def test_normal_quotes_and_keyword_mentions_are_preserved(self):
        for content in (
            '<p>“你要记住网址，”他说，“回去后再搜索这篇小说。”</p>',
            '<p>她说：“请收藏本站这几个字只是书上的例句。”</p>',
            '<p>她说：“<b>请收藏本站，更新最快。</b>”</p>',
            '<p>他阅读最新章节后，推荐朋友分享读书心得。</p>',
            '<p>她提醒书友谨记那个承诺，然后独自回到故乡。</p>',
        ):
            with self.subTest(content=content):
                self.unchanged(content)

    def test_long_clean_paragraph_preserves_all_characters(self):
        content = "<p>" + "他阅读书中的章节，记住老师说过的话。" * 100 + "预算￥100，编号Ａ２。</p>"
        self.unchanged(content)

    def test_entities_and_line_breaks_are_not_normalized(self):
        self.unchanged("<p>&nbsp;首段&#x3000;正文&amp;后续。</p>\r\n<p>第二段\t正文。</p>")

    def test_transport_control_characters_are_preserved(self):
        # Old mysql text transport silently replaced CHAR(1)/CHAR(2) and CR/NUL.
        self.unchanged("<p>前文\x00\x01\x02\r\n\t后文。</p>")

    def test_advertisement_tail_keeps_the_story(self):
        content = "<p>少女推开门。提醒书友谨记本站网址。</p>"
        result = cleaner.clean_content(content)
        self.assertEqual(result.before, content)
        self.assertEqual(result.after, "<p>少女推开门。</p>")
        self.assertTrue(result.changes)

    def test_obfuscated_advertisement_tail_keeps_the_story(self):
        result = cleaner.clean_content("<p>他走出房间，来到院子。`请收藏本站，更新最快，免费阅读。</p>")
        self.assertEqual(result.after, "<p>他走出房间，来到院子。</p>")
        self.assertTrue(result.changes)

    def test_whole_plain_and_obfuscated_advertisement_paragraphs(self):
        for advertisement in (
            "一秒记住http：//om",
            "浏♂览♂器♂搜索本站网址：www.example.com。",
        ):
            with self.subTest(advertisement=advertisement):
                result = cleaner.clean_content("<p>保留的正文。</p><p>" + advertisement + "</p><p>下一段正文。</p>")
                self.assertNotIn(advertisement, result.after)
                self.assertIn("<p>保留的正文。</p>", result.after)
                self.assertIn("<p>下一段正文。</p>", result.after)
                self.assertTrue(result.changes)

    def test_obfuscated_station_advertisement_suffixes(self):
        advertisements = (
            "/6`1,看￠书.网^ *无?错′内\\容/",
            "\\新\\完′本_神,站¨ -更.新.最/全\\",
            "*秒\\章`节+小¨说-网~ \\免,费^阅!读\\",
        )
        story = "她静静地望着窗外的月光。"
        for advertisement in advertisements:
            with self.subTest(advertisement=advertisement):
                result = cleaner.clean_content("<p>" + story + advertisement + "</p>")
                self.assertEqual(result.after, "<p>" + story + "</p>")
                self.assertTrue(result.changes)
                self.assertEqual(cleaner.clean_content(result.after).after, result.after)

    def test_station_names_and_quoted_station_advertisement_are_preserved(self):
        for content in (
            "<p>他看见看书网无错内容几个字，便想起旧事。</p>",
            "<p>新完本神站更新最全只是她写在纸上的文字。</p>",
            '<p>她念道：“/6`1,看￠书.网^ *无?错′内\\容/”</p>',
        ):
            with self.subTest(content=content):
                self.unchanged(content)

    def test_xiangshu_header_and_share_footer_remove_only_complete_standalone_notices(self):
        header = '【香书小说】提醒书友谨记：本站网址: www.xxiangshu.com 一秒记住、永不丢失！'
        footer = '【香书小说】倾心打造、全文无错；分享本站网址出去一次可以求一本自己想看的书！'
        story = '<p>痛！</p><p>他望向镜子，终于发现了伤口。</p>'
        original = '<p>' + header + '</p>' + story + '<p>' + footer + '</p>'
        result = cleaner.clean_content(original)
        self.assertEqual(result.after, '<p></p>' + story + '<p></p>')
        self.assertEqual([change['removed'] for change in reversed(result.changes)], [header, footer])
        self.assertTrue(all(change['kind'] == 'xiangshu_notice' for change in result.changes))
        self.assertFalse(result.warnings)
        self.assertFalse(cleaner.clean_content(result.after).changes)

    def test_xiangshu_notices_in_quotes_inline_tags_attributes_or_story_are_preserved(self):
        header = '【香书小说】提醒书友谨记：本站网址: www.xxiangshu.com 一秒记住、永不丢失！'
        footer = '【香书小说】倾心打造、全文无错；分享本站网址出去一次可以求一本自己想看的书！'
        for notice in (header, footer):
            for content in (
                '<p>“' + notice + '”</p>', '<p>她说：“<b>' + notice + '</b>”</p>',
                '<p>他指着<b>' + notice + '</b>这段旧广告。</p>',
                '<p>' + notice + '她读完了纸上的文字。</p>',
                '<p title="' + notice + '">正常剧情。</p>',
                '<!--' + notice + '--><p>正常剧情。</p>',
            ):
                with self.subTest(content=content):
                    result = cleaner.clean_content(content)
                    self.assertEqual(result.after, content)
                    self.assertFalse(result.changes)

    def test_xiangshu_notices_support_p_div_br_boundaries_and_padding(self):
        header = '【香书小说】提醒书友谨记：本站网址: www.xxiangshu.com 一秒记住、永不丢失！'
        for before, after in (('<p class="ad">', '</p>'), ('<div>', '</div>'), ('', '<br>'), ('<BR />', '')):
            with self.subTest(before=before, after=after):
                result = cleaner.clean_content(before + '&nbsp;\r\n' + header + '\t' + after)
                self.assertEqual(result.after, before + after)
                self.assertEqual(len(result.changes), 1)
                self.assertFalse(result.warnings)

    def test_xiangshu_detection_does_not_generalize_other_sites_or_partial_promises(self):
        for text in (
            '【香书小说】倾心打造、全文无错；她想借一本自己喜欢的书。',
            '【香书小说】倾心打造、全文无错；分享本站网址出去一次可以求一本自己想看的书！后面还有剧情。',
            '【另一个站点】倾心打造、全文无错；分享本站网址出去一次可以求一本自己想看的书！',
            '香书小说里的人物喜欢分享自己的故事。',
        ):
            with self.subTest(text=text):
                self.unchanged('<p>' + text + '</p>')

    def test_station_advertisement_without_story_boundary_is_reported(self):
        content = "<p>她静静望着月光/6`1,看￠书.网^ *无?错′内\\容/却想起了远方的朋友。</p>"
        result = self.unchanged(content)
        self.assertTrue(result.warnings)

    def test_ambiguous_advertisement_inside_sentence_is_not_cut(self):
        content = "<p>他把记住网址刻在木牌上，又把免费阅读写在旁边。</p>"
        self.unchanged(content)

    def test_long_paragraph_with_advertisement_preserves_story(self):
        story = "他循着小路来到山脚，抬头望向满天星光。" * 35
        result = cleaner.clean_content("<p>" + story + "提醒书友谨记本站网址。</p>")
        self.assertEqual(result.after, "<p>" + story + "</p>")

    def test_idempotent_on_ads_html_and_preserved_symbols(self):
        content = "<p>花费￥100，进度60%。</p></div><script>chapter_();</script><p>少女推开门。提醒书友谨记本站网址。</p>"
        first = cleaner.clean_content(content)
        second = cleaner.clean_content(first.after)
        self.assertEqual(second.after, first.after)
        self.assertFalse(second.changes)


class ChapterHtmlTests(unittest.TestCase):
    def test_complete_script_and_style_are_removed_without_prose_loss(self):
        content = '<p>前文。</p><SCRIPT type="text/javascript">chapter_();</SCRIPT><style>p{display:none}</style><p>后文。</p>'
        result = cleaner.clean_content(content)
        self.assertEqual(result.after, "<p>前文。</p><p>后文。</p>")
        self.assertTrue(result.changes)

    def test_malformed_script_is_retained_and_reported(self):
        for content in (
            "<p>前文。</p><script>chapter_();<</p><p>/script><p>后文。</p>",
            "<p>前文。</p><script>chapter_();<p>不能丢掉的剧情。</p>",
            "<script>chapter_();" + "未闭合的后续正文。" * 800,
        ):
            with self.subTest(content=content[:100]):
                result = cleaner.clean_content(content)
                self.assertEqual(result.after, content)
                self.assertFalse(result.changes)
                self.assertTrue(result.warnings)

    def test_isolated_div_closers_between_pages_are_removed(self):
        content = "<p>第一页。</p></div><p>第二页。</p></div>"
        result = cleaner.clean_content(content)
        self.assertEqual(result.after, "<p>第一页。</p><p>第二页。</p>")

    def test_legitimate_nested_div_container_is_preserved(self):
        for content in (
            '<div class="chapter"><p>正常正文。</p></div>',
            '<div class="outer"><div class="inner"><p>正常正文。</p></div></div>',
            '<div class="outer"><p>正常正文。</p></div><p>末段。</p>',
            '<div title="a > b"><p>正常正文。</p></div>',
            '<div data-example="</div>"><p>正常正文。</p></div>',
            '<div title="<script>code</script>"><p>正常正文。</p></div>',
        ):
            with self.subTest(content=content):
                self.assertEqual(cleaner.clean_content(content).after, content)

    def test_only_style_and_spaced_duplicate_p_are_cleaned(self):
        content = "<p>正常正文。</p> \r\n </p><style>p{color:red}</style>"
        # Remove the unwanted tags while preserving ordinary whitespace.
        self.assertEqual(cleaner.clean_content(content).after, "<p>正常正文。</p> \r\n ")

    def test_html_cleaning_can_be_disabled(self):
        content = "<p>正常正文。</p></div><script>chapter_();</script><style>p{color:red}</style>"
        self.assertEqual(cleaner.clean_content(content, clean_html=False).after, content)


class ExplicitFilterTests(unittest.TestCase):
    def test_filters_change_text_only_after_explicit_enablement(self):
        content = "<p>前文CUSTOM_AD后文。</p>"
        self.assertEqual(cleaner.clean_content(content, filters=("CUSTOM_AD",)).after, content)
        enabled = cleaner.clean_content(content, filters=("CUSTOM_AD",), apply_filters=True)
        self.assertEqual(enabled.after, "<p>前文后文。</p>")
        self.assertTrue(enabled.changes)

    def test_invalid_filter_is_rejected_even_when_filters_are_disabled(self):
        content = "<p>少女推开门。提醒书友谨记本站网址。</p>"
        for enabled in (False, True):
            with self.subTest(enabled=enabled):
                with self.assertRaises(ValueError):
                    cleaner.clean_content(content, filters=("少女", "["), apply_filters=enabled)

    def test_java_only_regex_is_rejected_instead_of_silently_changed(self):
        for pattern in (r"\p{Han}+", r"\Qexample.invalid\E", r"\R"):
            with self.subTest(pattern=pattern):
                with self.assertRaises(ValueError):
                    cleaner.clean_content("<p>正常正文。</p>", filters=(pattern,), apply_filters=True)


class GuardedUpdateSqlTests(unittest.TestCase):
    def record(self, table="book_content", index_id="123"):
        return {"table": table, "index_id": index_id,
                "before": "<p>原文Ａ。广告'\\\x00\x01\x02\r\n</p>", "after": "<p>原文Ａ。</p>"}

    def test_single_transaction_compares_exact_original_and_uses_utf8_hex(self):
        rows = [self.record(), self.record("book_content9", "124")]
        sql = cleaner.build_update_sql(rows)
        upper = sql.upper()
        self.assertEqual(upper.count("START TRANSACTION"), 1)
        self.assertEqual(upper.count("COMMIT"), 1)
        self.assertEqual(len(re.findall(r"\bUPDATE\b", upper)), 2)
        self.assertNotIn("LIKE", upper)
        for row in rows:
            self.assertIn(row["before"].encode("utf-8").hex().upper(), upper)
            self.assertIn(row["after"].encode("utf-8").hex().upper(), upper)
        updates = re.findall(r"\bUPDATE\b.*?;", sql, re.I | re.S)
        for update, row in zip(updates, rows):
            self.assertRegex(update, r"(?is)WHERE\b.*\bindex_id\b")
            self.assertRegex(update, r"(?is)WHERE\b.*\bBINARY\b")
            set_clause, where_clause = re.split(r"\bWHERE\b", update, maxsplit=1, flags=re.I)
            self.assertIn(row["after"].encode("utf-8").hex().upper(), set_clause.upper())
            self.assertIn(row["before"].encode("utf-8").hex().upper(), where_clause.upper())
            self.assertRegex(where_clause.replace("`", ""), r"(?i)\bBINARY\s+(?:\w+\.)?content\s*=")

    def test_all_supported_content_tables_are_accepted(self):
        for table in ("book_content", *("book_content" + str(i) for i in range(10))):
            with self.subTest(table=table):
                self.assertRegex(cleaner.build_update_sql([self.record(table)]), r"(?i)\bUPDATE\b")

    def test_table_name_and_index_id_are_validated_before_sql_generation(self):
        for table in ("book", "book_index", "book_content10", "BOOK_CONTENT", "book_content; DROP TABLE book", "other.book_content"):
            with self.subTest(table=table):
                with self.assertRaises(ValueError):
                    cleaner.build_update_sql([self.record(table)])
        for index_id in ("123 OR 1=1", "123; DELETE FROM book", "-1", "0", "", "1.2"):
            with self.subTest(index_id=index_id):
                with self.assertRaises(ValueError):
                    cleaner.build_update_sql([self.record(index_id=index_id)])

    def test_restore_reverses_values_and_guards_current_cleaned_text(self):
        row = self.record()
        sql = cleaner.build_update_sql([row], restore=True)
        set_clause, where_clause = re.split(r"\bWHERE\b", sql, maxsplit=1, flags=re.I)
        self.assertIn(row["before"].encode("utf-8").hex().upper(), set_clause.upper())
        self.assertIn(row["after"].encode("utf-8").hex().upper(), where_clause.upper())
        self.assertRegex(where_clause, r"(?i)\bBINARY\b")

    def test_duplicate_chapters_and_out_of_range_ids_are_rejected(self):
        with self.assertRaises(ValueError):
            cleaner.build_update_sql([self.record(), self.record()])
        for index_id in (True, str(2**63), "9" * 100):
            with self.subTest(index_id=index_id):
                with self.assertRaises(ValueError):
                    cleaner.build_update_sql([self.record(index_id=index_id)])


class OfflineFileCommandTests(unittest.TestCase):
    def run_file(self, *argv):
        with patch.object(cleaner, "MysqlClient", side_effect=AssertionError("file command connected to database")), \
                patch("subprocess.run", side_effect=AssertionError("file command started subprocess")), \
                contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            return cleaner.main(["file", *map(str, argv)])

    def test_cli_preserves_utf8_input_bytes_and_control_characters(self):
        with tempfile.TemporaryDirectory() as directory:
            source, output = Path(directory) / "chapter.txt", Path(directory) / "cleaned.txt"
            original = "<p>金额￥100，编号Ａ２。</p>\r\n<p>正文\x00\x01\x02\r\t后文。</p>".encode("utf-8")
            source.write_bytes(original)
            self.assertEqual(self.run_file("--input", source, "--output", output), 0)
            self.assertEqual(source.read_bytes(), original)
            self.assertEqual(output.read_bytes(), original)

    def test_cli_refuses_input_or_existing_output_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            source, output = Path(directory) / "chapter.txt", Path(directory) / "cleaned.txt"
            original, sentinel = "<p>原始正文。</p>".encode("utf-8"), b"existing output"
            source.write_bytes(original)
            output.write_bytes(sentinel)
            self.assertEqual(self.run_file("--input", source, "--output", source), 1)
            self.assertEqual(self.run_file("--input", source, "--output", output), 1)
            self.assertEqual(source.read_bytes(), original)
            self.assertEqual(output.read_bytes(), sentinel)

    def test_cli_default_is_preview_and_creates_no_output(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "chapter.txt"
            original = "<p>少女推开门。提醒书友谨记本站网址。</p>".encode("utf-8")
            source.write_bytes(original)
            self.assertEqual(self.run_file("--input", source), 0)
            self.assertEqual(source.read_bytes(), original)
            self.assertEqual(list(Path(directory).iterdir()), [source])


class ExecuteUpdatesResultTests(unittest.TestCase):
    class Client:
        def __init__(self, rows):
            self.result_rows = rows
            self.sql = None

        def rows(self, sql):
            self.sql = sql
            return self.result_rows

    def test_zero_affected_rows_are_reported_as_conflicts(self):
        records = [GuardedUpdateSqlTests().record(), GuardedUpdateSqlTests().record("book_content2", "124")]
        client = self.Client([["ROW", "book_content", "123", "1"], ["ROW", "book_content2", "124", "0"], ["TOTAL", "1"]])
        result = cleaner.execute_updates(client, records)
        self.assertEqual(result["changed"], [("book_content", 123)])
        self.assertEqual(result["conflicts"], [("book_content2", 124)])
        self.assertIn("BINARY content", client.sql)

    def test_missing_or_nonunique_result_rows_are_rejected(self):
        record = GuardedUpdateSqlTests().record()
        for rows in (
            [],
            [["ROW", "book_content", "123", "1"]],
            [["ROW", "book_content", "123", "1"], ["TOTAL", "0"]],
            [["ROW", "book_content", "123", "2"], ["TOTAL", "2"]],
        ):
            with self.subTest(rows=rows):
                with self.assertRaises(RuntimeError):
                    cleaner.execute_updates(self.Client(rows), [record])


class DatabaseWorkflowTests(unittest.TestCase):
    TARGET = {"server_uuid": "synthetic-server", "database": "synthetic_novels"}

    def args(self, command="scan", *options):
        return cleaner.make_parser().parse_args([command, "--compose-dir", "synthetic-unused", *map(str, options)])

    def record(self):
        return {"table": "book_content0", "content_id": 1, "index_id": 10, "book_id": 1000,
                "source_id": 7, "before": "<p>" + "她循着小路来到山脚，望着满天星光。" * 20 + "提醒书友谨记本站网址。</p>"}

    def fake_client(self):
        client = unittest.mock.Mock()
        client.target.return_value = dict(self.TARGET)
        client.rows.side_effect = AssertionError("unexpected database query")
        return client

    def test_scan_preview_never_executes_an_update(self):
        client, record = self.fake_client(), self.record()
        args = self.args("scan", "--book", "1000")
        with patch.object(cleaner, "MysqlClient", return_value=client), \
                patch.object(cleaner, "discover_tables", return_value=["book_content0"]), \
                patch.object(cleaner, "fetch_batch", side_effect=[[record], []]), \
                patch.object(cleaner, "execute_updates", side_effect=AssertionError("preview wrote data")), \
                patch.object(cleaner, "durable_flush", side_effect=AssertionError("preview created backup")), \
                patch("subprocess.run", side_effect=AssertionError("real subprocess")), \
                contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(cleaner.command_scan(args), 0)
        client.rows.assert_not_called()

    def test_apply_fsyncs_complete_backup_before_update_and_counts_conflict(self):
        with tempfile.TemporaryDirectory() as directory:
            backup = Path(directory) / "backup.jsonl"
            args = self.args("scan", "--book", "1000", "--apply", "--backup", backup)
            record, client, events = self.record(), self.fake_client(), []
            original_flush = cleaner.durable_flush

            def flush(handle):
                original_flush(handle)
                events.append("fsync")

            def execute(_client, records):
                self.assertEqual(events[-1], "fsync")
                persisted = [json.loads(line) for line in backup.read_text(encoding="utf-8").splitlines()]
                self.assertEqual(persisted[0]["target"], self.TARGET)
                chapter = persisted[1]
                self.assertEqual(chapter["before"], records[0]["before"])
                self.assertEqual(chapter["after"], records[0]["after"])
                self.assertEqual(chapter["before_sha256"], cleaner.digest(chapter["before"]))
                self.assertEqual(chapter["after_sha256"], cleaner.digest(chapter["after"]))
                events.append("update")
                return {"changed": [], "conflicts": [("book_content0", 10)]}

            output = io.StringIO()
            with patch.object(cleaner, "MysqlClient", return_value=client), \
                    patch.object(cleaner, "discover_tables", return_value=["book_content0"]), \
                    patch.object(cleaner, "fetch_batch", side_effect=[[record], []]), \
                    patch.object(cleaner, "durable_flush", side_effect=flush), \
                    patch.object(cleaner, "execute_updates", side_effect=execute), \
                    patch("subprocess.run", side_effect=AssertionError("real subprocess")), \
                    contextlib.redirect_stdout(output):
                self.assertEqual(cleaner.command_scan(args), 0)
            self.assertIn('"conflicts": 1', output.getvalue())
            self.assertIn('"written": 0', output.getvalue())
            self.assertEqual(events, ["fsync", "fsync", "update", "fsync"])

    def backup(self, path, corrupt=False):
        row = self.record()
        row.update(kind="chapter", after="<p>原文。</p>")
        row.update(before_sha256=cleaner.digest(row["before"]), after_sha256=cleaner.digest(row["after"]))
        if corrupt:
            row["before_sha256"] = "0" * 64
        header = {"kind": "header", "schema": cleaner.BACKUP_SCHEMA, "target": self.TARGET}
        path.write_text(json.dumps(header) + "\n" + json.dumps(row) + "\n", encoding="utf-8")

    def test_restore_rejects_corrupt_backup_before_constructing_client(self):
        with tempfile.TemporaryDirectory() as directory:
            backup = Path(directory) / "backup.jsonl"
            self.backup(backup, corrupt=True)
            with patch.object(cleaner, "MysqlClient", side_effect=AssertionError("corrupt backup accessed database")):
                with self.assertRaises(ValueError):
                    cleaner.command_restore(self.args("restore", "--backup", backup, "--apply"))

    def test_restore_rejects_different_database_before_any_content_query(self):
        with tempfile.TemporaryDirectory() as directory:
            backup = Path(directory) / "backup.jsonl"
            self.backup(backup)
            client = self.fake_client()
            client.target.return_value = {**self.TARGET, "server_uuid": "different-server"}
            with patch.object(cleaner, "MysqlClient", return_value=client), \
                    patch.object(cleaner, "discover_tables", side_effect=AssertionError("wrong database queried content")):
                with self.assertRaises(ValueError):
                    cleaner.command_restore(self.args("restore", "--backup", backup, "--apply"))
            client.rows.assert_not_called()

    class MemoryQueryClient:
        """Independent in-memory SQL oracle; never instantiates the Docker client."""
        def __init__(self):
            self.connection = sqlite3.connect(":memory:")
            self.connection.executescript("""
                CREATE TABLE book (id INTEGER, crawl_source_id INTEGER);
                CREATE TABLE book_index (id INTEGER, book_id INTEGER);
                CREATE TABLE book_content0 (id INTEGER, index_id INTEGER, content TEXT);
                CREATE TABLE book_content1 (id INTEGER, index_id INTEGER, content TEXT);
                INSERT INTO book VALUES (1000,7),(1001,8);
                INSERT INTO book_index VALUES (10,1000),(11,1001),(12,1000),(13,1000),(14,1001);
                INSERT INTO book_content0 VALUES (1,10,'page0'),(2,12,'page2'),(3,14,'page4');
                INSERT INTO book_content1 VALUES (4,10,'page1'),(5,11,'page3'),(6,13,'page5');
            """)

        def rows(self, sql):
            return [[str(value) if value is not None else "NULL" for value in row]
                    for row in self.connection.execute(sql).fetchall()]

    def test_keyset_pagination_crosses_shards_without_skipping_equal_ids(self):
        client = self.MemoryQueryClient()
        self.addCleanup(client.connection.close)
        args, after, found = self.args(), (0, "\uffff"), []
        for _ in range(4):
            rows = cleaner.fetch_batch(client, ["book_content0", "book_content1"], args, [], after, 2)
            if not rows:
                break
            found.extend((row["index_id"], row["table"]) for row in rows)
            after = (rows[-1]["index_id"], rows[-1]["table"])
        self.assertEqual(found, [(10, "book_content0"), (10, "book_content1"), (11, "book_content1"),
                                 (12, "book_content0"), (13, "book_content1"), (14, "book_content0")])

    def test_source_and_book_filters_intersect(self):
        client = self.MemoryQueryClient()
        self.addCleanup(client.connection.close)
        for source_ids, book, expected in (([8], 1000, []), ([7], 1000, [10, 10, 12, 13]), ([7, 8], 1001, [11, 14])):
            with self.subTest(source_ids=source_ids, book=book):
                args = self.args("scan", "--book", str(book))
                rows = cleaner.fetch_batch(client, ["book_content0", "book_content1"], args, source_ids, (0, "\uffff"), 100)
                self.assertEqual([row["index_id"] for row in rows], expected)


class ResponseMetadataExtractionTests(unittest.TestCase):
    BOOK = "大奉打更人"
    CHAPTER = "新书已肥"
    REAL_TEXT = "新书《灵境行者》，20万字了，书荒的可以看看。勿养~"

    def response(self, body=None):
        return {"code": 0, "data": {
            "bookinfo": {"bookname": self.BOOK, "bookid": 123, "desc": "这只是图书介绍，不能冒充章节正文。"},
            "riskinfo": {"banid": 0, "banmessage": ""},
            "fo": {"ame": self.CHAPTER, "chapterid": 947, "actualwords": 25, "wordst": 25,
                   "t": self.REAL_TEXT if body is None else body, "authorwords": {"t": ""}, "updatetimestamp": 1650504600},
        }, "msg": "成功"}

    def packed(self, response):
        return json.dumps(response, ensure_ascii=False, separators=(",", ":"))

    def damaged(self, body=None):
        value = self.packed(self.response(body)).replace('"', "“")
        # The real sample has damaged field syntax in addition to curly quotes.
        return value.replace("“bookid“:123", "“form“:etype“:1,“bookid“:123")

    def extract(self, content, **contexts):
        return cleaner.extract_response_content(content, contexts.get("book", self.BOOK), contexts.get("chapter", self.CHAPTER))

    def unchanged(self, content, **contexts):
        result = self.extract(content, **contexts)
        self.assertEqual(result.before, content)
        self.assertEqual(result.after, content)
        self.assertFalse(result.changes)
        self.assertTrue(result.warnings)
        return result

    def test_standard_response_preserves_quotes_escapes_newlines_and_nested_json(self):
        body = '他说：“别把引号丢掉。” ASCII="quoted"，路径C:\\tmp\\chapter。\n' + \
               '{"authorwords":{"t":"这是正文引用的JSON"}}\r\n百分比60%与&符号。'
        content = self.packed(self.response(body))
        result = self.extract(content)
        self.assertEqual(result.before, content)
        self.assertEqual(result.after, "<p>" + html.escape(body, quote=False) + "</p>")
        self.assertTrue(result.changes)
        self.assertFalse(result.warnings)

    def test_metadata_word_count_does_not_truncate_the_26_character_text(self):
        self.assertEqual(len(self.REAL_TEXT), 26)
        result = self.extract(self.packed(self.response()))
        self.assertEqual(result.after, "<p>" + self.REAL_TEXT + "</p>")
        self.assertIn("勿养~", result.after)

    def test_body_html_is_kept_as_text_instead_of_executed_or_reformatted(self):
        body = '他抄下<p>原样标记</p><script>alert(1)</script>，继续写正文。'
        result = self.extract(self.packed(self.response(body)))
        self.assertEqual(result.after, '<p>' + html.escape(body, quote=False) + '</p>')
        self.assertTrue(result.warnings)

    def test_ordinary_cleaner_protects_reordered_response_keys_and_extra_fields(self):
        value = self.response('这是完整正文。' * 100 + '<script>AD();</script>')
        variants = [
            {'data': value['data'], 'code': 0, 'msg': '成功'},
            {'code': 0, 'other': 1, 'data': value['data'], 'msg': '成功'},
            {'msg': '成功', 'data': value['data'], 'code': 0},
        ]
        for value in variants:
            for content in (self.packed(value), self.packed(value).replace('"', '“')):
                with self.subTest(content=content[:40]):
                    result = cleaner.clean_content(content, ('完整正文',), True)
                    self.assertEqual(result.after, content)
                    self.assertFalse(result.changes)
                    self.assertTrue(result.warnings)

    def test_damaged_curly_response_can_only_produce_review_candidate(self):
        content = self.damaged()
        result = self.extract(content)
        self.assertEqual(result.before, content)
        self.assertEqual(result.after, "<p>" + self.REAL_TEXT + "</p>")
        self.assertTrue(result.changes)
        self.assertTrue(result.warnings)

    def test_html_paragraph_split_keeps_the_complete_chapter_text(self):
        content = self.damaged()
        boundary = content.index("“t“:“") + len("“t“:“")
        wrapped = "<p>" + content[:boundary] + "</p><p>" + content[boundary:] + "</p>"
        result = self.extract(wrapped)
        self.assertEqual(result.before, wrapped)
        self.assertEqual(result.after, "<p>" + self.REAL_TEXT + "</p>")
        self.assertTrue(result.warnings)

    def test_wrong_book_or_chapter_context_preserves_the_response(self):
        for content in (self.packed(self.response()), self.damaged()):
            for contexts in ({"book": "另一部小说"}, {"chapter": "另一章"}):
                with self.subTest(content=content[:40], contexts=contexts):
                    self.unchanged(content, **contexts)

    def test_missing_or_duplicate_primary_body_boundaries_are_not_guessed(self):
        incomplete = self.damaged().split("“,“authorwords“:", 1)[0]
        self.unchanged(incomplete)
        duplicate = self.packed(self.response()).replace('"t":' + json.dumps(self.REAL_TEXT, ensure_ascii=False),
                                                        '"t":"第一份正文","t":"第二份正文"', 1)
        self.unchanged(duplicate)

    def test_fake_authorwords_marker_inside_damaged_body_is_not_a_cut_point(self):
        body = '她读出“,“authorwords“:{“t“:“假界标“}这串字符，又翻到了下一页。'
        self.unchanged(self.damaged(body))

    def test_json_quoted_in_ordinary_story_is_not_extracted(self):
        content = "<p>他打开终端，屏幕显示接口响应：</p><p>" + self.packed(self.response()) + "</p><p>他转身走出了房间。</p>"
        self.unchanged(content)

    def test_second_extraction_leaves_the_extracted_body_unchanged(self):
        source = self.packed(self.response())
        first = self.extract(source)
        second = self.extract(first.after)
        self.assertEqual(source, first.before)
        self.assertEqual(second.before, first.after)
        self.assertEqual(second.after, first.after)
        self.assertFalse(second.changes)

    def test_ordinary_cleaner_protects_response_envelopes_even_with_filters(self):
        for content in (self.packed(self.response()), self.damaged(), "<p>" + self.damaged() + "</p>"):
            for enabled in (False, True):
                with self.subTest(content=content[:40], enabled=enabled):
                    result = cleaner.clean_content(content, filters=(r"[\s\S]+",), apply_filters=enabled)
                    self.assertEqual(result.before, content)
                    self.assertEqual(result.after, content)
                    self.assertFalse(result.changes)
                    self.assertTrue(result.warnings)

    def test_extract_cli_is_offline_and_saves_independent_candidate_and_report(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "response.txt"
            output, report = Path(directory) / "candidate.txt", Path(directory) / "report.jsonl"
            content = self.packed(self.response())
            original = content.encode("utf-8")
            source.write_bytes(original)
            argv = ["extract-response", "--input", str(source), "--expected-book", self.BOOK,
                    "--expected-chapter", self.CHAPTER]
            with patch.object(cleaner, "MysqlClient", side_effect=AssertionError("extract accessed database")), \
                    patch("subprocess.run", side_effect=AssertionError("extract started subprocess")), \
                    patch("urllib.request.urlopen", side_effect=AssertionError("extract accessed network")), \
                    contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(cleaner.main([*argv, "--output", str(output), "--report", str(report)]), 0)
                self.assertEqual(cleaner.main([*argv, "--output", str(source)]), 1)
                self.assertEqual(cleaner.main([*argv, "--output", str(output)]), 1)
            self.assertEqual(source.read_bytes(), original)
            expected = "<p>" + self.REAL_TEXT + "</p>"
            self.assertEqual(output.read_bytes(), expected.encode("utf-8"))
            records = [json.loads(line) for line in report.read_text(encoding="utf-8").splitlines()]
            self.assertEqual(len(records), 1)
            self.assertEqual(records[0]["before"], content)
            self.assertEqual(records[0]["after"], expected)
            self.assertEqual(records[0]["expected_book"], self.BOOK)
            self.assertEqual(records[0]["expected_chapter"], self.CHAPTER)
            self.assertFalse(records[0]["database_written"])


class MysqlStdinTransportTests(unittest.TestCase):
    def client(self):
        client = cleaner.MysqlClient.__new__(cleaner.MysqlClient)
        client.command = ['synthetic-mysql-command']
        client.password = 'synthetic-test-password'
        client.timeout = 10
        return client

    def test_password_delimiter_is_lf_bytes_and_sql_controls_are_preserved(self):
        client = self.client()
        sql = "SELECT '中文\r\n\t\x00';"
        response = subprocess.CompletedProcess(client.command, 0, stdout='中文\t字段\n'.encode('utf-8'), stderr=b'')
        with patch('subprocess.run', return_value=response) as call:
            self.assertEqual(client.rows(sql), [['中文', '字段']])
        self.assertEqual(call.call_args.kwargs['input'], (client.password + '\n' + sql + '\n').encode('utf-8'))
        self.assertNotIn('text', call.call_args.kwargs)
        self.assertNotIn('encoding', call.call_args.kwargs)
        self.assertNotIn(client.password, str(call.call_args.args))

    def test_error_output_redacts_password_after_byte_decode(self):
        client = self.client()
        error = ('拒绝连接：' + client.password).encode('utf-8') + b'\xff'
        response = subprocess.CompletedProcess(client.command, 1, stdout=b'', stderr=error)
        with patch('subprocess.run', return_value=response), self.assertRaises(RuntimeError) as raised:
            client.rows('SELECT 1;')
        self.assertNotIn(client.password, str(raised.exception))
        self.assertIn('***', str(raised.exception))


class DescriptionCleaningTests(unittest.TestCase):
    BOOK = "女主，你的后宫是不是跑错片场了？"

    def test_default_preserves_escapes_chinese_labels_and_windows_paths(self):
        for content in (
            r"【穿书\u002F反派\u002F后宫\u002F不种马\u002F女主全是百合】\n\n本书又名：《副标题》",
            r"目录 C:\new\books，文件 D:\temp\notes.txt，未知 \q 与 \x2f。",
            "【百合】（不种马）她改变命运，预算￥100，型号Ａ２，进度50%。 --",
        ):
            with self.subTest(content=content):
                result = cleaner.clean_description(content, self.BOOK)
                self.assertEqual(result.before, content)
                self.assertEqual(result.after, content)
                self.assertFalse(result.changes)

    def test_explicit_json_escape_decode_keeps_all_unicode_and_unknown_sequences(self):
        content = r'中文\u002F百合\n下一段\t\"引号\"与\/网址、\\反斜线、\q未知、\x2f未知。'
        result = cleaner.clean_description(content, self.BOOK, decode_escapes=True)
        self.assertEqual(result.before, content)
        self.assertEqual(result.after, '中文/百合\n下一段\t"引号"与/网址、\\反斜线、\\q未知、\\x2f未知。')
        self.assertTrue(result.changes)
        self.assertFalse(result.warnings)

    def test_decode_is_one_pass_even_for_double_encoding_and_encoded_backslash(self):
        content = r"双层 \\u002F 和 \\n；编码反斜线 \u005cn。"
        result = cleaner.clean_description(content, self.BOOK, decode_escapes=True)
        self.assertEqual(result.after, r"双层 \u002F 和 \n；编码反斜线 \n。")
        self.assertTrue(result.warnings)
        self.assertNotIn("双层 /", result.after)

    def test_surrogate_pairs_are_combined_and_invalid_ones_retained(self):
        result = cleaner.clean_description(r"中文\uD83D\uDE00与\uD840\uDC00。", self.BOOK, True)
        self.assertEqual(result.after, "中文😀与𠀀。")
        self.assertFalse(result.warnings)
        for content in (r"正文\uD83D结尾", r"正文\uDE00结尾", r"正文\u12", r"正文\uZZZZ"):
            with self.subTest(content=content):
                result = cleaner.clean_description(content, self.BOOK, True)
                self.assertEqual(result.after, content)
                self.assertTrue(result.warnings)
                result.after.encode("utf-8")  # No invalid surrogate may leak into output.

    def test_complete_book_name_signature_requires_a_real_terminal_line(self):
        book = "书名[第一部]+(她与她)?"
        for separator in ("\n", "\r\n", "\r", "<br>", "<BR />", '<br class="break">'):
            with self.subTest(separator=separator):
                result = cleaner.clean_description("【百合】正常简介。" + separator + "--" + book, book)
                self.assertEqual(result.after, "【百合】正常简介。")
                self.assertTrue(result.changes)
        for content in (
            "正常简介。 --" + book,
            "正常简介。\n--" + book + "，这也是剧情的一部分。",
            "正常简介。\n--" + book + "\n后面还有正文。",
            "正常简介。\n--其他书名",
            "正常简介。\n--",
            "她写道：“\n--" + book,
        ):
            with self.subTest(content=content):
                self.assertEqual(cleaner.clean_description(content, book).after, content)

    def test_html_footer_removal_keeps_containers_and_ignores_attribute_comment_markers(self):
        content = '<div><p>简介。<br data-label="a>b">--' + self.BOOK + '</p></div>'
        self.assertEqual(cleaner.clean_description(content, self.BOOK).after, '<div><p>简介。</p></div>')
        for content in (
            '<p title="例句\n--' + self.BOOK + '">正常简介。</p>',
            '<p>正常简介。</p><!--\n--' + self.BOOK + '-->',
            '<script>异常\n--' + self.BOOK,
        ):
            with self.subTest(content=content):
                self.assertEqual(cleaner.clean_description(content, self.BOOK).after, content)

    def test_real_pollution_shape_decodes_but_does_not_invent_truncated_text_or_strip_bare_dash(self):
        prefix = (r"【穿书\u002F反派\u002F后宫\u002F不种马\u002F女主全是百合】\n\n"
                  r"本书又名：《女主，你的后宫是不是跑错片场了？》、《我们三、四、五……总之过好日子比什么都重要？》\n\n熬夜追…")
        expected = ("【穿书/反派/后宫/不种马/女主全是百合】\n\n"
                    "本书又名：《女主，你的后宫是不是跑错片场了？》、《我们三、四、五……总之过好日子比什么都重要？》\n\n熬夜追…")
        full = cleaner.clean_description(prefix + r"\n--" + self.BOOK, self.BOOK, True)
        self.assertEqual(full.after, expected)
        bare = cleaner.clean_description(prefix + "\r\n--", self.BOOK, True)
        self.assertEqual(bare.after, expected + "\r\n--")
        self.assertEqual(cleaner.clean_description(full.after, self.BOOK, True).after, full.after)

    def test_terminal_breaks_after_html_signature_are_removed_with_signature(self):
        for trailer in ('<br>', '<BR />\n', '<br title="a>b">\r\n'):
            with self.subTest(trailer=trailer):
                content = '<p>【百合】简介。<BR />--' + self.BOOK + trailer + '</p>'
                self.assertEqual(cleaner.clean_description(content, self.BOOK).after, '<p>【百合】简介。</p>')

    def test_stored_synopsis_requires_bare_footer_confirmation_then_preserves_all_story_text(self):
        original = (r"【穿书\u002F反派\u002F后宫\u002F不种马\u002F女主全是百合】\n\n"
                    r"本书又名：《女主，你的后宫是不是跑错片场了？》、《我们三、四、五……总之过好日子比什么都重要？》\n\n熬夜追…"
                    + "\r\n--")
        expected = ("【穿书/反派/后宫/不种马/女主全是百合】\n\n"
                    "本书又名：《女主，你的后宫是不是跑错片场了？》、《我们三、四、五……总之过好日子比什么都重要？》\n\n熬夜追…")
        self.assertEqual(len(original), 105)
        self.assertEqual(cleaner.clean_description(original, self.BOOK, True).after, expected + "\r\n--")
        result = cleaner.clean_description(original, self.BOOK, True, strip_bare_footer=True)
        self.assertEqual(result.after, expected)
        self.assertEqual(len(result.after), 77)
        self.assertFalse(result.warnings)
        self.assertEqual(sum(change['kind'] == 'description_bare_footer' for change in result.changes), 1)
        again = cleaner.clean_description(result.after, self.BOOK, True, strip_bare_footer=True)
        self.assertEqual(again.after, expected)
        self.assertFalse(again.changes)

    def test_confirmed_bare_footer_is_limited_to_an_independent_terminal_line(self):
        for separator in ('\n', '\r\n', '\r', '<br>', '<BR />', '<br class="break">'):
            with self.subTest(separator=separator):
                self.assertEqual(cleaner.clean_description('正常简介。' + separator + '--', self.BOOK,
                                                          strip_bare_footer=True).after, '正常简介。')
        for content in (
            '正常简介。 --', '她--他一起出发。', '正常简介。\n--另一部书',
            '正常简介。\n--\n后面还有剧情。', '--', '她说：“\n--',
            '<p title="例句\n--">正常简介。</p>', '<p>正常简介。</p><!--\n-->',
            '<script>剧情\n--', r'正常简介。\n--',
        ):
            with self.subTest(content=content):
                self.assertEqual(cleaner.clean_description(content, self.BOOK,
                                                          strip_bare_footer=True).after, content)
        html = '<div><p>【百合】简介。<br>--<br>\r\n</p></div>'
        self.assertEqual(cleaner.clean_description(html, self.BOOK, strip_bare_footer=True).after,
                         '<div><p>【百合】简介。</p></div>')

    def test_bare_footer_cli_confirmation_is_recorded_without_touching_input_or_database(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / 'stored.txt'
            output, report = Path(directory) / 'cleaned.txt', Path(directory) / 'report.jsonl'
            original = (r'【百合\u002F反派】\n正常简介。' + '\r\n--').encode('utf-8')
            source.write_bytes(original)
            argv = ['file', '--kind', 'description', '--book-name', self.BOOK, '--decode-escapes',
                    '--strip-bare-footer', '--input', str(source), '--output', str(output), '--report', str(report)]
            with patch.object(cleaner, 'MysqlClient', side_effect=AssertionError('offline accessed database')), \
                    patch('subprocess.run', side_effect=AssertionError('offline started subprocess')), \
                    contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(cleaner.main(argv), 0)
            self.assertEqual(source.read_bytes(), original)
            self.assertEqual(output.read_bytes(), '【百合/反派】\n正常简介。'.encode('utf-8'))
            record = json.loads(report.read_text(encoding='utf-8'))
            self.assertTrue(record['strip_bare_footer'])
            self.assertEqual(record['before'].encode('utf-8'), original)
            self.assertFalse(record['database_written'])

    def test_bare_footer_flag_does_not_apply_to_chapters(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / 'chapter.txt'
            source.write_bytes('正常剧情。\n--'.encode('utf-8'))
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(cleaner.main(['file', '--input', str(source), '--strip-bare-footer']), 1)
            self.assertEqual(cleaner.clean_content('正常剧情。\n--').after, '正常剧情。\n--')

    def test_conservative_advertisement_cleaning_is_reused_without_cutting_story_quotes(self):
        content = "【百合】少女推开门。提醒书友谨记本站网址。"
        self.assertEqual(cleaner.clean_description(content, self.BOOK).after, "【百合】少女推开门。")
        quoted = '她说：“请收藏本站，更新最快。”（这是故事里的广告台词。）'
        self.assertEqual(cleaner.clean_description(quoted, self.BOOK).after, quoted)
        ambiguous = "故事刚开始请收藏本站，更新最快。"
        result = cleaner.clean_description(ambiguous, self.BOOK)
        self.assertEqual(result.after, ambiguous)
        self.assertTrue(result.warnings)

    def test_invalid_book_name_is_rejected_and_empty_result_is_reported(self):
        for name in ("", "  ", "书名\n另一行"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                cleaner.clean_description("原简介。", name)
        result = cleaner.clean_description("--" + self.BOOK, self.BOOK)
        self.assertEqual(result.after, "")
        self.assertTrue(result.warnings)

    def test_description_cli_is_offline_preview_then_saves_independent_output_and_report(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "description.txt"
            output, report = Path(directory) / "cleaned.txt", Path(directory) / "report.jsonl"
            original = (r"【百合\u002F反派】\n正常简介。" + "\r\n--" + self.BOOK).encode("utf-8")
            source.write_bytes(original)
            argv = ["file", "--kind", "description", "--book-name", self.BOOK,
                    "--decode-escapes", "--input", str(source)]
            with patch.object(cleaner, "MysqlClient", side_effect=AssertionError("description accessed database")), \
                    patch("subprocess.run", side_effect=AssertionError("description started subprocess")), \
                    patch("urllib.request.urlopen", side_effect=AssertionError("description accessed network")), \
                    contextlib.redirect_stdout(io.StringIO()) as stdout, contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(cleaner.main(argv), 0)
                self.assertEqual(list(Path(directory).iterdir()), [source])
                self.assertIn("离线简介", stdout.getvalue())
                self.assertEqual(cleaner.main([*argv, "--output", str(output), "--report", str(report)]), 0)
                self.assertEqual(cleaner.main([*argv, "--output", str(source)]), 1)
                self.assertEqual(cleaner.main([*argv, "--output", str(output)]), 1)
            self.assertEqual(source.read_bytes(), original)
            self.assertEqual(output.read_bytes(), "【百合/反派】\n正常简介。".encode("utf-8"))
            record = json.loads(report.read_text(encoding="utf-8"))
            self.assertEqual(record["before"].encode("utf-8"), original)
            self.assertEqual(record["after"], "【百合/反派】\n正常简介。")
            self.assertEqual(record["content_kind"], "description")
            self.assertEqual(record["book_name"], self.BOOK)
            self.assertTrue(record["decode_escapes"])
            self.assertFalse(record["database_written"])

    def test_description_options_do_not_change_chapter_mode_or_accept_arbitrary_filters(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "input.txt"
            original = r"【百合\u002F反派】\n正常简介。".encode("utf-8")
            source.write_bytes(original)
            with patch.object(cleaner, "MysqlClient", side_effect=AssertionError("file accessed database")), \
                    contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                for argv in (
                    ["--kind", "description"],
                    ["--kind", "description", "--book-name", self.BOOK, "--use-filters"],
                    ["--kind", "description", "--book-name", self.BOOK, "--keep-html"],
                    ["--book-name", self.BOOK], ["--decode-escapes"],
                ):
                    with self.subTest(argv=argv):
                        self.assertEqual(cleaner.main(["file", "--input", str(source), *argv]), 1)
            self.assertEqual(source.read_bytes(), original)
            self.assertEqual(cleaner.clean_content(original.decode("utf-8")).after.encode("utf-8"), original)


if __name__ == "__main__":
    unittest.main()
