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
    "projectm": (".native/projectm/source",),
    "svp": ("native/ambientsvp",),
    "shaders": ("third_party/.librashader-src", "app/src/main/assets/games/slang"),
    "moonlight": ("third_party/moonlight-android",),
}
# These are build outputs/caches, not corresponding source. Do not copy
# credentials, local config, VCS metadata, native binaries or signing keys.
EXCLUDED_DIRS = {".git", ".gradle", ".cxx", ".idea", "__pycache__", "target", "obj", "node_modules", "ambient-built"}
EXCLUDED_SUFFIXES = {".apk", ".aar", ".dex", ".so", ".a", ".o", ".class", ".jks", ".keystore", ".p12", ".pfx", ".key"}
NOTICE = re.compile(r"^(license|licence|copying|notice|copyright|unlicense|authors)([._ -].*|$)", re.I)
MAX_SOURCE_BYTES = 1_800_000_000


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def safe_path(relative):
    return not any(part in EXCLUDED_DIRS or part in (".", "..", "") or
                   part.lower() in ("local.properties", "keystore.properties", "private-keys.env",
                                    "gradle.properties.local", ".env", ".netrc", "credentials")
                   for part in relative.parts) and relative.suffix.lower() not in EXCLUDED_SUFFIXES


def source_files(root):
    # For git worktrees include tracked files as modified by the actual build,
    # including recursively checked-out submodules. Also capture generated
    # source/patches and non-git source trees from explicitly selected roots.
    for directory, dirs, names in os.walk(root, followlinks=False):
        base = Path(directory)
        dirs[:] = sorted(d for d in dirs if safe_path((base / d).relative_to(root)) and
                         not (base / d).is_symlink() and d not in ("build", "prefix", "install"))
        for name in sorted(names):
            path = base / name
            relative = path.relative_to(root)
            if not safe_path(relative) or path.is_symlink() or not path.is_file():
                continue
            yield path, relative


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
                for path, relative in source_files(root):
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
    args = parser.parse_args()
    collect(args.module, args.sha, args.output, args.notices, args.extra_root)


if __name__ == "__main__":
    main()
