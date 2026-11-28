#!/usr/bin/env python3
"""Collect corresponding-source candidates and notices from the actual build tree.

This is evidence for review, not a license compliance certificate. A release
review must check dependencies, generated sources, redistribution terms and any
additional source inputs required by the licenses.
"""
import argparse
import gzip
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parents[1]
ROOTS = {
    "monogram": ("third_party/monogram",),
    "smarttube": ("third_party/smarttube/MediaServiceCore", "third_party/smarttube/SharedModules"),
    "spotify": ("third_party/spotify/rusty-src",),
    "mpv": (".native/mpv-android/buildscripts",),
    "vlc": (".native/libvlcjni",),
    "libretrodroid": ("third_party/libretrodroid",),
    "projectm": (".native/projectm/source", ".native/projectm/assets"),
    "svp": ("native/ambientsvp",),
    "shaders": ("third_party/.librashader-src", "app/src/main/assets/games/slang"),
    "moonlight": ("third_party/moonlight-android",),
}
CARGO_MANIFESTS = {
    "svp": ("native/ambientsvp/Cargo.toml",),
    "monogram": ("third_party/monogram/native/Cargo.toml", "third_party/monogram/native/mtproto/Cargo.toml"),
    "spotify": ("third_party/spotify/rusty-src/rust/Cargo.toml",),
    "shaders": ("third_party/.librashader-src/Cargo.toml",),
}

# These are build outputs/caches, not corresponding source. Do not copy
# credentials, local config, VCS metadata, native binaries or signing keys.
EXCLUDED_DIRS = {".git", ".gradle", ".cxx", ".idea", "__pycache__", "target", "obj", "node_modules", "ambient-built"}
EXCLUDED_SUFFIXES = {".apk", ".aar", ".dex", ".so", ".a", ".o", ".class", ".jks", ".keystore", ".p12", ".pfx", ".key"}
NOTICE = re.compile(r"^(license|licence|copying|notice|copyright|unlicense|authors)([._ -].*|$)", re.I)
MAX_SOURCE_BYTES = 1_800_000_000
MAX_SOURCE_FILES = 500_000
MAX_INVENTORY_BYTES = 512 * 1024


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def safe_path(relative):
    return not any(part in EXCLUDED_DIRS or part in (".", "..", "") or
                   part.lower() in ("local.properties", "keystore.properties", "private-keys.env",
                                    "gradle.properties.local", ".env", ".netrc", "credentials")
                   for part in relative.parts) and relative.suffix.lower() not in EXCLUDED_SUFFIXES


def source_files(root, preserve_vendor=False):
    # For git worktrees include tracked files as modified by the actual build,
    # including recursively checked-out submodules. Also capture generated
    # source/patches and non-git source trees from explicitly selected roots.
    for directory, dirs, names in os.walk(root, followlinks=False):
        base = Path(directory)
        if preserve_vendor:
            # Cargo's checksums cover package inputs, including directories
            # named build and any packaged binary fixtures. Do not silently
            # apply the ordinary build-output exclusions to vendor sources.
            dirs[:] = sorted(dirs)
            for name in dirs + names:
                item = base / name
                relative = item.relative_to(root)
                if (item.is_symlink() or not safe_archive_name(relative.as_posix())
                        or any(part.lower() in (".git", ".env", ".netrc", "credentials",
                                               "local.properties", "keystore.properties", "private-keys.env",
                                               "gradle.properties.local") for part in relative.parts)
                        or item.suffix.lower() in (".jks", ".keystore", ".p12", ".pfx", ".key")):
                    raise ValueError(f"Unsafe Cargo vendor input: {relative}")
        else:
            dirs[:] = sorted(d for d in dirs if safe_path((base / d).relative_to(root)) and
                             not (base / d).is_symlink() and d not in ("build", "prefix", "install"))
        for name in sorted(names):
            path = base / name
            relative = path.relative_to(root)
            if preserve_vendor:
                if not path.is_file():
                    raise ValueError(f"Invalid Cargo vendor input: {relative}")
            elif not safe_path(relative) or path.is_symlink() or not path.is_file():
                continue
            yield path, relative


