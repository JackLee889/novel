#!/usr/bin/env python3
"""Build an allowlisted Linux release without starting services or executing SQL."""
import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import sys
import tarfile
import zipfile


class PackageError(ValueError):
    pass


ASSETS = {"sherpa-onnx-tts.js", "sherpa-onnx-wasm-main-tts.js",
          "sherpa-onnx-wasm-main-tts.wasm", "sherpa-onnx-wasm-main-tts.data"}
START_CLASSES = {"front": "com.java2nb.novel.FrontNovelApplication",
                 "admin": "com.java2nb.AdminApplication",
                 "crawl": "com.java2nb.novel.CrawlNovelApplication"}


def fail(message):
    raise PackageError(message)


def safe_relative(name):
    if not name or any(c in name for c in "\\\r\n\x00:"):
        fail("Unsafe relative path: " + repr(name))
    parts = name.split("/")
    if any(p in ("", ".", "..") for p in parts) or PurePosixPath(name).is_absolute():
        fail("Unsafe relative path: " + repr(name))
    return name


def private_name(name):
    if name == ".dockerignore":
        return False  # The release-root build-context allowlist is a public template.
    for part in PurePosixPath(name).parts:
        lower = part.casefold()
        if (lower == ".env" or (lower.startswith(".env.") and lower != ".env.example")
                or lower.startswith(".") and lower != ".env.example"
                or re.search(r"(?:^|[._-])(private|secret|credentials?)(?:[._-]|$)", lower)
                or lower.endswith((".pem", ".key", ".p12", ".pfx", ".dpapi.json"))):
            return True
    return False


def checked_path(path, root, directory=False):
    """Reject symlinks and Windows junctions, including any selected ancestor."""
    path, root = Path(path).absolute(), Path(root).absolute()
    try:
        path.relative_to(root)
    except ValueError:
        fail("Path leaves the permitted root: " + str(path))
    for item in (path, *path.parents):
        if not item.exists() and not item.is_symlink():
            fail("Missing input: " + str(item))
        info = item.lstat()
        if stat.S_ISLNK(info.st_mode) or (getattr(info, "st_file_attributes", 0) &
                                      getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0)):
            fail("Links and junctions are not permitted: " + str(item))
        if item == root:
            break
    if directory and not path.is_dir() or not directory and not path.is_file():
        fail("Expected a " + ("directory: " if directory else "regular file: ") + str(path))
    return path


def digest(path):
    value = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def tree_files(base, root):
    checked_path(base, root, directory=True)
    for folder, dirs, files in os.walk(base, followlinks=False):
        for name in sorted(dirs):
            checked_path(Path(folder) / name, root, directory=True)
        for name in sorted(files):
            yield checked_path(Path(folder) / name, root)


def public_template(name):
    safe_relative(name)
    if private_name(name):
        return False
    parts = PurePosixPath(name).parts
    if len(parts) > 1 and parts[0] != "config":
        return False
    leaf = PurePosixPath(name).name
    return (leaf in {"Dockerfile", ".env.example"} or name == ".dockerignore" or leaf.startswith("Dockerfile.")
            or parts[0] == "config" and Path(leaf).suffix.casefold() == ".xml"
            or Path(leaf).suffix.casefold() in {".md", ".sh", ".yaml", ".yml", ".conf", ".service", ".example", ".template"})


def allowed_release_file(name):
    parts = PurePosixPath(name).parts
    if name == "LICENSE":
        return True
    if parts[0] == "apps":
        return name in {"apps/" + app + ".jar" for app in START_CLASSES}
    if parts[0] == "templates":
        return len(parts) >= 3
    if parts[0] == "reader-resources":
        return (name == "reader-resources/README.md" or len(parts) == 3 and
                bool(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,99}", parts[1])) and
                parts[2] in ASSETS | {"asset-manifest.json", "LICENSE"})
    if parts[0] == "sql":
        return len(parts) == 2 and (parts[1] in {"novel_plus.sql", "add-visitor-stats.sql"} or
                bool(re.fullmatch(r"(?:\d{8}|repair-[A-Za-z0-9_-]+)\.sql", parts[1])))
    return public_template(name)


