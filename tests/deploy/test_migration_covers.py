"""Run the real shell logic on disposable fixtures, with id/chown/cp mocks.

No Docker service or volume is started. Fixed mount paths are substituted only in
the temporary test copy; the shipped script has no environment-based path override.
"""
import hashlib
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "deploy/linux/migration/covers.sh"
COMPOSE = ROOT / "deploy/linux/migration/compose.covers.yaml"


def find_bash():
    if os.name == "nt":
        for path in (Path("C:/Program Files/Git/bin/bash.exe"),
                     Path("C:/Program Files/Git/usr/bin/bash.exe")):
            if path.is_file():
                return str(path)
    return shutil.which("bash")


BASH = find_bash()


@unittest.skipUnless(BASH, "Bash is required for shell fixture tests")
class CoverMigrationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.base = Path(self.temp.name)
        self.data = self.base / "data"
        self.target = self.base / "target"
        self.mockbin = self.base / "mockbin"
        self.target.mkdir()
        self.mockbin.mkdir()
        self.write("pic/localPic/cover/2106418450629967872.jpg", b"cover one\n")
        self.write("pic/localPic/cover/2106420586008854528.jpg", b"cover two\n")
        self.write("tables/book.tsv", b"public book fixture\n")
        self.write("cover-manifest.json", b'{"fixture":true}\n')
        self.sums()
        self.log = self.base / "chown.log"
        self.mountinfo = self.base / "mountinfo"
        self.data_shell = self.shell_path(self.data)
        self.target_shell = self.shell_path(self.target)
        self.mountinfo.write_text(
            f"1 0 0:1 / {self.data_shell} ro - fixture fixture ro\n"
            f"2 0 0:2 / {self.target_shell} rw - fixture fixture rw\n", encoding="utf-8")
        source = SCRIPT.read_text(encoding="utf-8")
        source = source.replace("DATA_ROOT=/migration-data", "DATA_ROOT=" + shlex.quote(self.data_shell))
        source = source.replace("TARGET_ROOT=/target-pic", "TARGET_ROOT=" + shlex.quote(self.target_shell))
        source = source.replace("MOUNTINFO=/proc/self/mountinfo", "MOUNTINFO=" + shlex.quote(self.shell_path(self.mountinfo)))
        source = source.replace("mktemp /tmp/novel-cover-checksums.XXXXXXXX",
                                "mktemp " + shlex.quote(self.shell_path(self.base / "novel-cover-checksums.XXXXXXXX")))
        self.runner = self.base / "covers.sh"
        self.runner.write_text(source, encoding="utf-8", newline="\n")
        self.real_cp = self.command_path("cp")
        self.mock("id", "printf '0\\n'\n")
        self.mock("chown", 'printf "%s\\n" "$*" >> "$MOCK_CHOWN_LOG"\n[ "${MOCK_CHOWN_FAIL:-0}" = 0 ]\n')
        self.mock("cp", 'if [ "${MOCK_CP_FAIL:-0}" = 1 ]; then\n'
                  '  printf "partial\\n" > "$MOCK_TARGET/partial.jpg"\n  exit 59\nfi\n'
                  '"$REAL_CP" "$@"\n'
                  'if [ "${MOCK_CP_CORRUPT:-0}" = 1 ]; then\n'
                  '  printf "tampered\\n" > "$MOCK_TARGET/localPic/cover/2106418450629967872.jpg"\nfi\n')
        self.env = os.environ.copy()
        self.env.update({"APP_UID": "10001", "APP_GID": "10001", "PIC_VOLUME_NAME": "new-pic",
                         "COVER_MIGRATION_ACK": "initialize-empty-pic:new-pic",
                         "MOCK_CHOWN_LOG": self.shell_path(self.log), "MOCK_TARGET": self.target_shell,
                         "REAL_CP": self.real_cp, "MSYS_NO_PATHCONV": "1"})

    def tearDown(self):
        self.temp.cleanup()

    def command_path(self, command):
        result = subprocess.run([BASH, "-c", 'command -v "$1"', "_", command],
                                capture_output=True, text=True, check=True)
        return result.stdout.strip()

    def shell_path(self, path):
        if os.name == "nt":
            result = subprocess.run([BASH, "-c", 'cygpath -u "$1"', "_", str(path)],
                                    capture_output=True, text=True, check=True)
            return result.stdout.strip()
        return str(path)

    def write(self, rel, data):
        path = self.data / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        return path

    def sums(self):
        rows = []
        for path in sorted(self.data.rglob("*")):
            if path.is_file() and path.name != "SHA256SUMS":
                rows.append(hashlib.sha256(path.read_bytes()).hexdigest() + "  " + path.relative_to(self.data).as_posix())
        (self.data / "SHA256SUMS").write_text("\n".join(rows) + "\n", encoding="ascii", newline="\n")

    def mock(self, name, body):
        path = self.mockbin / name
        path.write_text("#!/bin/bash\nset -eu\n" + body, encoding="utf-8", newline="\n")
        path.chmod(0o755)

    def run_tool(self, **variables):
        env = self.env.copy()
        env.update(variables)
        result = subprocess.run(
            [BASH, "-c", 'export PATH="$1:$PATH"; exec bash "$2"', "_",
             self.shell_path(self.mockbin), self.shell_path(self.runner)],
            env=env, capture_output=True, text=True, timeout=20)
        return result

    def assert_untouched_failure(self, result):
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertEqual(list(self.target.iterdir()), [])
        self.assertFalse(self.log.exists())

    def test_success_copies_only_covers_and_verifies_owner_scope(self):
        before = {p.relative_to(self.data).as_posix(): p.read_bytes() for p in self.data.rglob("*") if p.is_file()}
        result = self.run_tool()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("2 files", result.stdout)
        copied = {p.relative_to(self.target).as_posix(): p.read_bytes() for p in self.target.rglob("*") if p.is_file()}
        expected = {name.removeprefix("pic/"): content for name, content in before.items() if name.startswith("pic/")}
        self.assertEqual(copied, expected)
        self.assertEqual(self.log.read_text().strip(), "-hR -- 10001:10001 " + self.target_shell)
        self.assertEqual(before, {p.relative_to(self.data).as_posix(): p.read_bytes() for p in self.data.rglob("*") if p.is_file()})

    def test_missing_or_wrong_ack_is_rejected_without_writes(self):
        for ack in ("", "initialize-empty-pic:old-pic"):
            with self.subTest(ack=ack):
                self.assert_untouched_failure(self.run_tool(COVER_MIGRATION_ACK=ack))

    def test_nonempty_target_including_hidden_entry_is_preserved(self):
        original = self.target / ".existing"
        original.write_bytes(b"do not overwrite")
        result = self.run_tool()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("not empty", result.stderr)
        self.assertEqual(original.read_bytes(), b"do not overwrite")
        self.assertEqual(list(self.target.iterdir()), [original])
        self.assertFalse(self.log.exists())

    def test_empty_target_directory_is_still_nonempty(self):
        (self.target / "localPic").mkdir()
        result = self.run_tool()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("not empty", result.stderr)
        self.assertEqual(list(self.target.iterdir()), [self.target / "localPic"])

    def test_noncover_payload_is_not_rehashed_or_copied(self):
        (self.data / "tables/book.tsv").write_bytes(b"corrupt")
        result = self.run_tool()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse((self.target / "tables").exists())

    def test_tampered_cover_manifest_blocks_copy(self):
        (self.data / "cover-manifest.json").write_bytes(b"corrupt")
        self.assert_untouched_failure(self.run_tool())

    def test_tampered_cover_blocks_copy(self):
        (self.data / "pic/localPic/cover/2106418450629967872.jpg").write_bytes(b"corrupt")
        self.assert_untouched_failure(self.run_tool())

    def test_unlisted_file_and_unexpected_empty_pic_directory_are_rejected(self):
        extra = self.write("pic/localPic/cover/99.jpg", b"not referenced")
        self.assert_untouched_failure(self.run_tool())
        extra.unlink()  # disposable fixture only
        (self.data / "pic/extra").mkdir()
        self.assert_untouched_failure(self.run_tool())

    def test_unsafe_duplicate_and_noncover_pic_checksum_paths_are_rejected(self):
        sums = self.data / "SHA256SUMS"
        original = sums.read_text()
        for text in (original + "0" * 64 + "  ../outside\n",
                     original + original.splitlines()[0] + "\n",
                     original.replace("pic/localPic/cover/2106418450629967872.jpg", "pic/private.jpg"),
                     original.replace("tables/book.tsv", "/absolute/path")):
            with self.subTest(text=text[-80:]):
                sums.write_text(text, encoding="ascii", newline="\n")
                self.assert_untouched_failure(self.run_tool())

    def test_data_mount_must_be_read_only_and_ids_must_be_nonzero(self):
        for variables in ({"APP_UID": "0"}, {"APP_GID": "-1"}, {"APP_UID": "4294967295"}):
            with self.subTest(variables=variables):
                self.assert_untouched_failure(self.run_tool(**variables))
        self.mountinfo.write_text(f"1 0 0:1 / {self.data_shell} rw - fixture fixture rw\n"
                                 f"2 0 0:2 / {self.target_shell} rw - fixture fixture rw\n", encoding="utf-8")
        self.assert_untouched_failure(self.run_tool())

    def test_partial_copy_is_retained_and_cannot_be_retried(self):
        result = self.run_tool(MOCK_CP_FAIL="1")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((self.target / "partial.jpg").read_bytes(), b"partial\n")
        self.assertIn("retained", result.stderr)
        retry = self.run_tool()
        self.assertNotEqual(retry.returncode, 0)
        self.assertIn("not empty", retry.stderr)
        self.assertEqual((self.target / "partial.jpg").read_bytes(), b"partial\n")
        self.assertFalse(self.log.exists())

    def test_copy_corruption_keeps_target_for_diagnosis(self):
        result = self.run_tool(MOCK_CP_CORRUPT="1")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Copied covers failed", result.stderr)
        self.assertTrue((self.target / "localPic/cover/2106418450629967872.jpg").exists())
        self.assertNotEqual(self.run_tool().returncode, 0)

    def test_chown_failure_keeps_verified_copy_and_cannot_be_retried(self):
        result = self.run_tool(MOCK_CHOWN_FAIL="1")
        self.assertNotEqual(result.returncode, 0)
        self.assertTrue((self.target / "localPic/cover/2106418450629967872.jpg").exists())
        retry = self.run_tool()
        self.assertNotEqual(retry.returncode, 0)
        self.assertIn("not empty", retry.stderr)

    def test_symlink_in_source_is_rejected(self):
        link = self.data / "linked-cover"
        try:
            link.symlink_to(self.data / "pic/localPic/cover/2106418450629967872.jpg")
        except (OSError, NotImplementedError):
            self.skipTest("Host cannot create symlink fixtures")
        self.assert_untouched_failure(self.run_tool())


class CoverComposeContractTests(unittest.TestCase):
    def test_compose_only_mounts_package_and_existing_new_pic_volume(self):
        text = COMPOSE.read_text(encoding="utf-8")
        self.assertIn("profiles: [migration-covers]", text)
        self.assertIn("network_mode: none", text)
        self.assertIn("external: true", text)
        self.assertIn("volume: {nocopy: true}", text)
        self.assertIn("source: ../data", text)
        self.assertIn("source: ../migration/covers.sh", text)
        self.assertIn("target: /target-pic", text)
        self.assertEqual(text.count("bind: {create_host_path: false}"), 2)
        self.assertNotRegex(text, r"source:\s*.*shared-files")
        script = SCRIPT.read_text(encoding="utf-8")
        self.assertNotRegex(script, r"(?m)^\s*(rm|rmdir|mkfs|truncate|mkdir)\s")
        self.assertIn("cp -R --no-clobber --no-dereference", script)
        self.assertNotIn("${MIGRATION_DATA", script)
        self.assertNotIn("${TARGET_ROOT", script)


if __name__ == "__main__":
    unittest.main()