def validate_source_archive(path, descriptor, notice_hashes=None):
    """Independently verify the collected source tree before signing a release.

    Stream regular tar members without extracting them. A matching compressed
    digest alone does not establish that the candidate contains its declared
    source roots, build recipes or inventory.
    """
    if not isinstance(descriptor, dict) or descriptor.get("schema") != 1 or descriptor.get("reviewRequired") is not True:
        raise ValueError("Invalid source descriptor")
    module = descriptor.get("module")
    commit = descriptor.get("commit")
    rows = descriptor.get("roots")
    if module not in ROOTS or not isinstance(commit, str) or not re.fullmatch(r"[a-f0-9]{40}", commit) or not isinstance(rows, list) or not rows:
        raise ValueError("Invalid source inventory identity")
    expected = {}
    for row in rows:
        if not isinstance(row, dict) or set(row) != {"root", "files", "notices"}:
            raise ValueError("Invalid source root inventory")
        name, count, notices = row["root"], row["files"], row["notices"]
        if not isinstance(name, str) or not safe_archive_name(name) or name in expected or type(count) is not int or type(notices) is not int or not 0 <= notices <= count <= MAX_SOURCE_FILES or count == 0:
            raise ValueError("Invalid source root inventory")
        expected[name] = (count, notices)
    if not set(ROOTS[module]) <= expected.keys() or sum(count for count, _ in expected.values()) > MAX_SOURCE_FILES:
        raise ValueError("Missing or oversized source roots")
    if any(other.startswith(name + "/") for name in expected for other in expected if name != other):
        raise ValueError("Overlapping source roots")
    root_indices = {name: index for index, name in enumerate(expected)}
    cargo_roots = [name for name in expected if name.rsplit("/", 1)[-1] == "resolved-cargo-source"]
    if module in CARGO_MANIFESTS and len(cargo_roots) != 1:
        raise ValueError("Missing or ambiguous resolved Cargo source root")
    cargo_root = cargo_roots[0] if cargo_roots else None
    maven_roots = [name for name in expected if name.rsplit("/", 1)[-1] == "resolved-smarttube-maven-source"]
    if module == "smarttube" and len(maven_roots) != 1:
        raise ValueError("Missing or ambiguous resolved SmartTube Maven source root")
    maven_root = maven_roots[0] if maven_roots else None
    maven_inventory = None
    maven_files = {}
    cargo_inventory = None
    cargo_files = {}
    cargo_checksums = {}
    cargo_locks = {}
    original_locks = {str(Path(manifest).with_name("Cargo.lock")): None
                      for manifest in CARGO_MANIFESTS.get(module, ())}
    if not path.is_file() or path.is_symlink() or path.name != descriptor.get("asset") or type(descriptor.get("size")) is not int or not 0 < path.stat().st_size <= MAX_SOURCE_BYTES or path.stat().st_size != descriptor["size"] or digest(path) != descriptor.get("sha256"):
        raise ValueError("Corresponding-source hash or size mismatch")

    seen = set()
    actual = {name: [0, 0] for name in expected}
    # Longest first avoids ambiguity for nested workspace roots.
    prefixes = sorted(expected, key=len, reverse=True)
    total = 0
    inventory = None
    j2v8 = None
    vlc_contrib = None
    vlc_archives = {}
    with gzip.open(path, "rb") as stream, tarfile.open(fileobj=stream, mode="r|") as archive:
        for member in archive:
            name = member.name
            if (not member.isreg() or member.issparse() or not safe_archive_name(name)
                    or name in seen or len(seen) >= MAX_SOURCE_FILES + 2):
                raise ValueError("Unsafe or duplicate source archive member")
            seen.add(name)
            total += member.size
            if member.size < 0 or total > MAX_SOURCE_BYTES + MAX_INVENTORY_BYTES:
                raise ValueError("Source archive expansion exceeds bounds")
            if name == "ambient-tv-source.tar":
                if member.size == 0:
                    raise ValueError("Empty host source archive")
            elif name == "source-inventory.json":
                if not 0 < member.size <= MAX_INVENTORY_BYTES:
                    raise ValueError("Oversized source inventory")
                inventory = json.load(archive.extractfile(member))
            else:
                root = next((root for root in prefixes if name.startswith(f"upstream/{root}/")), None)
                if root is None:
                    raise ValueError("Unexpected source archive member")
                actual[root][0] += 1
                relative = name.removeprefix(f"upstream/{root}/")
                workspace_path = f"{root}/{relative}"
                if workspace_path in original_locks:
                    original_locks[workspace_path] = hashlib.file_digest(archive.extractfile(member), "sha256").hexdigest()
                member_digest = None
                if root == cargo_root:
                    if relative == "workspaces.json":
                        if not 0 < member.size <= MAX_INVENTORY_BYTES:
                            raise ValueError("Invalid Cargo workspace inventory")
                        cargo_inventory = json.load(archive.extractfile(member))
                    elif re.fullmatch(r"workspace-[0-9]+/(Cargo.lock|cargo-source-config.toml)", relative):
                        if not 0 < member.size <= 16 * 1024**2:
                            raise ValueError("Invalid vendored Cargo metadata")
                        contents = archive.extractfile(member).read()
                        member_digest = hashlib.sha256(contents).hexdigest()
                        cargo_files[relative] = member_digest
                        if relative.endswith("/Cargo.lock"):
                            import tomllib
                            cargo_locks[relative] = tomllib.loads(contents.decode("utf-8"))
                    elif re.match(r"^workspace-[0-9]+/vendor/", relative):
                        if relative.endswith("/.cargo-checksum.json"):
                            if not 0 < member.size <= 16 * 1024**2:
                                raise ValueError("Invalid Cargo package checksum metadata")
                            contents = archive.extractfile(member).read()
                            member_digest = hashlib.sha256(contents).hexdigest()
                            cargo_checksums[relative] = json.loads(contents)
                        else:
                            member_digest = hashlib.file_digest(archive.extractfile(member), "sha256").hexdigest()
                        cargo_files[relative] = member_digest
                    else:
                        raise ValueError("Unexpected vendored Cargo member")
                if root == maven_root:
                    if relative == "maven-inventory.json":
                        if not 0 < member.size <= 16 * 1024**2:
                            raise ValueError("Invalid Maven source inventory size")
                        maven_inventory = json.load(archive.extractfile(member))
                    else:
                        member_digest = hashlib.file_digest(archive.extractfile(member), "sha256").hexdigest()
                        maven_files[relative] = member_digest
                if module == "smarttube" and root.endswith("/resolved-j2v8-provenance") and name == f"upstream/{root}/j2v8-provenance.json":
                    if j2v8 is not None or not 0 < member.size <= MAX_INVENTORY_BYTES:
                        raise ValueError("Invalid J2V8 provenance member")
                    j2v8 = json.load(archive.extractfile(member))
                if module == "vlc":
                    prefix = "upstream/.native/libvlcjni/vlc/contrib/tarballs/"
                    if name.startswith(prefix) and "/" not in name[len(prefix):] and is_vlc_source_tarball(name[len(prefix):]):
                        vlc_archives[name[len(prefix):]] = (member.size, hashlib.file_digest(archive.extractfile(member), "sha256").hexdigest())
                    if root.endswith("/resolved-vlc-contrib") and name == f"upstream/{root}/vlc-contrib-inventory.json":
                        if vlc_contrib is not None or not 0 < member.size <= MAX_INVENTORY_BYTES:
                            raise ValueError("Invalid VLC contrib inventory member")
                        vlc_contrib = json.load(archive.extractfile(member))
                if NOTICE.match(name.rsplit("/", 1)[-1]) and 0 < member.size <= 4 * 1024 * 1024:
                    actual[root][1] += 1
                    if notice_hashes is not None:
                        relative = name.removeprefix(f"upstream/{root}/")
                        notice_hashes[f"source-{root_indices[root]}/{relative}"] = (
                            member.size, member_digest or hashlib.file_digest(archive.extractfile(member), "sha256").hexdigest())
        # Consume the remaining gzip stream, including its checksum, rather
        # than accepting an archive truncated after the tar end marker.
        trailing = 0
        while chunk := stream.read(64 * 1024):
            trailing += len(chunk)
            if trailing > 1024 * 1024 or any(chunk):
                raise ValueError("Unexpected source archive trailing data")
    if "ambient-tv-source.tar" not in seen or inventory != {key: descriptor[key] for key in ("schema", "module", "commit", "roots", "reviewRequired")}:
        raise ValueError("Source archive inventory mismatch")
    if any(tuple(actual[name]) != expected[name] for name in expected):
        raise ValueError("Source root file or notice count mismatch")
    if module in CARGO_MANIFESTS:
        validate_cargo_inventory(module, cargo_inventory, cargo_files, original_locks, cargo_checksums, cargo_locks)
    if module == "smarttube":
        validate_maven_inventory(maven_inventory, maven_files)
        from package_system_modules import ABIS
        if not isinstance(j2v8, dict) or j2v8.get("schema") != 1 or j2v8.get("nativeSourceCorrespondence") != "unverified":
            raise ValueError("Missing or invalid J2V8 provenance")
        rows = j2v8.get("libraries", [])
        if len(rows) != len(ABIS) or {row.get("abi") for row in rows} != set(ABIS):
            raise ValueError("Incomplete J2V8 provenance")
        for row in rows:
            if not all(re.fullmatch(r"[a-f0-9]{64}", row.get(key, "")) for key in ("inputSha256", "textSha256", "apkSha256")) or not re.fullmatch(r"[a-f0-9]{40}", row.get("gitBlob", "")):
                raise ValueError("Invalid J2V8 binary fingerprints")
    if module == "vlc":
        if not isinstance(vlc_contrib, dict) or vlc_contrib.get("schema") != 1 or vlc_contrib.get("reviewRequired") is not True:
            raise ValueError("Missing VLC contrib provenance")
        ffmpeg = vlc_contrib.get("ffmpegCommit", "")
        rows = vlc_contrib.get("archives", [])
        if not re.fullmatch(r"[a-f0-9]{40}", ffmpeg) or not isinstance(rows, list) or not rows:
            raise ValueError("Invalid VLC contrib provenance")
        recorded = {row["file"]: (row["size"], row["sha256"]) for row in rows}
        if len(recorded) != len(rows) or recorded != vlc_archives:
            raise ValueError("VLC contrib archive inventory mismatch")
        if not any(row["file"] == f"ffmpeg-{ffmpeg}.tar.xz" and row.get("gitCommit") == ffmpeg for row in rows) or not any(
                row["file"].startswith("libplacebo-") and row.get("recipeVerification") == "sha512" for row in rows):
            raise ValueError("Missing pinned VLC contrib source evidence")
    return j2v8