def validate_jar(path, app):
    try:
        with zipfile.ZipFile(path) as jar:
            for item in jar.infolist():
                safe_relative(item.filename.rstrip("/"))
                if stat.S_ISLNK(item.external_attr >> 16):
                    fail("JAR contains a link: " + str(path))
            if jar.testzip() is not None:
                fail("Corrupt JAR: " + str(path))
            manifest = jar.read("META-INF/MANIFEST.MF").decode("utf-8")
            # Manifest continuation lines are part of the previous attribute.
            manifest = re.sub(r"\r?\n ", "", manifest)
            # Accept both launcher layouts; current three modules use Boot 4.1.1.
            if not re.search(r"^Main-Class: org\.springframework\.boot\.loader\.(?:launch\.)?JarLauncher\s*$", manifest, re.M):
                fail("Expected a runnable Spring Boot JAR: " + str(path))
            if not re.search(r"^Start-Class: " + re.escape(START_CLASSES[app]) + r"\s*$", manifest, re.M):
                fail("JAR application identity mismatch: " + str(path))
            entry = "BOOT-INF/classes/" + START_CLASSES[app].replace(".", "/") + ".class"
            if entry not in jar.namelist():
                fail("JAR application class missing: " + str(path))
    except (OSError, KeyError, UnicodeError, zipfile.BadZipFile, RuntimeError) as error:
        fail("Invalid JAR " + str(path) + ": " + str(error))


def read_manifest(path):
    try:
        return json.loads(Path(path).read_text(encoding="utf-8-sig"))
    except (OSError, ValueError) as error:
        fail("Invalid TTS manifest: " + str(path) + ": " + str(error))


def validate_tts(resources, template_manifest, root):
    resources = checked_path(resources, root, directory=True)
    checked_path(resources / "README.md", root)
    manifest = read_manifest(checked_path(template_manifest, root))
    version = str(manifest.get("engineVersion", "")) + "-" + str(manifest.get("modelId", ""))
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,99}", version):
        fail("Unsafe TTS version")
    base = "/reader/tts/" + version + "/"
    if manifest.get("baseUrl") != base:
        fail("TTS manifest base URL mismatch")
    model = checked_path(resources / version, root, directory=True)
    checked_path(model / "LICENSE", root)
    if read_manifest(checked_path(model / "asset-manifest.json", root)) != manifest:
        fail("Prepared resource and template manifests differ")
    assets = manifest.get("assets")
    if not isinstance(assets, list) or len(assets) != len(ASSETS):
        fail("TTS manifest must contain all four runtime assets")
    seen, total = set(), 0
    for asset in assets:
        name = asset.get("name") if isinstance(asset, dict) else None
        if name not in ASSETS or name in seen or asset.get("url") != base + name:
            fail("TTS asset name, URL or duplicate mismatch")
        seen.add(name)
        path = checked_path(model / name, root)
        # Validate actual spelling even on case-insensitive Windows filesystems.
        if name not in {child.name for child in model.iterdir()}:
            fail("TTS filename case mismatch: " + name)
        size = asset.get("bytes")
        if not isinstance(size, int) or isinstance(size, bool) or size <= 0 or path.stat().st_size != size:
            fail("TTS size mismatch: " + name)
        expected = asset.get("sha256", "")
        if not isinstance(expected, str) or not re.fullmatch(r"[0-9a-f]{64}", expected) or digest(path) != expected:
            fail("TTS SHA-256 mismatch: " + name)
        total += size
    if seen != ASSETS or manifest.get("totalBytes") != total:
        fail("TTS manifest total mismatch")
    return manifest, model


