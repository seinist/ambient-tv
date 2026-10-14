#!/usr/bin/env python3
"""Keep SVP's pinned Rust source, lockfile and vendored shader in one revision."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import tomllib
from pathlib import Path
from typing import Callable

ROOT = Path(__file__).resolve().parents[1]
REPO = "Z1xus/open-svpflow"
GIT_URL = f"https://github.com/{REPO}"
CRATES = ("svpflow1", "svpflow-core", "svpflow-wasm")
UPSTREAM_FILES = {
    "shaders/smooth.wgsl": "crates/svpflow-wasm/smooth.wgsl",
    "OPEN-SVPFLOW-LICENSE": "LICENSE",
}
UPDATE_FILES = ("Cargo.toml", "Cargo.lock", "NOTICE.txt", "upstream.json", *UPSTREAM_FILES)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def pinned_revision(root: Path) -> str:
    props = (root / "gradle/external-versions.properties").read_text()
    match = re.search(r"(?m)^SVPFLOW_REF=([0-9a-f]{40})$", props)
    if not match:
        raise RuntimeError("SVPFLOW_REF must be a full commit hash in external-versions.properties")
    return match[1]


def verify(crate: Path, revision: str) -> None:
    manifest = tomllib.loads((crate / "Cargo.toml").read_text())
    for name in CRATES:
        dep = manifest["dependencies"][name]
        if dep.get("git") != GIT_URL or dep.get("rev") != revision:
            raise RuntimeError(f"SVP {name} pin mismatch; use ./build.sh libs-update svp")
    packages = tomllib.loads((crate / "Cargo.lock").read_text())["package"]
    locked = [p for p in packages if p.get("source", "").startswith(f"git+{GIT_URL}?")]
    expected = f"git+{GIT_URL}?rev={revision}#{revision}"
    if not set(CRATES).issubset({p["name"] for p in locked}) or any(p["source"] != expected for p in locked):
        raise RuntimeError("SVP Cargo.lock does not match the managed source pin")
    upstream = json.loads((crate / "upstream.json").read_text())
    if upstream.get("revision") != revision or upstream.get("repository") != GIT_URL:
        raise RuntimeError("SVP shader provenance does not match the managed source pin")
    for local in UPSTREAM_FILES:
        if upstream["sha256"].get(local) != sha256((crate / local).read_bytes()):
            raise RuntimeError(f"SVP upstream asset modified or stale: {local}")
    if f"Revision: {revision}\n" not in (crate / "NOTICE.txt").read_text():
        raise RuntimeError("SVP NOTICE revision does not match the managed source pin")


def fingerprint(root: Path) -> str:
    crate = root / "native/ambientsvp"
    revision = pinned_revision(root)
    verify(crate, revision)
    paths = [crate / name for name in UPDATE_FILES]
    paths += sorted((crate / "src").rglob("*.rs"))
    paths += sorted((crate / "include").glob("*.h"))
    paths += [root / "scripts" / name for name in (
        "build_svp_android.sh", "vendor_svp_licenses.sh", "svp_dependency.py",
    )]
    digest = hashlib.sha256(revision.encode())
    for path in paths:
        digest.update(str(path.relative_to(root)).encode() + b"\0")
        digest.update(path.read_bytes() + b"\0")
    return digest.hexdigest()


def resolve_lock(crate: Path) -> None:
    # Keep the existing lockfile: Cargo updates the changed Git source and only
    # the dependencies it needs, rather than refreshing unrelated wgpu versions.
    subprocess.run([
        "rustup", "run", "stable", "cargo", "metadata", "--format-version", "1",
        "--manifest-path", str(crate / "Cargo.toml"),
    ], check=True, stdout=subprocess.DEVNULL)


def stage_update(root: Path, revision: str, download: Callable[[str], bytes]) -> dict[Path, bytes]:
    """Resolve in isolation; a fetch/lock failure never leaves half-updated sources."""
    if not re.fullmatch(r"[0-9a-f]{40}", revision):
        raise RuntimeError("SVP update requires an immutable full commit hash")
    crate = root / "native/ambientsvp"
    verify(crate, pinned_revision(root))
    with tempfile.TemporaryDirectory(prefix="ambient-svp-update-") as temporary:
        stage = Path(temporary) / "ambientsvp"
        shutil.copytree(crate, stage, ignore=shutil.ignore_patterns("target", ".git"))
        manifest = (stage / "Cargo.toml").read_text()
        for name in CRATES:
            manifest, count = re.subn(
                rf'(?m)^({re.escape(name)}\s*=\s*\{{[^\n]*\brev\s*=\s*")[0-9a-f]+(")',
                lambda m: m[1] + revision + m[2], manifest,
            )
            if count != 1:
                raise RuntimeError(f"Cannot update SVP dependency {name}")
        (stage / "Cargo.toml").write_text(manifest)
        hashes = {}
        for local, upstream in UPSTREAM_FILES.items():
            data = download(f"https://raw.githubusercontent.com/{REPO}/{revision}/{upstream}")
            if not data:
                raise RuntimeError(f"Empty SVP upstream asset: {upstream}")
            if local == "OPEN-SVPFLOW-LICENSE" and data != (crate / local).read_bytes():
                raise RuntimeError("Open-SVPFlow license changed; manual review required before updating")
            (stage / local).write_bytes(data)
            hashes[local] = sha256(data)
        (stage / "upstream.json").write_text(json.dumps({
            "repository": GIT_URL, "revision": revision, "sha256": hashes,
        }, indent=2) + "\n")
        notice, count = re.subn(r"(?m)^Revision: .+$", f"Revision: {revision}", (stage / "NOTICE.txt").read_text())
        if count != 1:
            raise RuntimeError("Cannot update SVP NOTICE revision")
        (stage / "NOTICE.txt").write_text(notice)
        resolve_lock(stage)
        verify(stage, revision)
        return {crate / name: (stage / name).read_bytes() for name in UPDATE_FILES}


def apply_files(updates: dict[Path, bytes], originals: dict[Path, bytes] | None = None) -> None:
    """Prepare all writes, detect concurrent edits, roll back on ordinary I/O errors."""
    originals = originals or {path: path.read_bytes() for path in updates}
    pending: dict[Path, Path] = {}
    replaced: list[Path] = []
    try:
        for path, data in updates.items():
            with tempfile.NamedTemporaryFile(dir=path.parent, prefix=f".{path.name}.", delete=False) as output:
                pending[path] = Path(output.name)
                output.write(data)
            pending[path].chmod(path.stat().st_mode & 0o777)
        for path in updates:
            if path.read_bytes() != originals[path]:
                raise RuntimeError(f"Concurrent edit detected; update aborted: {path}")
        for path, temporary in pending.items():
            os.replace(temporary, path)
            replaced.append(path)
    except BaseException:
        for path in reversed(replaced):
            path.write_bytes(originals[path])
        raise
    finally:
        for temporary in pending.values():
            temporary.unlink(missing_ok=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fingerprint", action="store_true")
    args = parser.parse_args()
    if args.fingerprint:
        print(fingerprint(ROOT))
    else:
        verify(ROOT / "native/ambientsvp", pinned_revision(ROOT))
        print("SVP source, lockfile, shader and notice match the managed pin.")


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(1)