def validate_cargo_inventory(module, inventory, files, original_locks, checksums, locks):
    """Match every archived resolved workspace to its original Cargo.lock."""
    if not isinstance(inventory, list) or not inventory:
        raise ValueError("Missing Cargo workspace inventory")
    expected_manifests = {manifest for manifest in CARGO_MANIFESTS[module]
                          if original_locks.get(str(Path(manifest).with_name("Cargo.lock"))) is not None}
    seen = set()
    prefixes = set()
    for index, row in enumerate(inventory):
        if not isinstance(row, dict) or set(row) != {"workspace", "manifest", "lockSha256"}:
            raise ValueError("Invalid Cargo workspace inventory")
        prefix = f"workspace-{index}"
        manifest = row["manifest"]
        if row["workspace"] != prefix or manifest not in expected_manifests or manifest in seen:
            raise ValueError("Unexpected Cargo workspace")
        seen.add(manifest)
        prefixes.add(prefix)
        lock_hash = original_locks[str(Path(manifest).with_name("Cargo.lock"))]
        if row["lockSha256"] != lock_hash or files.get(f"{prefix}/Cargo.lock") != lock_hash:
            raise ValueError("Vendored Cargo lockfile differs from original")
        if not files.get(f"{prefix}/cargo-source-config.toml"):
            raise ValueError("Missing reusable Cargo source configuration")
        vendor = f"{prefix}/vendor/"
        lock = locks.get(f"{prefix}/Cargo.lock")
        if not isinstance(lock, dict):
            raise ValueError("Missing parsed Cargo lockfile")
        verify_vendor_packages(vendor, files, checksums, lock)
    if seen != expected_manifests or any(path.split("/", 1)[0] not in prefixes for path in files):
        raise ValueError("Incomplete Cargo workspace inventory")