def collect(root):
    root = checked_path(root, root, directory=True)
    selected, cases = {}, {}

    def add(source, name, text=False):
        safe_relative(name)
        if private_name(name):
            fail("Private file is not permitted: " + name)
        if name.casefold() in cases:
            fail("Duplicate or case-colliding destination: " + name)
        source = checked_path(source, root)
        selected[name] = (source, text)
        cases[name.casefold()] = name

    deploy = root / "deploy/linux"
    for source in tree_files(deploy, root):
        name = source.relative_to(deploy).as_posix()
        # Book/crawler import tools belong to the separate, reviewed data bundle.
        # They and their tests are not runtime inputs for this application release.
        if PurePosixPath(name).parts[0] == "migration":
            continue
        if not public_template(name):
            fail("Non-public deployment input: " + name)
        add(source, name, True)
    for required in ("README.md", "compose.yaml", "Dockerfile", "entrypoint.sh"):
        if required not in selected:
            fail("Missing deployment template: " + required)
    add(root / "LICENSE", "LICENSE")
    for app in START_CLASSES:
        target = checked_path(root / ("novel-" + app) / "target", root, directory=True)
        jars = sorted(target.glob("*.jar"))
        if len(jars) != 1:
            fail("Expected exactly one built JAR for " + app)
        validate_jar(checked_path(jars[0], root), app)
        add(jars[0], "apps/" + app + ".jar")
    templates = root / "novel-front/target/build/templates"
    for source in tree_files(templates, root):
        add(source, "templates/" + source.relative_to(templates).as_posix())
    template_manifest = templates / "green/static/reader/tts-manifest.json"
    manifest, model = validate_tts(root / "reader-resources", template_manifest, root)
    source_manifest = checked_path(root / "novel-front/src/main/resources/static/reader/tts-manifest.json", root)
    if read_manifest(source_manifest) != manifest:
        fail("Source and freshly built template TTS manifests differ")
    for name in ("README.md",):
        add(root / "reader-resources" / name, "reader-resources/" + name)
    for name in sorted(ASSETS | {"asset-manifest.json", "LICENSE"}):
        add(model / name, "reader-resources/" + model.name + "/" + name)
    sql = checked_path(root / "doc/sql", root, directory=True)
    add(sql / "novel_plus.sql", "sql/novel_plus.sql")
    for source in sorted(sql.iterdir()):
        if source.name == "add-visitor-stats.sql" or re.fullmatch(r"(?:\d{8}|repair-[A-Za-z0-9_-]+)\.sql", source.name):
            add(source, "sql/" + source.name)
    return selected


def verify(release):
    release = Path(release).absolute()
    checked_path(release, release, directory=True)
    checksum = checked_path(release / "SHA256SUMS", release)
    expected, cases = {}, set()
    for line in checksum.read_text(encoding="utf-8").splitlines():
        if len(line) < 67 or line[64:66] != "  " or not re.fullmatch(r"[0-9a-f]{64}", line[:64]):
            fail("Invalid SHA256SUMS record")
        name = safe_relative(line[66:])
        if private_name(name) or not allowed_release_file(name) or name == "SHA256SUMS" or name.casefold() in cases:
            fail("Unsafe or duplicate SHA256SUMS file: " + name)
        cases.add(name.casefold())
        expected[name] = line[:64]
    actual = {p.relative_to(release).as_posix() for p in tree_files(release, release)} - {"SHA256SUMS"}
    if actual != set(expected):
        fail("Release file list differs from SHA256SUMS")
    for name, value in expected.items():
        if digest(checked_path(release / name, release)) != value:
            fail("Release SHA-256 mismatch: " + name)
        shell = name.casefold().endswith(".sh")
        if shell and b"\r" in (release / name).read_bytes():
            fail("Shell script must use LF: " + name)
        if shell and os.name != "nt" and stat.S_IMODE((release / name).stat().st_mode) != 0o755:
            fail("Shell script must have mode 755: " + name)
    for app in START_CLASSES:
        validate_jar(checked_path(release / "apps" / (app + ".jar"), release), app)
    validate_tts(release / "reader-resources", release / "templates/green/static/reader/tts-manifest.json", release)
    for name in ("README.md", "compose.yaml", "Dockerfile", "entrypoint.sh", "LICENSE", "sql/novel_plus.sql"):
        checked_path(release / name, release)
    return {"files": len(actual), "verified": True}


