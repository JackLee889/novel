"""Small synthetic releases exercise safety and completeness without real JARs."""
import hashlib
import importlib.util
import contextlib
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
import zipfile


SCRIPT = Path(__file__).resolve().parents[2] / "scripts/package-linux.py"
SPEC = importlib.util.spec_from_file_location("package_linux", SCRIPT)
pkg = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(pkg)


class LinuxPackageTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name) / "repo"
        self.root.mkdir()
        self.write("LICENSE", b"License\n")
        for name, text in {"README.md": "Manual new-database SQL only\r\n",
                           "compose.yaml": "services: {}\r\n", "Dockerfile": "FROM example\r\n",
                           "entrypoint.sh": "#!/bin/sh\r\nexec java \"$@\"\r\n",
                           "config/.env.example": "DB_PASSWORD=\r\n"}.items():
            self.write("deploy/linux/" + name, text.encode())
        for app, start in pkg.START_CLASSES.items():
            jar_path = self.root / ("novel-" + app) / "target" / ("novel-" + app + "-1.jar")
            jar_path.parent.mkdir(parents=True)
            with zipfile.ZipFile(jar_path, "w") as jar:
                jar.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\nMain-Class: org.springframework.boot.loader.launch.JarLauncher\r\nStart-Class: " + start + "\r\n")
                jar.writestr("BOOT-INF/classes/" + start.replace(".", "/") + ".class", b"fixture")
        self.version = "1.13.8-matcha-icefall-zh-en"
        assets = []
        for name in sorted(pkg.ASSETS):
            payload = ("fixture " + name).encode()
            self.write("reader-resources/" + self.version + "/" + name, payload)
            assets.append({"name": name, "url": "/reader/tts/" + self.version + "/" + name,
                           "bytes": len(payload), "sha256": hashlib.sha256(payload).hexdigest()})
        self.manifest = {"engineVersion": "1.13.8", "modelId": "matcha-icefall-zh-en",
                         "baseUrl": "/reader/tts/" + self.version + "/",
                         "totalBytes": sum(a["bytes"] for a in assets), "assets": assets}
        self.sync_manifests()
        self.write("reader-resources/README.md", b"Same release assets\n")
        self.write("reader-resources/" + self.version + "/LICENSE", b"Apache\n")
        self.write("novel-front/target/build/templates/green/html/book/reader.html", b"Actual reader fixture\n")
        self.write("doc/sql/novel_plus.sql", b"DROP TABLE IF EXISTS example;\n")
        self.write("doc/sql/20261008.sql", b"ALTER TABLE example ADD value INT;\n")
        self.write("doc/sql/repair-example.sql", b"SELECT 1;\n")

    def tearDown(self):
        self.temp.cleanup()

    def write(self, name, data):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        return path

    def sync_manifests(self):
        data = json.dumps(self.manifest).encode()
        for name in ("reader-resources/" + self.version + "/asset-manifest.json",
                     "novel-front/src/main/resources/static/reader/tts-manifest.json",
                     "novel-front/target/build/templates/green/static/reader/tts-manifest.json"):
            self.write(name, data)

    def build(self, version="test-1", output="dist"):
        return pkg.build(self.root, Path(output), version)

    def test_allowlist_complete_release_lf_modes_and_no_data_or_secrets(self):
        self.write("deploy/linux/config/.env.example", b"\xef\xbb\xbfDB_PASSWORD=\r\n")
        self.write("deploy/linux/.dockerignore", b"*\r\n!Dockerfile\r\n")
        self.write("deploy/linux/config/logback-spring.xml", b"<configuration/>\r\n")
        for name in (".env", "deploy/private.env", "backups/secret.pem", ".reader-tools/java.exe",
                     "doc/sql/novel_plus_data.sql.zip", "novel-front/target/classes/private.env"):
            self.write(name, b"DO NOT SHIP")
        result = self.build()
        release = Path(result["release"])
        self.assertTrue(pkg.verify(release)["verified"])
        self.assertEqual((release / "entrypoint.sh").read_bytes(), b'#!/bin/sh\nexec java "$@"\n')
        self.assertEqual((release / "config/.env.example").read_bytes(), b"DB_PASSWORD=\n")
        self.assertEqual((release / ".dockerignore").read_bytes(), b"*\n!Dockerfile\n")
        self.assertEqual((release / "sql/novel_plus.sql").read_bytes(), b"DROP TABLE IF EXISTS example;\n")
        with tarfile.open(result["archive"]) as archive:
            members = {m.name: m for m in archive.getmembers()}
            prefix = release.name + "/"
            self.assertEqual(members[prefix + "entrypoint.sh"].mode, 0o755)
            self.assertEqual(members[prefix + "apps/front.jar"].mode, 0o644)
            self.assertIn(prefix + "LICENSE", members)
            self.assertIn(prefix + "config/logback-spring.xml", members)
            self.assertIn(prefix + "sql/20261008.sql", members)
            self.assertIn(prefix + "sql/repair-example.sql", members)
            self.assertFalse(any("DO NOT SHIP" in archive.extractfile(m).read().decode(errors="ignore")
                                 for m in members.values() if m.isfile()))
            self.assertFalse(any("target/" in name or "deploy/linux/" in name for name in members))

    def test_public_input_secret_is_rejected_before_creating_output(self):
        for name in (".env", ".ENV", "db.private.yaml", "server.key", "credentials.json", "config/.dockerignore", ".Dockerignore"):
            with self.subTest(name=name):
                file = self.write("deploy/linux/" + name, b"SECRET")
                with self.assertRaises(pkg.PackageError):
                    self.build()
                self.assertFalse((self.root / "dist").exists())
                file.unlink()

    def test_visitor_migration_is_packaged_without_allowing_other_add_scripts(self):
        migration = (b"CREATE TABLE IF NOT EXISTS site_visitor_stats (id TINYINT PRIMARY KEY, "
                     b"visitors BIGINT NOT NULL DEFAULT 0, page_views BIGINT NOT NULL DEFAULT 0);\n"
                     b"INSERT IGNORE INTO site_visitor_stats(id) VALUES(1);\n")
        self.write("doc/sql/add-visitor-stats.sql", migration)
        self.write("doc/sql/add-unapproved-feature.sql", b"SELECT 'must not ship';\n")
        result = self.build()
        release = Path(result["release"])
        self.assertEqual((release / "sql/add-visitor-stats.sql").read_bytes(), migration)
        self.assertTrue(pkg.verify(release)["verified"])
        self.assertFalse(pkg.allowed_release_file("sql/add-unapproved-feature.sql"))
        self.assertFalse((release / "sql/add-unapproved-feature.sql").exists())
        with tarfile.open(result["archive"]) as archive:
            prefix = release.name + "/"
            self.assertEqual(archive.extractfile(prefix + "sql/add-visitor-stats.sql").read(), migration)
            self.assertNotIn(prefix + "sql/add-unapproved-feature.sql", archive.getnames())

    def test_separate_data_bundle_tools_and_tests_are_not_shipped(self):
        self.write("deploy/linux/migration/import.sh", b"DATA BUNDLE TOOL")
        self.write("deploy/linux/migration/tests/test_import.py", b"TEST ONLY")
        self.write("deploy/linux/migration/private.env", b"PRIVATE MIGRATION CONFIG")
        result = self.build()
        release = Path(result["release"])
        self.assertFalse((release / "migration").exists())
        self.assertTrue(pkg.verify(release)["verified"])

    def test_version_and_output_path_guards(self):
        for version in ("../escape", "/absolute", "a\\b", "a:b", ".hidden", "a..b", "a.", "x\nsecret"):
            with self.subTest(version=version), self.assertRaises(pkg.PackageError):
                self.build(version)
        for output in ("../outside", "dist/../../outside", "reader-resources/releases", "."):
            with self.subTest(output=output), self.assertRaises(pkg.PackageError):
                self.build(output=output)
        self.assertFalse((self.root / "dist").exists())

    def test_release_and_archive_never_overwritten(self):
        result = self.build()
        archive = Path(result["archive"])
        original = archive.read_bytes()
        with self.assertRaises(pkg.PackageError):
            self.build()
        self.assertEqual(archive.read_bytes(), original)
        (self.root / "dist/novel-plus-other.tar.gz").write_bytes(b"KEEP")
        with self.assertRaises(pkg.PackageError):
            self.build("other")
        self.assertEqual((self.root / "dist/novel-plus-other.tar.gz").read_bytes(), b"KEEP")

    def test_missing_bad_duplicate_and_wrong_identity_jars(self):
        path = self.root / "novel-front/target/novel-front-1.jar"
        data = path.read_bytes()
        path.unlink()
        with self.assertRaisesRegex(pkg.PackageError, "exactly one"):
            self.build()
        path.write_bytes(b"not a jar")
        with self.assertRaisesRegex(pkg.PackageError, "Invalid JAR"):
            self.build()
        path.write_bytes(data)
        duplicate = path.with_name("old.jar")
        duplicate.write_bytes(data)
        with self.assertRaisesRegex(pkg.PackageError, "exactly one"):
            self.build()
        duplicate.unlink()
        path.write_bytes((self.root / "novel-admin/target/novel-admin-1.jar").read_bytes())
        with self.assertRaisesRegex(pkg.PackageError, "identity"):
            self.build()
        self.assertFalse((self.root / "dist").exists())

    def test_asset_missing_hash_size_case_duplicate_and_url_guards(self):
        baseline = json.loads(json.dumps(self.manifest))
        for problem in ("hash", "size", "case", "duplicate", "url", "total"):
            with self.subTest(problem=problem):
                self.manifest = json.loads(json.dumps(baseline))
                a = self.manifest["assets"][0]
                if problem == "hash": a["sha256"] = "0" * 64
                if problem == "size": a["bytes"] += 1
                if problem == "case": a["name"] = a["name"].upper()
                if problem == "duplicate": self.manifest["assets"][1] = a.copy()
                if problem == "url": a["url"] = "https://other.example/" + a["name"]
                if problem == "total": self.manifest["totalBytes"] += 1
                self.sync_manifests()
                with self.assertRaises(pkg.PackageError): self.build()
        self.manifest = baseline
        self.sync_manifests()
        (self.root / "reader-resources" / self.version / self.manifest["assets"][0]["name"]).unlink()
        with self.assertRaisesRegex(pkg.PackageError, "Missing input"):
            self.build()

    def test_stale_generated_manifest_and_missing_license(self):
        self.write("novel-front/target/build/templates/green/static/reader/tts-manifest.json", b"{}")
        with self.assertRaises(pkg.PackageError): self.build()
        self.sync_manifests()
        (self.root / "reader-resources" / self.version / "LICENSE").unlink()
        with self.assertRaisesRegex(pkg.PackageError, "Missing input"):
            self.build()

    def test_symlink_in_selected_tree_and_output_is_rejected(self):
        secret = self.write("secret.txt", b"NEVER SHIP")
        link = self.root / "deploy/linux/linked.md"
        try:
            link.symlink_to(secret)
        except OSError as error:
            self.skipTest("Host lacks symlink creation privilege: " + str(error))
        with self.assertRaisesRegex(pkg.PackageError, "Links"):
            self.build()
        link.unlink()
        (self.root / "dist").symlink_to(self.root / "reader-resources", target_is_directory=True)
        with self.assertRaisesRegex(pkg.PackageError, "Links"):
            self.build()

    def test_verify_tamper_unlisted_file_and_checksum_traversal(self):
        result = self.build()
        release = Path(result["release"])
        readme = release / "README.md"
        original = readme.read_bytes()
        readme.write_bytes(b"tampered")
        with self.assertRaisesRegex(pkg.PackageError, "SHA-256"):
            pkg.verify(release)
        readme.write_bytes(original)
        extra = release / ".env"
        extra.write_bytes(b"secret")
        with self.assertRaisesRegex(pkg.PackageError, "file list"):
            pkg.verify(release)
        extra.unlink()
        sums = release / "SHA256SUMS"
        sums.write_text("0" * 64 + "  ../escape\n", encoding="utf-8")
        with self.assertRaisesRegex(pkg.PackageError, "Unsafe relative path"):
            pkg.verify(release)

    def test_jar_path_traversal_rejected(self):
        path = self.root / "novel-front/target/novel-front-1.jar"
        with zipfile.ZipFile(path, "a") as jar:
            jar.writestr("../escape", b"unsafe")
        with self.assertRaisesRegex(pkg.PackageError, "Unsafe relative path"):
            self.build()

    def test_verify_disallowed_file_even_with_recomputed_checksums(self):
        result = self.build()
        release = Path(result["release"])
        (release / "private-dump.sql.zip").write_bytes(b"dump")
        with (release / "SHA256SUMS").open("a", encoding="utf-8", newline="\n") as sums:
            sums.write(pkg.digest(release / "private-dump.sql.zip") + "  private-dump.sql.zip\n")
        with self.assertRaisesRegex(pkg.PackageError, "Unsafe or duplicate"):
            pkg.verify(release)

    def test_uppercase_shell_extension_lf_and_archive_mode(self):
        self.write("deploy/linux/health.SH", b"#!/bin/sh\r\necho ok\r\n")
        result = self.build()
        release = Path(result["release"])
        self.assertNotIn(b"\r", (release / "health.SH").read_bytes())
        with tarfile.open(result["archive"]) as archive:
            self.assertEqual(archive.getmember(release.name + "/health.SH").mode, 0o755)

    def test_cli_json_is_ascii_for_unicode_output_path(self):
        stream = io.StringIO()
        with contextlib.redirect_stdout(stream):
            pkg.main(["--root", str(self.root), "--output", "dist/发布", "--version", "ascii-test"])
        output = stream.getvalue()
        self.assertTrue(output.isascii())
        self.assertIn("发布", json.loads(output)["release"])

    def test_existing_boot_two_admin_launcher_is_supported(self):
        path = self.root / "novel-admin/target/novel-admin-1.jar"
        with zipfile.ZipFile(path) as archive:
            entries = {name: archive.read(name) for name in archive.namelist()}
        entries["META-INF/MANIFEST.MF"] = entries["META-INF/MANIFEST.MF"].replace(
            b"org.springframework.boot.loader.launch.JarLauncher", b"org.springframework.boot.loader.JarLauncher")
        with zipfile.ZipFile(path, "w") as archive:
            for name, data in entries.items(): archive.writestr(name, data)
        self.assertTrue(self.build()["verified"])


if __name__ == "__main__":
    unittest.main()