def verify_vendor_packages(prefix, files, checksums, lock):
    """Require every locked external crate and every file listed by Cargo.

    Registry archive checksums are bound to Cargo.lock; Git dependencies
    carry a null package checksum, as generated by cargo vendor. Their
    revision remains recorded in the lockfile, not independently rebuilt.
    """
    required = {}
    packages = lock.get("package")
    if not isinstance(packages, list):
        raise ValueError("Missing Cargo lock package list")
    for package in packages:
        if not isinstance(package, dict):
            raise ValueError("Invalid Cargo lock package")
        source = package.get("source")
        if source is None:
            continue
        name, version = package.get("name"), package.get("version")
        if not isinstance(name, str) or not re.fullmatch(r"[A-Za-z0-9_-]+", name) or not isinstance(version, str) or not re.fullmatch(r"[0-9A-Za-z.+-]+", version):
            raise ValueError("Invalid locked Cargo package identity")
        identity = f"{name}-{version}"
        if not isinstance(source, str) or identity in required:
            raise ValueError("Ambiguous locked Cargo package identity")
        checksum = package.get("checksum")
        if source.startswith("registry+"):
            if not isinstance(checksum, str) or not re.fullmatch(r"[a-f0-9]{64}", checksum):
                raise ValueError("Missing locked registry checksum")
        elif source.startswith("git+"):
            if checksum is not None or not re.search(r"#[a-f0-9]{40}$", source):
                raise ValueError("Missing pinned Cargo Git revision")
        else:
            raise ValueError("Unsupported Cargo dependency source")
        required[identity] = checksum
    actual = {}
    for path, digest_value in files.items():
        if not path.startswith(prefix):
            continue
        relative = path.removeprefix(prefix)
        crate, separator, file = relative.partition("/")
        if not separator or not file:
            raise ValueError("Unexpected Cargo vendor member")
        actual.setdefault(crate, {})[file] = digest_value
    if not required or set(actual) != set(required):
        raise ValueError("Vendored Cargo package set differs from lockfile")
    for crate, package_files in actual.items():
        checksum_path = f"{prefix}{crate}/.cargo-checksum.json"
        metadata = checksums.get(checksum_path)
        if not isinstance(metadata, dict) or set(metadata) != {"files", "package"} or metadata["package"] != required[crate]:
            raise ValueError(f"Cargo package checksum differs from lockfile: {crate}")
        declared = metadata["files"]
        observed = {name: value for name, value in package_files.items() if name != ".cargo-checksum.json"}
        if not isinstance(declared, dict) or not declared or "Cargo.toml" not in declared or any(
                not safe_archive_name(name) or not isinstance(value, str) or not re.fullmatch(r"[a-f0-9]{64}", value)
                for name, value in declared.items()) or observed != declared:
            raise ValueError(f"Missing, extra or changed vendored Cargo file: {crate}")


def safe_archive_name(name):
    return bool(name) and len(name) <= 4096 and "\\" not in name and not any(
        part in ("", ".", "..") or any(ord(char) < 32 or ord(char) == 127 for char in part)
        for part in name.split("/"))


def add_file(archive, path, name, budget):
    size = path.stat().st_size
    if size > budget[0]:
        raise ValueError("Corresponding-source archive exceeds size budget")
    budget[0] -= size
    info = tarfile.TarInfo(name)
    info.size = size
    info.mode = 0o755 if path.stat().st_mode & 0o111 else 0o644
    with path.open("rb") as stream:
        archive.addfile(info, stream)


def add_bytes(archive, name, data):
    info = tarfile.TarInfo(name)
    info.size = len(data)
    info.mode = 0o644
    archive.addfile(info, io.BytesIO(data))


def vendor_cargo(module, destination):
    candidates = CARGO_MANIFESTS.get(module, ())
    if not candidates:
        return None
    manifests = [ROOT / path for path in candidates
                 if (ROOT / path).is_file() and (ROOT / path).with_name("Cargo.lock").is_file()]
    if not manifests:
        raise ValueError(f"{module}: missing resolved Cargo workspace and lockfile")
    destination.mkdir(parents=True, exist_ok=False)
    # A module can contain several separately resolved Cargo workspaces.
    # Preserve each lockfile, not just the first one encountered.
    inventory = []
    for index, manifest in enumerate(manifests):
        workspace = destination / f"workspace-{index}"
        workspace.mkdir()
        lock = manifest.with_name("Cargo.lock")
        original_lock = lock.read_bytes()
        config = subprocess.check_output(["cargo", "vendor", "--locked", "--versioned-dirs",
                                          "--manifest-path", str(manifest.resolve()), "vendor"],
                                         cwd=workspace, text=True)
        if lock.read_bytes() != original_lock:
            raise ValueError(f"{module}: Cargo.lock changed during source collection")
        if not (workspace / "vendor").is_dir() or not any((workspace / "vendor").iterdir()):
            raise ValueError(f"{module}: missing vendored source")
        # No build-runner absolute path in the reusable Cargo configuration.
        config = config.replace(str((workspace / "vendor").resolve()), "vendor")
        (workspace / "cargo-source-config.toml").write_text(config)
        (workspace / "Cargo.lock").write_bytes(original_lock)
        inventory.append({"workspace": workspace.name,
                          "manifest": manifest.relative_to(ROOT).as_posix(),
                          "lockSha256": hashlib.sha256(original_lock).hexdigest()})
    (destination / "workspaces.json").write_text(json.dumps(inventory, sort_keys=True) + "\n")
    return destination