def build(root, output, version):
    root = Path(root).absolute()
    checked_path(root, root, directory=True)
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}", version) or version.endswith(".") or ".." in version:
        fail("Version must be a safe, non-path release label")
    output = Path(output)
    output = (root / output if not output.is_absolute() else output).absolute()
    try:
        relative = output.relative_to(root)
    except ValueError:
        fail("Output must remain within the project root")
    if any(part in (".", "..") for part in relative.parts) or not relative.parts or relative.parts[0] not in {"dist", "exports"}:
        fail("Output must be under dist/ or exports/ inside the project")
    for parent in (output, *output.parents):
        if parent.exists() or parent.is_symlink():
            checked_path(parent, root, directory=True)
            break
    release = output / ("novel-plus-" + version)
    archive = output / (release.name + ".tar.gz")
    if release.exists() or release.is_symlink() or archive.exists() or archive.is_symlink():
        fail("Refusing to overwrite an existing release or archive")
    selected = collect(root)  # All missing/integrity/private-file checks precede output creation.
    output.mkdir(parents=True, exist_ok=True)
    checked_path(output, root, directory=True)
    release.mkdir()  # Exclusive reservation; never merge into an old release.
    archive_created = False
    try:
        for name, (source, text) in sorted(selected.items()):
            destination = release / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            checked_path(source, root)
            if text:
                content = source.read_text(encoding="utf-8-sig").replace("\r\n", "\n").replace("\r", "\n")
                with destination.open("x", encoding="utf-8", newline="\n") as stream:
                    stream.write(content)
            else:
                with source.open("rb") as src, destination.open("xb") as dst:
                    shutil.copyfileobj(src, dst)
            destination.chmod(0o755 if name.casefold().endswith(".sh") else 0o644)
        sums = "".join(digest(release / name) + "  " + name + "\n" for name in sorted(selected))
        with (release / "SHA256SUMS").open("x", encoding="utf-8", newline="\n") as stream:
            stream.write(sums)
        result = verify(release)
        with archive.open("xb") as raw:
            archive_created = True
            with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as compressed:
                with tarfile.open(fileobj=compressed, mode="w", format=tarfile.PAX_FORMAT) as tar:
                    paths = [release, *sorted(release.rglob("*"))]
                    for path in paths:
                        checked_path(path, release, directory=path.is_dir())
                        info = tar.gettarinfo(str(path), arcname=path.relative_to(output).as_posix())
                        info.mode = 0o755 if path.is_dir() or path.suffix.casefold() == ".sh" else 0o644
                        info.uid = info.gid = info.mtime = 0
                        info.uname = info.gname = ""
                        if path.is_file():
                            with path.open("rb") as content:
                                tar.addfile(info, content)
                        else:
                            tar.addfile(info)
        return {**result, "release": str(release), "archive": str(archive), "archiveSha256": digest(archive)}
    except Exception:
        # Only outputs created in this call may be removed; originals never enter this branch.
        checked_path(release, output, directory=True)
        shutil.rmtree(release)
        if archive_created:
            checked_path(archive, output)
            archive.unlink()
        raise


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--output", type=Path, default=Path("dist"))
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument("--version", help="Safe release label, for example 20261008.1")
    action.add_argument("--verify", type=Path, help="Verify an existing release directory without changing it")
    args = parser.parse_args(argv)
    try:
        result = verify(args.verify) if args.verify else build(args.root, args.output, args.version)
    except (PackageError, OSError, UnicodeError) as error:
        parser.exit(1, "package-linux: " + str(error) + "\n")
    print(json.dumps(result, ensure_ascii=True))


if __name__ == "__main__":
    main()
