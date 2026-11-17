#!/usr/bin/env python3
"""Restore bundled source resources offline, verifying everything before writing.

Run this once after cloning, before Maven or frontend packaging. Correct existing
resources are skipped; a different existing file causes the entire run to stop.
No file is deleted or overwritten. Python 3.9+ standard library only.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import re
import stat
import sys
import zipfile
from pathlib import Path


FORMAT = "novel-source-assets-v1"
PART_SIZE = 1024 * 1024
SHA256 = re.compile(r"[0-9a-f]{64}\Z")


class BundleError(ValueError):
    """An invalid bundle or unsafe/conflicting destination."""


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def regular_file(path: Path) -> bool:
    return path.exists() and not path.is_symlink() and stat.S_ISREG(path.lstat().st_mode)


def no_links(path: Path) -> None:
    # Junctions and symlinks are not followed, including in existing parents.
    for item in [path, *path.parents]:
        try:
            reparse = bool(getattr(item.lstat(), "st_file_attributes", 0) &
                           getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0x400))
        except FileNotFoundError:
            reparse = False
        if item.is_symlink() or reparse or (hasattr(item, "is_junction") and item.is_junction()):
            raise BundleError(f"Link or junction is not allowed: {item}")


def safe_relative(value: object) -> str:
    if not isinstance(value, str) or not value or "\\" in value:
        raise BundleError("Resource path is not a portable relative path")
    parts = value.split("/")
    if any(not p or p in {".", ".."} or p.endswith((".", " ")) for p in parts):
        raise BundleError(f"Unsafe resource path: {value!r}")
    if any(any(ord(c) < 32 or c in '<>:"|?*' for c in p) for p in parts):
        raise BundleError(f"Unsafe resource path: {value!r}")
    reserved = {"con", "prn", "aux", "nul", *[f"com{i}" for i in range(1, 10)],
                *[f"lpt{i}" for i in range(1, 10)]}
    if any(p.split(".")[0].casefold() in reserved or p.casefold() == ".git" for p in parts):
        raise BundleError(f"Reserved resource path: {value!r}")
    if parts[0].casefold() == "source-assets" or value.casefold() in {
        ".gitignore", ".gitattributes", "scripts/restore-binary-resources.py"
    }:
        raise BundleError(f"Resource path conflicts with bundle infrastructure: {value!r}")
    return value


def positive_size(value: object, label: str, *, allow_zero: bool = True) -> int:
    if type(value) is not int or value < (0 if allow_zero else 1):
        raise BundleError(f"Invalid byte size: {label}")
    return value


def digest(value: object, label: str) -> str:
    if not isinstance(value, str) or not SHA256.fullmatch(value):
        raise BundleError(f"Invalid SHA-256: {label}")
    return value


def unique_json(pairs: list[tuple[str, object]]) -> dict:
    result = {}
    for key, value in pairs:
        if key in result:
            raise BundleError(f"Duplicate JSON field: {key}")
        result[key] = value
    return result


def load_bundle(bundle: Path) -> tuple[dict, dict[str, bytes]]:
    no_links(bundle)
    manifest_path = bundle / "manifest.json"
    if not regular_file(manifest_path) or manifest_path.stat().st_size > 8 * 1024 * 1024:
        raise BundleError("Missing, unsafe or oversized manifest.json")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8-sig"), object_pairs_hook=unique_json)
    if not isinstance(manifest, dict) or manifest.get("format") != FORMAT:
        raise BundleError("Unsupported resource manifest format")
    archive = manifest.get("archive")
    parts = manifest.get("parts")
    files = manifest.get("files")
    if not isinstance(archive, dict) or not isinstance(parts, list) or not parts or not isinstance(files, list) or not files:
        raise BundleError("Missing archive, parts or resource inventory")
    archive_size = positive_size(archive.get("bytes"), "archive", allow_zero=False)
    archive_hash = digest(archive.get("sha256"), "archive")
    if archive.get("type") != "zip" or archive_size > 1024 * 1024 * 1024:
        raise BundleError("Unsupported archive type or archive larger than 1 GiB")

    resources = {}
    path_keys = set()
    expected_members = {}
    for entry in files:
        if not isinstance(entry, dict):
            raise BundleError("Invalid resource record")
        name = safe_relative(entry.get("path"))
        if name.casefold() in path_keys:
            raise BundleError(f"Duplicate resource path: {name}")
        path_keys.add(name.casefold())
        size = positive_size(entry.get("bytes"), name)
        checksum = digest(entry.get("sha256"), name)
        member = entry.get("member")
        if member != "blobs/" + checksum:
            raise BundleError(f"Invalid archive member for resource: {name}")
        if member in expected_members and expected_members[member] != (size, checksum):
            raise BundleError("Conflicting sizes for a content-addressed member")
        expected_members[member] = (size, checksum)
        resources[name] = member
    if manifest.get("file_count") != len(resources) or archive.get("member_count") != len(expected_members):
        raise BundleError("Resource/member count does not match inventory")
    if manifest.get("original_bytes") != sum(entry["bytes"] for entry in files):
        raise BundleError("Total resource byte count does not match inventory")

    chunks = []
    part_names = []
    for index, part in enumerate(parts, 1):
        if not isinstance(part, dict) or part.get("path") != f"resources-{index:03d}.bin":
            raise BundleError("Invalid or out-of-order archive part name")
        name = part["path"]
        size = positive_size(part.get("bytes"), name, allow_zero=False)
        if size > PART_SIZE or (index < len(parts) and size != PART_SIZE):
            raise BundleError(f"Archive part has invalid size: {name}")
        checksum = digest(part.get("sha256"), name)
        path = bundle / name
        if not regular_file(path) or path.stat().st_size != size:
            raise BundleError(f"Missing or wrong-sized archive part: {name}")
        raw = path.read_bytes()
        if sha256(raw) != checksum:
            raise BundleError(f"Archive part SHA-256 mismatch: {name}")
        chunks.append(raw)
        part_names.append(name)
    if {path.name for path in bundle.glob("*.bin")} != set(part_names):
        raise BundleError("Unexpected or missing .bin archive parts")
    raw_archive = b"".join(chunks)
    if len(raw_archive) != archive_size or sha256(raw_archive) != archive_hash:
        raise BundleError("Reassembled archive size or SHA-256 mismatch")

    verified = {}
    with zipfile.ZipFile(io.BytesIO(raw_archive), "r") as zf:
        inventory = zf.infolist()
        names = [info.filename for info in inventory]
        if len(names) != len(set(names)) or set(names) != set(expected_members):
            raise BundleError("ZIP member inventory differs from manifest")
        for info in inventory:
            size, checksum = expected_members[info.filename]
            mode = info.external_attr >> 16
            if info.is_dir() or stat.S_ISLNK(mode) or (mode and stat.S_IFMT(mode) not in (0, stat.S_IFREG)):
                raise BundleError("ZIP member is not a regular file")
            if info.flag_bits & 1 or info.file_size != size:
                raise BundleError("Encrypted or wrong-sized ZIP member")
            raw = zf.read(info)
            if len(raw) != size or sha256(raw) != checksum:
                raise BundleError("Resource SHA-256 does not match manifest")
            verified[info.filename] = raw
    return manifest, {name: verified[member] for name, member in resources.items()}


def restore(bundle: Path, destination: Path, *, verify_only: bool = False) -> dict:
    manifest, resources = load_bundle(bundle)
    destination = destination.absolute()
    no_links(destination)
    if not destination.is_dir():
        raise BundleError(f"Destination must be an existing directory: {destination}")
    pending = []
    skipped = 0
    # Preflight every destination before creating even a directory.
    for relative, raw in resources.items():
        target = destination.joinpath(*relative.split("/"))
        no_links(target)
        if target.exists():
            if not regular_file(target) or sha256(target.read_bytes()) != sha256(raw):
                raise BundleError(f"Existing resource differs; refusing to overwrite: {relative}")
            skipped += 1
            continue
        for parent in target.parents:
            if parent == destination:
                break
            if parent.exists() and not parent.is_dir():
                raise BundleError(f"Resource parent is not a directory: {relative}")
        pending.append((target, relative, raw))
    if not verify_only:
        for target, relative, raw in pending:
            no_links(target)
            target.parent.mkdir(parents=True, exist_ok=True)
            no_links(target)
            # Exclusive creation also refuses a file that appears after preflight.
            with target.open("xb") as stream:
                stream.write(raw)
    return {"files": len(resources), "archive_members": manifest["archive"]["member_count"],
            "restored": 0 if verify_only else len(pending), "existing_correct": skipped,
            "pending": len(pending) if verify_only else 0, "verified_bytes": manifest["original_bytes"],
            "verify_only": verify_only}


def main() -> int:
    root = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--destination", type=Path, default=root, help="Existing output directory; defaults to this repository")
    parser.add_argument("--verify-only", action="store_true", help="Verify bundle and existing files without writing")
    args = parser.parse_args()
    try:
        print(json.dumps(restore(root / "source-assets", args.destination, verify_only=args.verify_only), sort_keys=True))
        return 0
    except (BundleError, OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile, RuntimeError) as error:
        print(f"Resource restore stopped: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