SMARTTUBE_MAVEN_REQUIRED = {
    "com.jayway.jsonpath:json-path", "net.minidev:json-smart", "com.grack:nanojson",
    "com.github.florianingerl.util:regex", "com.jakewharton:disklrucache",
}


def validate_maven_inventory(inventory, files):
    if not isinstance(inventory, list) or not 1 <= len(inventory) <= 128:
        raise ValueError("Missing or invalid Maven source inventory")
    seen = set()
    declared = {}
    for row in inventory:
        if not isinstance(row, dict) or set(row) != {"coordinate", "binarySha256", "sourceSha256",
                                                    "sources", "binaryNotices", "files"}:
            raise ValueError("Invalid Maven source inventory row")
        coordinate = row["coordinate"]
        if not isinstance(coordinate, str) or not re.fullmatch(r"[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.+-]+", coordinate):
            raise ValueError("Invalid Maven coordinate")
        group, artifact, version = coordinate.split(":")
        if any(part in (".", "..") for part in (group, artifact, version)):
            raise ValueError("Unsafe Maven coordinate")
        identity = f"{group}:{artifact}"
        if identity in seen or (identity not in SMARTTUBE_MAVEN_REQUIRED and group not in {"net.minidev", "org.ow2.asm"}):
            raise ValueError("Unexpected or duplicate Maven coordinate")
        seen.add(identity)
        if any(not isinstance(row[key], str) or not re.fullmatch(r"[a-f0-9]{64}", row[key])
               for key in ("binarySha256", "sourceSha256")):
            raise ValueError("Invalid Maven artifact fingerprints")
        entries = row["files"]
        if not isinstance(entries, dict) or not 1 <= len(entries) <= 65536:
            raise ValueError("Missing Maven source file inventory")
        counts = {"sources": 0, "binaryNotices": 0}
        for name, value in entries.items():
            if not safe_archive_name(name) or not isinstance(value, str) or not re.fullmatch(r"[a-f0-9]{64}", value):
                raise ValueError("Invalid Maven source file fingerprint")
            folder, sep, relative = name.partition("/")
            if not sep or folder not in {"sources", "binary-notices"} or not relative:
                raise ValueError("Unexpected Maven source file")
            if folder == "binary-notices" and not NOTICE.match(relative.rsplit("/", 1)[-1]):
                raise ValueError("Unexpected Maven binary notice")
            counts["sources" if folder == "sources" else "binaryNotices"] += 1
            declared[f"{group}/{artifact}/{version}/{name}"] = value
        if not counts["sources"] or any(type(row[key]) is not int or row[key] != count for key, count in counts.items()):
            raise ValueError("Maven source counts differ from inventory")
    if not SMARTTUBE_MAVEN_REQUIRED <= seen or declared != files:
        raise ValueError("Missing, extra or changed Maven source file")


def collect_smarttube_maven_sources(inputs, destination):
    """Expand exact resolved source jars and copy embedded binary notices.

    Keep only source/notice material plus binary and source digests, not the
    binary JAR itself. Missing source artifacts must stop candidate generation.
    """
    import zipfile
    if not inputs.is_dir() or inputs.is_symlink():
        raise ValueError("Missing resolved SmartTube Maven sources")
    destination.mkdir(parents=True, exist_ok=False)
    inventory = []
    seen = set()
    budget = 128 * 1024**2
    for source in sorted(inputs.glob("*/*/*/sources.jar")):
        group, artifact, version = source.relative_to(inputs).parts[:3]
        identity = f"{group}:{artifact}"
        if identity not in SMARTTUBE_MAVEN_REQUIRED and group not in {"net.minidev", "org.ow2.asm"}:
            raise ValueError("Unexpected private Maven dependency")
        if identity in seen or not re.fullmatch(r"[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.+-]+", f"{identity}:{version}"):
            raise ValueError("Invalid or duplicate resolved Maven coordinate")
        seen.add(identity)
        binary = source.with_name("binary.jar")
        if any(not item.is_file() or item.is_symlink() or not 0 < item.stat().st_size <= 64 * 1024**2 for item in (source, binary)):
            raise ValueError("Invalid resolved Maven artifact")
        target = destination / group / artifact / version
        target.mkdir(parents=True)
        counts = {"sources": 0, "binaryNotices": 0}
        fingerprints = {}
        for jar, folder, notices_only in ((source, "sources", False), (binary, "binary-notices", True)):
            with zipfile.ZipFile(jar) as archive:
                names = set()
                for member in archive.infolist():
                    name = member.filename
                    if member.is_dir():
                        continue
                    if not safe_archive_name(name) or name in names or (member.external_attr >> 16) & 0xf000 not in (0, 0x8000):
                        raise ValueError("Unsafe Maven JAR entry")
                    names.add(name)
                    if len(names) > 32768:
                        raise ValueError("Maven JAR entry count exceeds bounds")
                    if notices_only and not NOTICE.match(name.rsplit("/", 1)[-1]):
                        continue
                    if not notices_only and (not safe_path(Path(name)) or name.endswith((".jar", ".zip"))):
                        continue
                    if member.file_size > budget or member.file_size > 16 * 1024**2:
                        raise ValueError("Maven sources exceed expansion bounds")
                    budget -= member.file_size
                    data = archive.read(member)
                    dest = target / folder / name
                    dest.parent.mkdir(parents=True, exist_ok=True)
                    dest.write_bytes(data)
                    fingerprints[f"{folder}/{name}"] = hashlib.sha256(data).hexdigest()
                    counts["binaryNotices" if notices_only else "sources"] += 1
        if not counts["sources"]:
            raise ValueError("Empty Maven source artifact")
        inventory.append({"coordinate": f"{identity}:{version}", "binarySha256": digest(binary),
                          "sourceSha256": digest(source), "files": fingerprints, **counts})
    if not SMARTTUBE_MAVEN_REQUIRED <= seen:
        raise ValueError("Incomplete SmartTube Maven source inventory")
    observed = {path.relative_to(destination).as_posix(): digest(path)
                for path, _ in source_files(destination)}
    validate_maven_inventory(inventory, observed)
    (destination / "maven-inventory.json").write_text(json.dumps(inventory, sort_keys=True) + "\n")
    return destination



