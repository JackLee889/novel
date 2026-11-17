"""Linux mount contracts against resolved Compose JSON; never start services."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


def assert_tmpfs_targets_are_absolute(config):
    checked = []
    for name, service in config["services"].items():
        for mount in service.get("tmpfs", []):
            if not isinstance(mount, str) or not mount.startswith("/"):
                raise AssertionError(
                    f"{name}: Docker tmpfs must start with an absolute mount path: {mount!r}"
                )
            checked.append((name, mount))
    return checked


class TmpfsFixtureContractTest(unittest.TestCase):
    def test_split_yaml_flow_options_are_rejected(self):
        # Unquoted commas in a YAML flow sequence become separate Docker mounts.
        for option in ("nosuid", "noexec", "size=16m", "mode=1777"):
            with self.subTest(option=option):
                broken = {"services": {"db-init": {"tmpfs": ["/tmp:rw", option]}}}
                with self.assertRaisesRegex(AssertionError, "absolute mount path"):
                    assert_tmpfs_targets_are_absolute(broken)


class ResolvedComposeContractTest(unittest.TestCase):
    def resolve(self, jobs="false", threads="0"):
        docker = shutil.which("docker")
        if docker is None:
            self.skipTest("Docker Compose CLI is required for resolved-config verification")
        fixture = "\n".join((
            "COMPOSE_PROJECT_NAME=novel-compose-contract-fixture",
            "MYSQL_PASSWORD=synthetic-mysql-password",
            "MYSQL_ROOT_PASSWORD=synthetic-root-password",
            "REDIS_PASSWORD=synthetic-redis-password",
            "JWT_SECRET=synthetic-jwt-value",
            "CACHE_MANAGER_PASSWORD=synthetic-cache-password",
            "CRAWL_ADMIN_PASSWORD=synthetic-crawl-password",
            "WEBSITE_DOMAIN=example.invalid",
            "ADMIN_INITIAL_PASSWORD=synthetic-initial-password",
            f"CRAWL_JOBS_ENABLED={jobs}",
            f"CRAWL_UPDATE_THREAD={threads}",
            "",
        ))
        with tempfile.TemporaryDirectory(prefix="novel-compose-contract-") as directory:
            env_file = Path(directory) / "fixture.env"
            env_file.write_text(fixture, encoding="utf-8")
            # Prevent an inherited profile/file list or a user's .env from changing
            # the fixture. The only Docker operation is local config rendering.
            environment = dict(os.environ)
            for name in tuple(environment):
                if name.startswith("COMPOSE_"):
                    environment.pop(name)
            # Explicit shell values win over env-file values in Compose. Pin
            # the fixture keys here too so inherited real credentials are unused.
            environment.update(line.split("=", 1) for line in fixture.splitlines() if "=" in line)
            command = [docker, "compose", "--env-file", str(env_file),
                       "-f", str(ROOT / "deploy/linux/compose.yaml"),
                       "--profile", "crawl", "--profile", "db-init",
                       "config", "--format", "json"]
            result = subprocess.run(command, cwd=ROOT, env=environment,
                                    capture_output=True, text=True,
                                    encoding="utf-8", timeout=30)
        self.assertEqual(result.returncode, 0, result.stderr)
        return json.loads(result.stdout)

    def test_all_profile_tmpfs_mounts_have_absolute_targets(self):
        checked = assert_tmpfs_targets_are_absolute(self.resolve())
        self.assertEqual({name for name, _ in checked}, {"front", "admin", "crawl", "db-init"})

    def test_crawler_job_configuration_can_enable_updates_and_disable_migration_jobs(self):
        for enabled, count in (("false", "0"), ("true", "1")):
            with self.subTest(enabled=enabled):
                settings = self.resolve(enabled, count)["services"]["crawl"]["environment"]
                self.assertEqual(settings["CRAWL_JOBS_ENABLED"], enabled)
                self.assertEqual(settings["CRAWL_UPDATE_THREAD"], count)


if __name__ == "__main__":
    unittest.main()
