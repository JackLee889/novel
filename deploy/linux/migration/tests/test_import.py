"""Offline fixtures only. No Docker, network or real database client is used."""
import gzip
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
BASH = shutil.which("bash")
GIT_BASH = Path("C:/Program Files/Git/bin/bash.exe")
if os.name == "nt" and GIT_BASH.exists():
    BASH = str(GIT_BASH)
TABLES = ["book_category", "book", "book_index", "book_content", *[f"book_content{i}" for i in range(10)],
          "book_setting", "crawl_source", "crawl_batch_task", "crawl_single_task"]
HEADER = "INSERT INTO `book_content` (`id`, `index_id`, `content`) VALUES "


def posix(path):
    path = str(Path(path).resolve()).replace("\\", "/")
    return "/" + path[0].lower() + path[2:] if os.name == "nt" else path


def run(command, **kwargs):
    return subprocess.run([BASH, "--noprofile", "--norc", "-c", command], capture_output=True, **kwargs)


@unittest.skipUnless(BASH, "Bash is required")
class ImportTests(unittest.TestCase):
    def validate(self, text, table="book_content", emit=1, trusted_dump=0):
        return run(f"LC_ALL=C awk -v table={table} -v emit={emit} -v trusted_dump={trusted_dump} -f '{posix(ROOT / 'validate-table.awk')}'",
                   input=text.encode())

    def test_dump_strings_are_data(self):
        row = HEADER + "(1,1,'text; DROP TABLE user; \\'quote\\' \\\\ end');\n"
        result = self.validate(row)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.decode(), row)

    def test_fixed_set_filtered(self):
        text = "/*!50503 SET NAMES utf8mb4 */;\n" + HEADER + "(1,1,'ok');\n"
        result = self.validate(text)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.decode(), HEADER + "(1,1,'ok');\n")

    def test_reviewed_mode_still_filters_and_refuses_different_tables_or_columns(self):
        good = "/*!50503 SET NAMES utf8mb4 */;\n" + HEADER + "(1,1,'ok');\n"
        result = self.validate(good, trusted_dump=1)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.decode(), HEADER + "(1,1,'ok');\n")
        for bad in ["DELETE FROM book_content;", HEADER.replace("book_content", "user") + "(1,1,'ok');",
                    HEADER.replace("`content`", "`password`") + "(1,1,'ok');", HEADER + "(1,1,'ok')"]:
            self.assertNotEqual(self.validate(bad + "\n", trusted_dump=1).returncode, 0)

    def test_reject_other_statements_without_values_in_diagnostic(self):
        for text in ["DELETE FROM book_content;", "UPDATE book SET x=1;", "REPLACE INTO book_content VALUES(1,1,'secret');",
                     "/*!40101 SET SQL_MODE='malicious' */;", HEADER + "(1,1,'secret'); DROP TABLE user;",
                     HEADER + "(1,1,'secret'); DELETE FROM user; SELECT(1);",
                     HEADER.replace("book_content", "user") + "(1,1,'secret');", HEADER + "(1,1,LOAD_FILE('/secret'));",
                     HEADER + "(1,1,'unterminated);", HEADER + "(1,1,'ok') ON DUPLICATE KEY UPDATE content='secret';"]:
            with self.subTest(text=text[:50]):
                result = self.validate(text + "\n")
                self.assertNotEqual(result.returncode, 0)
                self.assertNotIn(b"secret", result.stderr)

    def fixture(self, directory):
        data = directory / "data"
        (data / "tables").mkdir(parents=True)
        (data / "schema.sql").write_text("SELECT 'safe schema fixture';\n", encoding="utf-8")
        (data / "manifest.json").write_text("{}\n", encoding="utf-8")
        (data / "cover-manifest.json").write_text("{}\n", encoding="utf-8")
        (data / "pic/localPic/cover").mkdir(parents=True)
        (data / "pic/localPic/cover/1.jpg").write_bytes(b"fixture")
        for table in TABLES:
            contents = "/*!50503 SET NAMES utf8mb4 */;\n"
            if table == "book_content":
                contents += HEADER + "(1,1,'fixture');\n"
            with gzip.open(data / f"tables/{table}.sql.gz", "wb") as stream:
                stream.write(contents.encode())
        self.write_hashes(data)
        binaries = directory / "bin"
        binaries.mkdir()
        mysql = binaries / "mysql"
        mysql.write_text("""#!/bin/bash
if [[ "$*" == *--execute=* ]]; then printf '%s\\n' "${MOCK_OBJECTS:-0}"; exit 0; fi
cat > "$MOCK_CAPTURE"
exit "${MOCK_MYSQL_EXIT:-0}"
""", encoding="utf-8", newline="\n")
        mysql.chmod(0o755)
        env = os.environ.copy()
        env.update(MYSQL_HOST="mysql", MYSQL_DATABASE="novel_plus", MYSQL_USERNAME="fixture", MYSQL_PWD="fixture-public",
                   DATA_MIGRATION_ACK="initialize-empty:novel_plus", ADMIN_INITIAL_PASSWORD="fixture-public",
                   WEBSITE_DOMAIN="https://example.invalid/'quoted'", WEBSITE_NAME="测试小说",
                   MIGRATION_DATA_ROOT=posix(data), MIGRATION_DATA_SHA256=self.digest(data / "SHA256SUMS"),
                   MOCK_CAPTURE=posix(directory / "capture.sql"))
        return data, binaries, env

    @staticmethod
    def digest(path):
        return hashlib.sha256(path.read_bytes()).hexdigest()

    def write_hashes(self, data):
        paths = sorted(p for p in data.rglob("*") if p.is_file() and p.name != "SHA256SUMS")
        (data / "SHA256SUMS").write_text("".join(f"{self.digest(p)}  {p.relative_to(data).as_posix()}\n" for p in paths),
                                       encoding="utf-8", newline="\n")

    def execute(self, directory, binaries, env, tools=ROOT):
        command = f"export PATH='{posix(binaries)}:/usr/bin:/bin'; /bin/bash '{posix(tools / 'import.sh')}'"
        return run(command, env=env)

    def test_reviewed_marker_requires_one_matching_lowercase_digest(self):
        for marker in ["matching", "different", "two_lines", "uppercase", "missing"]:
            with self.subTest(marker=marker), tempfile.TemporaryDirectory() as temporary:
                directory = Path(temporary)
                _, binaries, env = self.fixture(directory)
                tools = directory / "tools"
                tools.mkdir()
                for name in ["import.sh", "validate-table.awk"]:
                    shutil.copyfile(ROOT / name, tools / name)
                digest = env["MIGRATION_DATA_SHA256"]
                contents = {"matching": digest + "\n", "different": "0" * 64 + "\n",
                            "two_lines": digest + "\n" + digest + "\n", "uppercase": digest.upper() + "\n"}
                if marker in contents:
                    (tools / "reviewed-data.sha256").write_text(contents[marker], encoding="utf-8", newline="\n")
                result = self.execute(directory, binaries, env, tools=tools)
                self.assertEqual(result.returncode, 0, result.stderr.decode())
                self.assertEqual(b"Using the privately reviewed export format" in result.stdout, marker == "matching")

    def test_success_sealed_data_hash_admin_and_no_old_set_forwarded(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            _, binaries, env = self.fixture(directory)
            result = self.execute(directory, binaries, env)
            self.assertEqual(result.returncode, 0, result.stderr.decode())
            capture = (directory / "capture.sql").read_text(encoding="utf-8")
            expected = hashlib.md5(hashlib.md5(b"admin1qazxsw2fixture-public").digest()).hexdigest()
            self.assertIn(f"SET @novel_initial_admin_hash = '{expected}'", capture)
            self.assertNotIn("fixture-public", capture)
            self.assertNotIn("/*!50503", capture)
            self.assertIn("COMMIT;", capture)
            self.assertLess(capture.index("novel_plus_migration_refused_existing_objects_or_lock"), capture.index("safe schema fixture"))

    def test_early_existing_schema_refusal(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            data, binaries, env = self.fixture(directory)
            (data / "SHA256SUMS").write_text("broken", encoding="utf-8")
            env["MOCK_OBJECTS"] = "1"
            result = self.execute(directory, binaries, env)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn(b"schema already contains", result.stderr)
            self.assertFalse((directory / "capture.sql").exists())

    def test_bad_checksum_never_imports(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            data, binaries, env = self.fixture(directory)
            (data / "manifest.json").write_text("changed", encoding="utf-8")
            result = self.execute(directory, binaries, env)
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse((directory / "capture.sql").exists())

    def test_corrupt_gzip_preflight_never_imports(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            data, binaries, env = self.fixture(directory)
            (data / "tables/book_content.sql.gz").write_bytes(b"not gzip")
            self.write_hashes(data)
            env["MIGRATION_DATA_SHA256"] = self.digest(data / "SHA256SUMS")
            result = self.execute(directory, binaries, env)
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse((directory / "capture.sql").exists())

    def test_gzip_failure_during_stream_never_sends_commit(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            _, binaries, env = self.fixture(directory)
            wrapper = binaries / "gzip"
            wrapper.write_text("""#!/bin/bash
if [[ "$*" == *book_content.sql.gz* && "$1" == -dc ]]; then
  if [[ -e "$MOCK_CAPTURE" ]]; then /usr/bin/gzip "$@"; exit 7; fi
fi
exec /usr/bin/gzip "$@"
""", encoding="utf-8", newline="\n")
            wrapper.chmod(0o755)
            result = self.execute(directory, binaries, env)
            self.assertNotEqual(result.returncode, 0)
            self.assertNotIn("COMMIT;", (directory / "capture.sql").read_text(encoding="utf-8"))
            self.assertNotIn(b"restore committed", result.stdout)

    def test_mysql_failure_is_not_success(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            _, binaries, env = self.fixture(directory)
            env["MOCK_MYSQL_EXIT"] = "1"
            result = self.execute(directory, binaries, env)
            self.assertNotEqual(result.returncode, 0)
            self.assertNotIn(b"restore committed", result.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