def elf_text_digest(data):
    with tempfile.NamedTemporaryFile() as binary:
        binary.write(data)
        binary.flush()
        sections = subprocess.check_output(["readelf", "-SW", binary.name], text=True)
    matches = re.findall(r"^\s*\[\s*\d+\]\s+\.text\s+PROGBITS\s+[0-9a-fA-F]+\s+([0-9a-fA-F]+)\s+([0-9a-fA-F]+)\s", sections, re.M)
    if len(matches) != 1:
        raise ValueError("Missing or ambiguous J2V8 executable text")
    offset, size = (int(value, 16) for value in matches[0])
    if not 0 < size <= len(data) - offset:
        raise ValueError("Invalid J2V8 text section")
    return hashlib.sha256(data[offset:offset + size]).hexdigest()


def collect_j2v8_provenance(shared, dist, destination):
    """Prove binary lineage across stripping, not native source correspondence."""
    import zipfile
    from package_system_modules import ABIS
    commit = subprocess.check_output(["git", "-C", str(shared), "rev-parse", "HEAD"], text=True).strip()
    if not re.fullmatch(r"[a-f0-9]{40}", commit):
        raise ValueError("Invalid SharedModules commit")
    versions = re.findall(r'(?m)^\s*versionName\s+"([0-9.]+)"\s*$', (shared / "j2v8/build.gradle").read_text())
    if len(versions) != 1:
        raise ValueError("Cannot determine vendored J2V8 version")
    inputs = {}
    for abi in ABIS:
        relative = f"j2v8/src/main/jniLibs/{abi}/libj2v8.so"
        path = shared / relative
        if not path.is_file() or path.is_symlink() or not 0 < path.stat().st_size <= 256 * 1024**2:
            raise ValueError(f"Missing vendored J2V8 binary: {abi}")
        data = path.read_bytes()
        blob = hashlib.sha1(f"blob {len(data)}\0".encode() + data).hexdigest()
        expected = subprocess.check_output(["git", "-C", str(shared), "rev-parse", f"{commit}:{relative}"], text=True).strip()
        if blob != expected:
            raise ValueError(f"J2V8 prebuilt differs from pinned Git blob: {abi}")
        inputs[abi] = {"abi": abi, "gitBlob": blob, "inputSha256": hashlib.sha256(data).hexdigest(),
                       "textSha256": elf_text_digest(data)}
    seen = set()
    for apk in sorted(dist.glob("*.apk")):
        with zipfile.ZipFile(apk) as archive:
            for abi in ABIS:
                name = f"lib/{abi}/libj2v8.so"
                if name not in archive.namelist():
                    continue
                if abi in seen or archive.namelist().count(name) != 1:
                    raise ValueError("Duplicate Full J2V8 ABI")
                info = archive.getinfo(name)
                if not 0 < info.file_size <= 256 * 1024**2:
                    raise ValueError("Full J2V8 exceeds bounds")
                data = archive.read(info)
                row = inputs[abi]
                if elf_text_digest(data) != row["textSha256"]:
                    raise ValueError(f"Full J2V8 executable text differs from pinned input: {abi}")
                row["apkSha256"] = hashlib.sha256(data).hexdigest()
                seen.add(abi)
    if seen != set(ABIS):
        raise ValueError("Missing Full J2V8 ABI provenance")
    destination.mkdir(parents=True, exist_ok=False)
    result = {"schema": 1, "sharedModulesCommit": commit, "declaredJ2v8Version": versions[0],
              "nativeSourceCorrespondence": "unverified", "libraries": [inputs[abi] for abi in ABIS]}
    (destination / "j2v8-provenance.json").write_text(json.dumps(result, sort_keys=True) + "\n")
    return destination


def is_vlc_source_tarball(name):
    return name.endswith((".tar.gz", ".tgz", ".tar.bz2", ".tbz2", ".tar.xz", ".txz", ".tar.zst", ".zip", ".tar", ".gz", ".bz2", ".xz"))


