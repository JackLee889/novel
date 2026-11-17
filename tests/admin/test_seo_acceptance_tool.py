"""No Docker/HTTP/DB calls: checks the acceptance tool's target refusal and password contract."""
import contextlib
import hashlib
import importlib.util
import io
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("seo_accept", Path(__file__).with_name("accept_seo.py"))
tool = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(tool)

class AcceptanceToolTest(unittest.TestCase):
    def args(self, base="http://127.0.0.1:18888", project="novel-seo-qa-20261008"):
        return ["accept_seo.py", "--env", str(ROOT / "backups/seo-deployment-20261008/qa.private.env"),
                "--base", base, "--qa-project", project, "--out", str(ROOT / "backups/seo-deployment-20261008/mock-never-created")]

    def test_remote_unrelated_local_ports_and_production_projects_are_refused_before_any_io(self):
        for base, project in [("http://128.241.233.43:18888", "novel-seo-qa-20261008"),
                              ("http://127.0.0.1:8088", "novel-seo-qa-20261008"),
                              ("http://127.0.0.1:18888/path", "novel-seo-qa-20261008"),
                              ("http://127.0.0.1:18888", "novel-plus-local")]:
            with self.subTest(base=base, project=project), patch("sys.argv", self.args(base, project)), \
                 patch.object(tool, "read_env") as env, patch.object(tool.subprocess, "run") as process, \
                 contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit):
                    tool.main()
                env.assert_not_called(); process.assert_not_called()

    def test_missing_password_is_refused_without_docker_or_http(self):
        with patch("sys.argv", self.args()), patch.object(tool, "read_env", return_value={}), \
             patch.object(tool.subprocess, "run") as process, contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit): tool.main()
            process.assert_not_called()

    def test_wrong_compose_identity_is_refused_before_mysql(self):
        class Result:
            returncode = 0
            stdout = b'[{"Config":{"Labels":{"com.docker.compose.project":"wrong"}},"State":{"Running":true}}]'
        def inspect_only(argv, **kwargs):
            self.assertEqual(["docker", "inspect", "novel-seo-qa-20261008-admin-1"], argv)
            self.assertIsNone(kwargs["input"])
            return Result()
        with patch("sys.argv", self.args()), patch.object(tool, "read_env", return_value={"ADMIN_INITIAL_PASSWORD":"public-fixture"}), \
             patch.object(tool.subprocess, "run", side_effect=inspect_only) as process:
            with self.assertRaisesRegex(RuntimeError, "identity mismatch"): tool.main()
            self.assertEqual(1, process.call_count)

    def test_utf8_fixture_hash_is_actual_two_round_shiro_binary_md5(self):
        username, password = "qa_fixture", "公用测试'\\$字符𠮷"
        first = hashlib.md5((username + "1qazxsw2" + password).encode("utf-8")).digest()
        self.assertEqual(hashlib.md5(first).hexdigest(), tool.shiro_hash(username, password))
        self.assertNotEqual(hashlib.md5(hashlib.md5((username + "1qazxsw2" + password).encode()).hexdigest().encode()).hexdigest(), tool.shiro_hash(username, password))

    def test_owned_session_challenge_parser_is_exact_and_refuses_malformed_data(self):
        key = b"RANDOMVALIDATECODEKEY"
        fixture = b"\xac\xed\x00\x05" + b"\x74" + len(key).to_bytes(2,"big") + key + b"\x74\x00\x04" + b"2468"
        self.assertEqual("2468",tool.challenge_from_owned_session(fixture))
        for invalid in (b"2468", fixture.replace(key,b"NOT_OWNED_SESSIONKEY"), fixture[:-1], fixture+b"x"*65536):
            with self.assertRaises(RuntimeError): tool.challenge_from_owned_session(invalid)

if __name__ == "__main__": unittest.main()
