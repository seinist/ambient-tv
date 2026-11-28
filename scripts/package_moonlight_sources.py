#!/usr/bin/env python3
"""Archive the exact Moonlight corresponding sources and validate full release APKs.

This covers the Moonlight integration and its native source inputs, not an
automatic compliance certification for every other library in the combined app.
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ABIS = {"arm64-v8a", "armeabi-v7a", "x86", "x86_64"}
NOTICES = {"moonlight-android-LICENSE.txt", "moonlight-common-c-LICENSE.txt",
           "SOURCE-NOTICE.txt", "OpenSSL-LICENSE.txt", "Opus-LICENSE.txt",
           "native-artifacts.git-blobs"}
OPUS = "22244de5a79bd1d6d623c32e72bf1954b56235be"
OPENSSL = "f089acdf4bc7ba94a79f4bf6eb7362c3e7d14aa9"
MOBILE_DEPS = "419349aa689e8749a9fb91a22dc45531ddd3df45"


def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args], text=True, timeout=60).strip()


def require_pin(root, pin):
    if not re.fullmatch(r"[a-f0-9]{40}", pin) or git(root, "rev-parse", "HEAD") != pin:
        raise ValueError(f"Unexpected source pin: {root.name}")
    if Path(git(root, "rev-parse", "--show-toplevel")).resolve() != root.resolve():
        raise ValueError(f"Not an independent source checkout: {root.name}")


def gitlink(root, path):
    fields = git(root, "ls-tree", "HEAD", path).split()
    if len(fields) != 4 or fields[:2] != ["160000", "commit"] or not re.fullmatch(r"[a-f0-9]{40}", fields[2]):
        raise ValueError(f"Missing pinned submodule: {path}")
    return fields[2]


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def archive_checkout(root, pin, name, out):
    require_pin(root, pin)
    target = out / f"{name}-{pin[:12]}.tar.gz"
    # Archive only committed source. Ambient's own archive includes all reviewed
    # patch files and the scripts needed to reconstruct the patched source.
    with tempfile.TemporaryFile() as tar:
        subprocess.run(["git", "-C", str(root), "archive", "--format=tar",
                        f"--prefix={name}/", pin], stdout=tar, check=True, timeout=180)
        tar.seek(0)
        with target.open("wb") as raw, gzip.GzipFile(filename="", fileobj=raw, mtime=0) as compressed:
            shutil.copyfileobj(tar, compressed)
    return {"component": name, "commit": pin, "archive": target.name, "sha256": digest(target)}


def fetch_checkout(root, url, pin):
    root.mkdir()
    subprocess.run(["git", "init", "-q", str(root)], check=True)
    subprocess.run(["git", "-C", str(root), "fetch", "--depth=1", url, pin], check=True, timeout=600)
    subprocess.run(["git", "-C", str(root), "checkout", "--detach", "FETCH_HEAD"], check=True, timeout=120)
    require_pin(root, pin)


def collect_apks(dist):
    results = {}
    for apk in sorted(dist.glob("*.apk")):
        with zipfile.ZipFile(apk) as package:
            names = set(package.namelist())
            if not all("assets/moonlight-licenses/" + notice in names for notice in NOTICES):
                raise ValueError(f"Missing Moonlight source notices: {apk.name}")
            native = [n for n in names if n.startswith("lib/") and n.endswith("/libmoonlight-core.so")]
            if len(native) != 1:
                raise ValueError(f"Expected exactly one Moonlight ABI: {apk.name}")
            abi = native[0].split("/")[1]
            if abi not in ABIS or abi in results or package.getinfo(native[0]).file_size <= 0:
                raise ValueError(f"Invalid or duplicate ABI: {apk.name}")
            results[abi] = {"apk": apk.name, "sha256": digest(apk)}
    if set(results) != ABIS:
        raise ValueError("Full release must contain one Moonlight APK for every ABI")
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dist", type=Path, default=ROOT / "dist")
    args = parser.parse_args()
    dist = args.dist.resolve()
    apks = collect_apks(dist)
    moon = ROOT / "third_party/moonlight-android"
    common_path = "app/src/main/jni/moonlight-core/moonlight-common-c"
    common = moon / common_path
    opus = moon / "app/src/main/jni/moonlight-core/opus/source"
    pins = dict(line.split("=", 1) for line in
                (ROOT / "gradle/external-versions.properties").read_text().splitlines()
                if "=" in line and not line.startswith("#"))
    with tempfile.TemporaryDirectory(prefix="moonlight-corresponding-", dir=dist) as temporary:
        temp = Path(temporary)
        out = temp / "sources"
        out.mkdir()
        sources = []
        checkouts = [(ROOT, git(ROOT, "rev-parse", "HEAD"), "ambient-tv"),
                     (moon, pins["MOONLIGHT_ANDROID_REF"], "moonlight-android"),
                     (common, gitlink(moon, common_path), "moonlight-common-c"),
                     (common / "enet", gitlink(common, "enet"), "modified-enet"),
                     (common / "nanors", gitlink(common, "nanors"), "nanors"),
                     (opus, OPUS, "opus")]
        for root, pin, name in checkouts:
            sources.append(archive_checkout(root, pin, name, out))
        for name, url, pin in (
            ("openssl", "https://github.com/openssl/openssl.git", OPENSSL),
            ("moonlight-mobile-deps", "https://github.com/cgutman/moonlight-mobile-deps.git", MOBILE_DEPS),
        ):
            root = temp / name
            fetch_checkout(root, url, pin)
            sources.append(archive_checkout(root, pin, name, out))
        notices = moon / "app/protocol-only-assets/moonlight-licenses"
        if not all((notices / notice).is_file() for notice in NOTICES):
            raise ValueError("Prepared Moonlight source notices incomplete")
        shutil.copytree(notices, out / "notices")
        (out / "manifest.json").write_text(json.dumps({
            "scope": "Moonlight and Ambient integration source; other combined-app obligations require separate review",
            "apks": apks, "sources": sources,
        }, indent=2, sort_keys=True) + "\n")
        destination = dist / "moonlight-sources"
        if destination.exists():
            raise ValueError("Remove previous moonlight-sources before generating a new package")
        out.rename(destination)


if __name__ == "__main__":
    main()