def collect_vlc_contrib_provenance(contrib, destination, ffmpeg_commit):
    """Inventory the exact downloaded inputs, not merely VLC recipe versions."""
    if not re.fullmatch(r"[a-f0-9]{40}", ffmpeg_commit):
        raise ValueError("Invalid FFmpeg source pin")
    tarballs = contrib / "tarballs"
    if not tarballs.is_dir() or tarballs.is_symlink():
        raise ValueError("Missing VLC contrib source tarballs")
    checksums = {}
    for checksum in sorted((contrib / "src").glob("*/SHA512SUMS")):
        for line in checksum.read_text().splitlines():
            match = re.fullmatch(r"([a-fA-F0-9]{128})\s+\*?([^/\\]+)", line.strip())
            if match:
                value, filename = match.groups()
                checksums.setdefault(filename, set()).add(value.lower())
    rows = []
    ffmpeg_found = False
    placebo_found = False
    for path in sorted(tarballs.iterdir()):
        if not is_vlc_source_tarball(path.name):
            continue
        if path.is_symlink() or not path.is_file() or not 0 < path.stat().st_size <= MAX_SOURCE_BYTES:
            raise ValueError("Invalid VLC contrib source tarball")
        with path.open("rb") as stream:
            sha512 = hashlib.file_digest(stream, "sha512").hexdigest()
        row = {"file": path.name, "size": path.stat().st_size, "sha256": digest(path),
               "sha512": sha512, "recipeVerification": "unmatched"}
        expected = checksums.get(path.name)
        if expected:
            if sha512 not in expected:
                raise ValueError(f"VLC contrib SHA512 mismatch: {path.name}")
            row["recipeVerification"] = "sha512"
        if path.name.endswith(".tar.xz"):
            pin_file = path.with_name(path.name.removesuffix(".tar.xz") + ".githash")
            if pin_file.exists():
                if pin_file.is_symlink() or not pin_file.is_file() or pin_file.stat().st_size > 8192:
                    raise ValueError("Invalid VLC contrib Git archive marker")
                pin = re.fullmatch(r"([a-fA-F0-9]{40})\s+(.+)", pin_file.read_text().strip())
                if not pin or Path(pin.group(2)).name != path.name:
                    raise ValueError("Invalid VLC contrib Git archive marker")
                row["gitCommit"] = pin.group(1).lower()
                row["recipeVerification"] = "git-marker"
        if path.name == f"ffmpeg-{ffmpeg_commit}.tar.xz":
            if row.get("gitCommit") != ffmpeg_commit:
                raise ValueError("VLC FFmpeg archive does not match pinned commit")
            ffmpeg_found = True
        if path.name.startswith("libplacebo-") and row["recipeVerification"] == "sha512":
            placebo_found = True
        rows.append(row)
    if not ffmpeg_found or not placebo_found:
        raise ValueError("Missing verified FFmpeg or libplacebo contrib archive")
    destination.mkdir(parents=True, exist_ok=False)
    (destination / "vlc-contrib-inventory.json").write_text(json.dumps({
        "schema": 1, "ffmpegCommit": ffmpeg_commit, "archives": rows,
        "reviewRequired": True,
    }, sort_keys=True) + "\n")
    return destination


def fetch_moonlight_source_dependencies(destination):
    notice = ROOT / "third_party/moonlight-android/app/protocol-only-assets/moonlight-licenses/SOURCE-NOTICE.txt"
    content = notice.read_text()
    repositories = {"openssl/openssl": "openssl", "cgutman/moonlight-mobile-deps": "mobile-deps"}
    destination.mkdir(parents=True, exist_ok=False)
    for repo, name in repositories.items():
        pins = set(re.findall(r"https://github\.com/" + re.escape(repo) + r"/tree/([a-f0-9]{40})(?![a-f0-9])", content))
        if len(pins) != 1:
            raise ValueError(f"Missing exact Moonlight corresponding-source pin: {repo}")
        pin = pins.pop()
        checkout = destination / name
        checkout.mkdir()
        subprocess.run(["git", "init", "-q", str(checkout)], check=True)
        subprocess.run(["git", "-C", str(checkout), "remote", "add", "origin", f"https://github.com/{repo}.git"], check=True)
        subprocess.run(["git", "-C", str(checkout), "fetch", "--depth", "1", "origin", pin], check=True)
        subprocess.run(["git", "-C", str(checkout), "checkout", "--detach", "FETCH_HEAD"], check=True)
        actual = subprocess.check_output(["git", "-C", str(checkout), "rev-parse", "HEAD"], text=True).strip()
        if actual != pin:
            raise ValueError(f"Moonlight dependency checkout does not match pin: {repo}")
        (destination / (name + "-commit.txt")).write_text(pin + "\n")
    return destination


def collect(module, commit, output, notices, extra_roots=()):
    if module not in ROOTS or not re.fullmatch(r"[a-f0-9]{40}", commit):
        raise ValueError("Invalid source candidate identity")
    actual = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    if actual != commit:
        raise ValueError("Source checkout differs from candidate commit")
    if output.exists() or notices.exists():
        raise ValueError("Source and notices outputs must be fresh")
    roots = [(p, ROOT / p) for p in ROOTS[module]]
    for extra in extra_roots:
        resolved = extra.resolve()
        if not resolved.is_relative_to(ROOT.resolve()):
            raise ValueError("Additional source root must be within the build workspace")
        roots.append((resolved.relative_to(ROOT).as_posix(), resolved))
    if len({name for name, _ in roots}) != len(roots):
        raise ValueError("Duplicate corresponding-source root")
    if any(other.startswith(name + "/") for name, _ in roots for other, _ in roots if name != other):
        raise ValueError("Overlapping corresponding-source roots")
    for name, path in roots:
        if not path.is_dir() or path.is_symlink():
            raise ValueError(f"Missing build source root: {name}")
    notices.mkdir(parents=True)
    (notices / "Ambient-TV-LICENSE").write_bytes((ROOT / "LICENSE").read_bytes())
    # Reviewed supplemental notices may include Maven, prebuilt archive and
    # transitive dependency license texts not supplied by an upstream checkout.
    reviewed = ROOT / "third_party_licenses/system_modules" / module
    supplemental = [reviewed]
    if module == "svp":
        supplemental.append(ROOT / "app/src/main/assets/licenses/open-svpflow")
    if module == "moonlight":
        supplemental.append(ROOT / "third_party/moonlight-android/app/protocol-only-assets/moonlight-licenses")
    inventory = []
    budget = [MAX_SOURCE_BYTES]
    output.parent.mkdir(parents=True, exist_ok=True)
    try:
        with output.open("xb") as raw, gzip.GzipFile(filename="", fileobj=raw, mode="wb", mtime=0) as compressed, \
                tarfile.open(fileobj=compressed, mode="w|") as archive:
            # Include exact tracked Ambient TV source, patches, build recipes and pins.
            # git archive does not include credentials or untracked build secrets.
            with tempfile.TemporaryDirectory() as temp:
                host = Path(temp) / "host-source.tar"
                with host.open("wb") as stream:
                    subprocess.run(["git", "archive", "--format=tar", commit], cwd=ROOT, stdout=stream, check=True)
                add_file(archive, host, "ambient-tv-source.tar", budget)
            for index, (name, root) in enumerate(roots):
                count = 0
                found = 0
                for path, relative in source_files(root, preserve_vendor=root.name == "resolved-cargo-source"):
                    add_file(archive, path, f"upstream/{name}/{relative.as_posix()}", budget)
                    count += 1
                    if NOTICE.match(path.name) and 0 < path.stat().st_size <= 4 * 1024 * 1024:
                        dest = notices / f"source-{index}" / relative
                        dest.parent.mkdir(parents=True, exist_ok=True)
                        dest.write_bytes(path.read_bytes())
                        found += 1
                if count == 0:
                    raise ValueError(f"Empty source root: {name}")
                inventory.append({"root": name, "files": count, "notices": found})
            for index, root in enumerate(supplemental):
                if not root.is_dir():
                    continue
                for path, relative in source_files(root):
                    dest = notices / f"reviewed-{index}" / relative
                    dest.parent.mkdir(parents=True, exist_ok=True)
                    dest.write_bytes(path.read_bytes())
            if not any(row["notices"] for row in inventory) and not any(p.is_dir() for p in notices.iterdir()):
                raise ValueError("No upstream license notices collected")
            info = {"schema": 1, "module": module, "commit": commit, "roots": inventory,
                    "reviewRequired": True}
            add_bytes(archive, "source-inventory.json", (json.dumps(info, sort_keys=True) + "\n").encode())
        if output.stat().st_size > MAX_SOURCE_BYTES:
            raise ValueError("Compressed corresponding-source archive exceeds release bounds")
        descriptor = {"asset": output.name, "size": output.stat().st_size, "sha256": digest(output), **info}
        validate_source_archive(output, descriptor)
        (notices / "corresponding-source.json").write_text(json.dumps(descriptor, sort_keys=True) + "\n")
        return descriptor
    except BaseException:
        output.unlink(missing_ok=True)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--module", choices=ROOTS, required=True)
    parser.add_argument("--sha", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--notices", type=Path, required=True)
    parser.add_argument("--extra-root", type=Path, action="append", default=[])
    parser.add_argument("--dist", type=Path, help="Signed Full APK directory for native binary provenance")
    args = parser.parse_args()
    extra_roots = list(args.extra_root)
    cargo = vendor_cargo(args.module, args.output.parent / "resolved-cargo-source")
    if cargo:
        extra_roots.append(cargo)
    if args.module == "smarttube":
        if args.dist is None:
            parser.error("SmartTube requires Full APKs for J2V8 provenance")
        extra_roots.append(collect_j2v8_provenance(ROOT / "third_party/smarttube/SharedModules",
            args.dist, args.output.parent / "resolved-j2v8-provenance"))
        extra_roots.append(collect_smarttube_maven_sources(
            ROOT / "app/build/outputs/system-modules/smarttube-maven-inputs",
            args.output.parent / "resolved-smarttube-maven-source"))
    if args.module == "vlc":
        pins = (ROOT / "gradle/external-versions.properties").read_text()
        matches = re.findall(r"(?m)^FFMPEG_COMMIT=([a-f0-9]{40})$", pins)
        if len(matches) != 1:
            raise ValueError("Missing FFmpeg pin")
        extra_roots.append(collect_vlc_contrib_provenance(ROOT / ".native/libvlcjni/vlc/contrib",
            args.output.parent / "resolved-vlc-contrib", matches[0]))
    if args.module == "moonlight":
        extra_roots.append(fetch_moonlight_source_dependencies(args.output.parent / "pinned-moonlight-dependencies"))
    collect(args.module, args.sha, args.output, args.notices, extra_roots)


if __name__ == "__main__":
    main()
